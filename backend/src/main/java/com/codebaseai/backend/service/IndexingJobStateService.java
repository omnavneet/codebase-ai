package com.codebaseai.backend.service;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.codebaseai.backend.model.IndexingJob;
import com.codebaseai.backend.model.Project;
import com.codebaseai.backend.repository.CodeChunkRepository;
import com.codebaseai.backend.repository.CodeReferenceRepository;
import com.codebaseai.backend.repository.CodeSymbolRepository;
import com.codebaseai.backend.repository.IndexingJobRepository;
import com.codebaseai.backend.repository.ProjectFileRepository;
import com.codebaseai.backend.repository.ProjectRepository;
import com.codebaseai.backend.storage.StorageService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * All job-row state changes, each in its own SHORT committed transaction.
 *
 * <p>The worker ({@link IndexingWorker}) must never hold a transaction open
 * while it extracts or embeds: those steps take minutes and would keep the
 * row lock (and a pooled connection) for the whole run, hiding progress from
 * the polling endpoint and blocking cancel. So the worker itself is NOT
 * transactional and calls into this bean instead. Because the calls cross a
 * bean boundary, Spring's proxy actually applies each {@code @Transactional}
 * below and every method commits before returning.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IndexingJobStateService {

    private final IndexingJobRepository indexingJobRepository;
    private final ProjectRepository projectRepository;
    private final ProjectFileRepository projectFileRepository;
    private final CodeChunkRepository codeChunkRepository;
    private final CodeSymbolRepository codeSymbolRepository;
    private final CodeReferenceRepository codeReferenceRepository;
    private final StorageService storageService;

    /**
     * Claims the next runnable job in ONE short transaction.
     *
     * <p>The repository's {@code SELECT ... FOR UPDATE SKIP LOCKED} picks a
     * row without waiting on rows other workers hold; this method then sets
     * {@code status=processing, locked_by, locked_at, attempts+1} and commits,
     * releasing the row lock immediately. The returned copy is detached —
     * freshness checks below re-read the row in their own short transactions.
     */
    @Transactional
    public Optional<IndexingJob> claimNext(String workerId, long staleSeconds) {
        return indexingJobRepository.claimNext(staleSeconds)
                .map(job -> {
                    job.setStatus(IndexingJob.PROCESSING);
                    job.setLockedBy(workerId);
                    job.setLockedAt(LocalDateTime.now());
                    job.setAttempts((job.getAttempts() == null ? 0 : job.getAttempts()) + 1);
                    job.setErrorMessage(null);
                    job.setUpdatedAt(LocalDateTime.now());
                    return indexingJobRepository.save(job);
                });
    }

    /**
     * Re-queues crashed jobs in one short transaction (crash recovery).
     *
     * <p>Any job still {@code processing} whose lease ({@code locked_at}) is
     * older than {@code staleSeconds} is assumed orphaned — its worker died —
     * and is flipped back to {@code queued} so {@link #claimNext} can pick it
     * up again. Attempts are left untouched here (they advance once, at claim
     * time). A healthy worker renews {@code locked_at} on every
     * {@link #heartbeat}, so a live job is never older than the timeout and is
     * never re-queued out from under its worker.
     *
     * @return how many jobs were re-queued.
     */
    @Transactional
    public int requeueStaleJobs(long staleSeconds) {
        int requeued = indexingJobRepository.requeueStale(staleSeconds, LocalDateTime.now());
        if (requeued > 0) {
            log.warn("Re-queued {} stale processing job(s) (no heartbeat for >{}s)", requeued, staleSeconds);
        }
        return requeued;
    }

    /**
     * Heartbeat: records progress and refreshes the lease in its own commit.
     *
     * <p>Visible to the polling endpoint at once. The refreshed
     * {@code locked_at} is what keeps a healthy job from looking stale to the
     * claim query, so the worker calls this after every batch, not just at
     * the end.
     *
     * @return false when the job row no longer exists (worker must stop).
     */
    @Transactional
    public boolean heartbeat(UUID jobId, int progressPercent) {
        Optional<IndexingJob> found = indexingJobRepository.findById(jobId);
        if (found.isEmpty()) {
            return false;
        }
        IndexingJob job = found.get();
        job.setProgressPercent(progressPercent);
        job.setLockedAt(LocalDateTime.now());
        job.setUpdatedAt(LocalDateTime.now());
        indexingJobRepository.save(job);
        return true;
    }

    /** Short read of the current status (used to notice cancellation). */
    @Transactional(readOnly = true)
    public Optional<String> currentStatus(UUID jobId) {
        return indexingJobRepository.findById(jobId).map(IndexingJob::getStatus);
    }

    /**
     * Marks the job ready ONLY when it is still processing.
     *
     * <p>Guarded on the status so a job the user cancelled mid-flight is never
     * overwritten back to ready: when the row says {@code cancelled} zero rows
     * change and this returns false so the worker stops quietly.
     */
    @Transactional
    public boolean completeIfProcessing(UUID jobId) {
        int updated = indexingJobRepository.markReadyIfProcessing(jobId, LocalDateTime.now());
        if (updated == 0) {
            return false;
        }
        log.info("Completed indexing job {}", jobId);
        return true;
    }

    /**
     * Records a failure in a short transaction: re-queue with backoff while
     * attempts remain, else mark error and purge the partial index.
     *
     * <p>Attempts are incremented once at claim time, so this method only
     * reads them. The terminal-error branch is guarded on
     * {@code status='processing'} like completion, so a cancelled job keeps
     * its cancellation instead of flipping to error.
     */
    @Transactional
    public void fail(UUID jobId, String errorMessage) {
        IndexingJob job = indexingJobRepository.findById(jobId).orElse(null);
        if (job == null) {
            return;
        }
        if (IndexingJob.CANCELLED.equals(job.getStatus())) {
            log.info("Job {} was cancelled; keeping cancellation instead of failing", jobId);
            return;
        }
        int attempts = job.getAttempts() == null ? 1 : job.getAttempts();
        int maxAttempts = job.getMaxAttempts() == null ? 3 : job.getMaxAttempts();
        if (attempts < maxAttempts) {
            long delaySeconds = (long) Math.pow(2, attempts) * 30; // 30s, 60s, 120s, ...
            job.setStatus(IndexingJob.QUEUED);
            job.setErrorMessage(errorMessage);
            job.setNextRunAt(LocalDateTime.now().plusSeconds(delaySeconds));
            job.setLockedBy(null);
            job.setLockedAt(null);
            job.setUpdatedAt(LocalDateTime.now());
            indexingJobRepository.save(job);
            log.info("Job {} failed (attempt {}/{}), rescheduling in {}s: {}",
                    jobId, attempts, maxAttempts, delaySeconds, errorMessage);
            return;
        }
        int updated = indexingJobRepository.markErrorIfProcessing(jobId, errorMessage, LocalDateTime.now());
        if (updated == 0) {
            return;
        }
        purgePartialIndex(job.getProjectId());
        Project project = projectRepository.findById(job.getProjectId()).orElse(null);
        if (project != null) {
            project.setStatus("error");
            project.setErrorMessage(errorMessage);
            project.setFileCount(0);
            project.setTotalSizeBytes(0L);
            projectRepository.save(project);
        }
        log.error("Job {} failed permanently after {} attempts: {}", jobId, attempts, errorMessage);
    }

    /**
     * Plain short status flip: non-terminal job to cancelled, committed at
     * once, never waiting on worker locks.
     *
     * <p>Touches only the job row: no joins, no bulk deletes in this
     * transaction. The worker holds no row lock between heartbeats, so this
     * update never blocks on long work — the worker notices the flip on its
     * next status read and stops. Slow cleanup runs afterwards in
     * {@link #purgeAfterCancel} as separate short transactions, so a big
     * partial index cannot hold the job row either.
     */
    @Transactional
    public IndexingJob cancel(UUID projectId, UUID jobId, UUID userId) {
        IndexingJob job = indexingJobRepository.findById(jobId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found"));

        Project project = projectRepository.findById(job.getProjectId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));

        if (!project.getId().equals(projectId) || !job.getProjectId().equals(projectId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found in this project");
        }

        if (!project.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }

        if (IndexingJob.READY.equals(job.getStatus())
                || IndexingJob.ERROR.equals(job.getStatus())
                || IndexingJob.CANCELLED.equals(job.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Cannot cancel job in status: " + job.getStatus());
        }

        job.setStatus(IndexingJob.CANCELLED);
        job.setErrorMessage("Cancelled by user");
        job.setLockedBy(null);
        job.setLockedAt(null);
        job.setUpdatedAt(LocalDateTime.now());
        IndexingJob saved = indexingJobRepository.save(job);
        // Enqueue flips project to "processing": a cancel must move it to a
        // terminal state the dashboard already handles. Projects have no
        // "cancelled" (only jobs do), so use "error" with a clear message.
        // Same short transaction as the job flip: no bulk deletes here.
        project.setStatus("error");
        project.setErrorMessage("Indexing cancelled");
        project.setFileCount(0);
        project.setTotalSizeBytes(0L);
        projectRepository.save(project);
        log.info("Cancelled indexing job {} for project {}", jobId, job.getProjectId());
        return saved;
    }

    /**
     * Deletes a cancelled job's partial index OUTSIDE the cancel transaction,
     * then moves the project to a terminal state the dashboard handles.
     *
     * <p>The index purge stays non-transactional (slow bulk deletes must not
     * hold the job row). The project flip below is a single repository save,
     * i.e. its own short transaction, guarded so a newer re-upload (latest
     * job != this job) or an already-ready project is never clobbered.
     */
    public void purgeAfterCancel(UUID projectId, UUID jobId) {
        try {
            purgePartialIndex(projectId);
        } catch (Exception e) {
            log.warn("Failed to clean up partial index for project {} after cancelling job {}",
                    projectId, jobId, e);
        }
        try {
            markProjectCancelledIfStale(projectId, jobId);
        } catch (Exception e) {
            log.warn("Failed to mark project {} cancelled after job {}", projectId, jobId, e);
        }
    }

    /** Removes partially indexed rows and staged files for a project. */
    @Transactional
    public void markProjectCancelledIfStale(UUID projectId, UUID jobId) {
        Project project = projectRepository.findById(projectId).orElse(null);
        if (project == null) {
            return;
        }
        // Never clobber a newer re-upload: only the latest job may move the
        // project, and never away from a finished ready state.
        if ("ready".equals(project.getStatus())) {
            return;
        }
        var latest = indexingJobRepository.findFirstByProjectIdOrderByCreatedAtDesc(projectId)
                .orElse(null);
        if (latest != null && !latest.getId().equals(jobId)) {
            return;
        }
        project.setStatus("error");
        project.setErrorMessage("Indexing cancelled");
        project.setFileCount(0);
        project.setTotalSizeBytes(0L);
        projectRepository.save(project);
    }

    /** Removes partially indexed rows and staged files for a project. */
    public void purgePartialIndex(UUID projectId) {
        codeChunkRepository.deleteByProjectId(projectId);
        codeSymbolRepository.deleteByProjectId(projectId);
        codeReferenceRepository.deleteByProjectId(projectId);
        projectFileRepository.deleteByProjectId(projectId);
        try {
            storageService.deleteProject(projectId);
        } catch (java.io.IOException e) {
            log.warn("Failed to delete project storage for {}", projectId, e);
        }
    }
}

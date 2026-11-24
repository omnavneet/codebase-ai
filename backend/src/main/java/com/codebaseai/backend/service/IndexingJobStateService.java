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
        return indexingJobRepository.claimNext()
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
     * {@link #heartbeatOwned}, so a live job is never older than the timeout and is
     * never re-queued out from under its worker.
     *
     * @return how many jobs were re-queued.
     */
    @Transactional
    public int requeueStaleJobs(long staleSeconds) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime cutoff = now.minusSeconds(staleSeconds);
        int recovered = 0;
        for (Object[] candidate : indexingJobRepository.findStaleJobIds(cutoff)) {
            UUID projectId = (UUID) candidate[0];
            UUID jobId = (UUID) candidate[1];
            Project project = projectRepository.findByIdForUpdate(projectId).orElse(null);
            IndexingJob job = indexingJobRepository.findByIdForUpdate(jobId).orElse(null);
            if (project == null || job == null || !IndexingJob.PROCESSING.equals(job.getStatus())
                    || job.getLockedAt() == null || !job.getLockedAt().isBefore(cutoff)) {
                continue;
            }
            int attempts = job.getAttempts() == null ? 0 : job.getAttempts();
            int maxAttempts = job.getMaxAttempts() == null ? 3 : job.getMaxAttempts();
            if (attempts >= maxAttempts) {
                job.setStatus(IndexingJob.ERROR);
                job.setErrorMessage("Indexing worker stopped repeatedly; upload the archive again");
                deleteUploadAfterCommit(job);
                boolean hasPublishedIndex = project.getActiveGenerationId() != null
                        || (project.getFileCount() != null && project.getFileCount() > 0);
                project.setStatus(hasPublishedIndex ? "ready"
                        : hasOtherPendingJob(projectId, jobId) ? "processing" : "error");
                project.setErrorMessage(job.getErrorMessage());
                projectRepository.save(project);
            } else {
                job.setStatus(IndexingJob.QUEUED);
                job.setNextRunAt(now);
            }
            job.setLockedBy(null);
            job.setLockedAt(null);
            job.setUpdatedAt(now);
            indexingJobRepository.save(job);
            recovered++;
        }
        if (recovered > 0) {
            log.warn("Recovered {} stale indexing job(s) (no heartbeat for >{}s)", recovered, staleSeconds);
        }
        return recovered;
    }

    @Transactional
    public boolean heartbeatOwned(UUID jobId, String workerId, int progressPercent) {
        return indexingJobRepository.heartbeatIfOwned(jobId, workerId, progressPercent, LocalDateTime.now()) == 1;
    }

    /** Publish the staged generation and finish its job in one transaction. */
    @Transactional
    public boolean publish(UUID projectId, UUID jobId, String workerId, UUID generationId,
                           int fileCount, long totalSize) {
        Project project = projectRepository.findByIdForUpdate(projectId).orElse(null);
        IndexingJob job = indexingJobRepository.findByIdForUpdate(jobId).orElse(null);
        if (project == null || job == null || !projectId.equals(job.getProjectId())
                || !IndexingJob.PROCESSING.equals(job.getStatus())
                || !workerId.equals(job.getLockedBy())) {
            return false;
        }
        project.setActiveGenerationId(generationId);
        projectRepository.recordPublishedGeneration(projectId, generationId);
        project.setFileCount(fileCount);
        project.setTotalSizeBytes(totalSize);
        project.setStatus("ready");
        project.setErrorMessage(null);
        projectRepository.save(project);
        job.setStatus(IndexingJob.READY);
        job.setProgressPercent(100);
        job.setErrorMessage(null);
        job.setLockedAt(null);
        job.setLockedBy(null);
        indexingJobRepository.save(job);
        return true;
    }

    @Transactional
    public boolean discardUnpublishedGeneration(UUID projectId, UUID generationId) {
        // Serialize with publish even when its commit outcome was ambiguous.
        Project project = projectRepository.findByIdForUpdate(projectId).orElse(null);
        if (project != null && projectRepository.isGenerationPublished(projectId, generationId)) {
            return false;
        }
        projectFileRepository.deleteByProjectIdAndGenerationId(projectId, generationId);
        return true;
    }

    /** Fail only the current lease owner; earlier attempts cannot affect a reclaim. */
    @Transactional
    public void failOwned(UUID projectId, UUID jobId, String workerId, String errorMessage, boolean permanent) {
        Project project = projectRepository.findByIdForUpdate(projectId).orElse(null);
        IndexingJob job = indexingJobRepository.findByIdForUpdate(jobId).orElse(null);
        if (project == null || job == null || !projectId.equals(job.getProjectId())
                || !IndexingJob.PROCESSING.equals(job.getStatus())
                || !workerId.equals(job.getLockedBy())) {
            return;
        }
        int attempts = job.getAttempts() == null ? 1 : job.getAttempts();
        int maxAttempts = job.getMaxAttempts() == null ? 3 : job.getMaxAttempts();
        job.setErrorMessage(errorMessage);
        job.setLockedBy(null);
        job.setLockedAt(null);
        if (!permanent && attempts < maxAttempts) {
            job.setStatus(IndexingJob.QUEUED);
            job.setNextRunAt(LocalDateTime.now().plusSeconds((long) Math.pow(2, attempts) * 30));
        } else {
            job.setStatus(IndexingJob.ERROR);
            deleteUploadAfterCommit(job);
            boolean hasPublishedIndex = project.getActiveGenerationId() != null
                    || (project.getFileCount() != null && project.getFileCount() > 0);
            project.setStatus(hasPublishedIndex ? "ready"
                    : hasOtherPendingJob(projectId, jobId) ? "processing" : "error");
            project.setErrorMessage(errorMessage);
            projectRepository.save(project);
        }
        indexingJobRepository.save(job);
    }

    @Transactional
    public IndexingJob cancel(UUID projectId, UUID jobId, UUID userId) {
        Project project = projectRepository.findByIdForUpdate(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
        IndexingJob job = indexingJobRepository.findByIdForUpdate(jobId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found"));

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
        deleteUploadAfterCommit(job);
        boolean hasPublishedIndex = project.getActiveGenerationId() != null
                || (project.getFileCount() != null && project.getFileCount() > 0);
        project.setStatus(hasPublishedIndex ? "ready"
                : hasOtherPendingJob(projectId, jobId) ? "processing" : "error");
        project.setErrorMessage("Indexing cancelled");
        projectRepository.save(project);
        log.info("Cancelled indexing job {} for project {}", jobId, job.getProjectId());
        return saved;
    }

    private void deleteUploadAfterCommit(IndexingJob job) {
        if (job.getUploadId() == null) {
            return;
        }
        UUID projectId = job.getProjectId();
        UUID uploadId = job.getUploadId();
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            storageService.deleteJobUpload(projectId, uploadId);
                        } catch (Exception e) {
                            log.warn("Failed to delete upload {} for job {}", uploadId, job.getId(), e);
                        }
                    }
                });
    }

    private boolean hasOtherPendingJob(UUID projectId, UUID jobId) {
        return indexingJobRepository.findByProjectIdAndStatusIn(
                projectId, java.util.List.of(IndexingJob.QUEUED, IndexingJob.PROCESSING)).stream()
                .anyMatch(other -> !other.getId().equals(jobId));
    }
}

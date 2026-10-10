package com.codebaseai.backend.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.codebaseai.backend.model.IndexingJob;
import com.codebaseai.backend.model.Project;
import com.codebaseai.backend.model.ProjectFile;
import com.codebaseai.backend.repository.CodeChunkRepository;
import com.codebaseai.backend.repository.CodeReferenceRepository;
import com.codebaseai.backend.repository.CodeSymbolRepository;
import com.codebaseai.backend.repository.ProjectFileRepository;
import com.codebaseai.backend.repository.ProjectRepository;
import com.codebaseai.backend.service.ZipExtractionService;
import com.codebaseai.backend.service.CodeProcessingService;
import com.codebaseai.backend.storage.StorageService;
import com.codebaseai.backend.config.AppProperties;

import lombok.RequiredArgsConstructor;

/**
 * Worker that processes indexing jobs from the queue.
 * 
 * Runs as a Spring @Scheduled task (default every 5 seconds) and claims
 * jobs using SELECT ... FOR UPDATE SKIP LOCKED so multiple workers never
 * process the same job.
 * 
 * Responsibilities:
 * - Claim jobs from the queue
 * - Extract ZIP files (with progress updates)
 * - Process extracted files (chunking and embedding with progress updates)
 * - Update job status and progress
 * - Handle retry/backoff for failed jobs
 * - Clean up partial data on cancellation or permanent failure
 * - Crash recovery (stale processing jobs are reclaimed)
 */
@Component
@RequiredArgsConstructor
public class IndexingWorker {

    private static final Logger log = LoggerFactory.getLogger(IndexingWorker.class);

    private final IndexingJobStateService jobStateService;
    private final ProjectRepository projectRepository;
    private final ProjectFileRepository projectFileRepository;
    private final CodeChunkRepository codeChunkRepository;
    private final CodeSymbolRepository codeSymbolRepository;
    private final CodeReferenceRepository codeReferenceRepository;
    private final ZipExtractionService zipExtractionService;
    private final CodeProcessingService codeProcessingService;
    private final StorageService storageService;
    private final AppProperties properties;

    private Path findStoredZip(UUID projectId) {
        Path projectDir = Paths.get(
                properties.getUpload().getDirectory(), projectId.toString());
        if (!Files.exists(projectDir)) {
            return null;
        }
        try (Stream<Path> paths = Files.list(projectDir)) {
            return paths
                    .filter(p -> Files.isRegularFile(p))
                    .filter(p -> p.getFileName().toString().startsWith("upload_") && p.toString().endsWith(".zip"))
                    .sorted(Comparator.reverseOrder())
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            log.warn("Failed to list project directory {}: {}", projectDir, e.getMessage());
            return null;
        }
    }

    /**
     * Processes the next available indexing job.
     * 
     * Runs every 5 seconds (configurable via app.indexing.workerPollIntervalMs).
     * Claims one job at a time using FOR UPDATE SKIP LOCKED.
     */
    @Scheduled(fixedDelayString = "${app.indexing.workerPollIntervalMs:5000}")
    public void processNextJob() {
        String workerId = "worker-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        long staleSeconds = properties.getIndexing().getStaleJobSeconds();

        // NOT @Transactional on purpose. The claim is one short committed
        // transaction inside the state service; extraction and embedding then
        // run with NO transaction open, so the row lock (and a pooled
        // connection) is released the moment the claim commits. Progress stays
        // visible and Cancel never waits on a lock held during the whole run.

        // Crash recovery: a job still 'processing' whose lease (locked_at) has
        // gone stale is re-queued so it can be claimed again. A healthy job
        // renews locked_at via the per-batch heartbeat, so it is never stale.
        int requeued = jobStateService.requeueStaleJobs(staleSeconds);
        if (requeued > 0) {
            log.info("Worker {} re-queued {} stale processing job(s)", workerId, requeued);
        }

        // Claim the next runnable job (queued and due, or a just-recovered
        // stale one). claimNext sets status/locked_by/locked_at and attempts+1
        // in ONE committed transaction, then returns a detached copy.
        Optional<IndexingJob> claimed = jobStateService.claimNext(workerId, staleSeconds);
        if (claimed.isEmpty()) {
            return;
        }
        IndexingJob job = claimed.get();

        // Never let an exception escape: an uncaught throw here would cancel
        // the scheduler's future runs for this task.
        try {
            processJob(job, workerId, staleSeconds);
        } catch (Exception e) {
            log.error("Worker {} failed to process job {}: {}", workerId, job.getId(), e.toString(), e);
            jobStateService.fail(job.getId(), "Worker error: " + e.getMessage());
        }
    }

    /**
     * Processes a single claimed job from start to finish.
     *
     * <p>Deliberately NOT {@code @Transactional}: the job is already claimed
     * (status/lock set and committed by {@link IndexingJobStateService#claimNext}),
     * and extraction plus embedding must run with no transaction open. Every
     * state change goes back through the state service in its own short commit,
     * so progress is visible and Cancel is never blocked by a long-held lock.
     *
     * @param job          the job returned by claimNext (detached copy)
     * @param workerId     identifier of the worker processing this job
     * @param staleSeconds stale-lease timeout (claim already applied it)
     */
    private void processJob(IndexingJob job, String workerId, long staleSeconds) {
        UUID jobId = job.getId();
        UUID projectId = job.getProjectId();
        log.info("Worker {} starting to process job {}", workerId, jobId);

        try {
            // A cancel may have landed between the claim commit and here; the
            // cheap status re-read catches it before we touch any data.
            Optional<String> status = jobStateService.currentStatus(jobId);
            if (status.isEmpty()) {
                log.warn("Job {} no longer exists", jobId);
                return;
            }
            if (IndexingJob.CANCELLED.equals(status.get())) {
                log.info("Job {} was cancelled before processing, cleaning up", jobId);
                jobStateService.purgeAfterCancel(projectId, jobId);
                return;
            }

            // A re-upload replaces the previous index instead of adding to it:
            // otherwise rows would duplicate and stale data would linger in the
            // store where the AI service could still read them.
            jobStateService.heartbeat(jobId, 5);
            codeChunkRepository.deleteByProjectId(projectId);
            codeSymbolRepository.deleteByProjectId(projectId);
            codeReferenceRepository.deleteByProjectId(projectId);
            projectFileRepository.deleteByProjectId(projectId);
            storageService.deleteProject(projectId);

            // Mark project as processing
            Project project = projectRepository.findById(projectId).orElseThrow();
            project.setStatus("processing");
            projectRepository.save(project);
            jobStateService.heartbeat(jobId, 10);

            // Step 1: Find and extract the uploaded ZIP
            Path stagedZip = findStoredZip(projectId);
            if (stagedZip == null) {
                jobStateService.fail(jobId, "No uploaded ZIP found for project");
                return;
            }
            jobStateService.heartbeat(jobId, 20);

            Path extractionDirectory = storageService.extractionDirectory(projectId);
            List<ZipExtractionService.ExtractedFile> extractedFiles =
                    zipExtractionService.extractZip(stagedZip, extractionDirectory);

            // Save extracted file metadata
            jobStateService.heartbeat(jobId, 30);
            int totalSize = 0;
            for (ZipExtractionService.ExtractedFile extractedFile : extractedFiles) {
                ProjectFile projectFile = new ProjectFile();
                projectFile.setProjectId(projectId);
                projectFile.setPath(extractedFile.getPath());
                projectFile.setSizeBytes((int) extractedFile.getSize());
                projectFile.setLanguage(com.codebaseai.backend.service.chunking.Language.fromPath(extractedFile.getPath()).getId());

                // Hash the staged file
                byte[] content = java.nio.file.Files.readAllBytes(extractedFile.getFilePath());
                String hash = org.springframework.util.DigestUtils.md5DigestAsHex(content);
                projectFile.setContentHash(hash);

                projectFileRepository.save(projectFile);
                totalSize += extractedFile.getSize();
            }

            // Commit the extracted tree to the store (no-op for local storage)
            storageService.persistExtractedFiles(projectId, extractionDirectory, extractedFiles);
            jobStateService.heartbeat(jobId, 45);

            // Step 2: Process files (chunking + embedding). The per-batch callback
            // renews the lease and stops the run if the job was cancelled.
            CodeProcessingService.ProcessingResult processingResult;
            try {
                processingResult = codeProcessingService.processProject(projectId, percentDone -> {
                    // Status re-check first, so we never write progress onto a
                    // cancelled row; then heartbeat maps 0-100 file progress onto
                    // the 45-80 band of the overall job bar.
                    Optional<String> current = jobStateService.currentStatus(jobId);
                    if (current.isEmpty()) {
                        throw new CodeProcessingService.ProcessingCancelledException(
                                "Job " + jobId + " no longer exists");
                    }
                    if (IndexingJob.CANCELLED.equals(current.get())) {
                        throw new CodeProcessingService.ProcessingCancelledException(
                                "Job " + jobId + " cancelled");
                    }
                    int overall = 45 + (int) Math.round(percentDone * 0.35);
                    jobStateService.heartbeat(jobId, overall);
                });
            } catch (CodeProcessingService.ProcessingCancelledException e) {
                // Cancelled between batches: stop, clean up the partial index,
                // and return WITHOUT marking the job ready.
                log.info("Job {} cancelled during processing: {}", jobId, e.getMessage());
                jobStateService.purgeAfterCancel(projectId, jobId);
                return;
            }

            jobStateService.heartbeat(jobId, 80);

            if (processingResult.hasFailures()) {
                String failedFiles = String.join(", ", processingResult.failedFiles());
                jobStateService.fail(jobId, "Failed to index file(s): " + failedFiles);
                return;
            }

            jobStateService.heartbeat(jobId, 90);

            // Update project status to ready
            project.setStatus("ready");
            project.setFileCount(extractedFiles.size());
            project.setTotalSizeBytes((long) totalSize);
            project.setErrorMessage(null);
            projectRepository.save(project);

            // Complete only if still processing: a cancel that landed during the
            // final steps wins, and we purge instead of flipping to ready.
            boolean completed = jobStateService.completeIfProcessing(jobId);
            if (!completed) {
                log.info("Job {} was cancelled before completion; purging partial index", jobId);
                jobStateService.purgeAfterCancel(projectId, jobId);
                return;
            }

            // Discard the staging ZIP after successful processing
            try {
                Path staged = findStoredZip(projectId);
                if (staged != null) {
                    storageService.discardStagingFile(staged);
                }
            } catch (Exception e) {
                log.warn("Failed to discard staging file for job {}: {}", jobId, e.getMessage());
            }

            log.info("Worker {} completed job {}", workerId, jobId);

        } catch (ZipLimitExceededException e) {
            log.error("Job {} failed with ZIP limit: {}", jobId, e.getMessage(), e);
            jobStateService.fail(jobId, e.getMessage());
        } catch (IOException e) {
            log.error("Job {} failed with IO error: {}", jobId, e.getMessage(), e);
            jobStateService.fail(jobId, "Failed to process upload: " + e.getMessage());
        } catch (Exception e) {
            log.error("Worker {} failed to process job {}: {}", workerId, jobId, e.toString(), e);
            jobStateService.fail(jobId, "Worker error: " + e.getMessage());
        }
    }
}

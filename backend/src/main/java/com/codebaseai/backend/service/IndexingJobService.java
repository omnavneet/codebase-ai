package com.codebaseai.backend.service;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.codebaseai.backend.dto.IndexingJobResponse;
import com.codebaseai.backend.model.IndexingJob;
import com.codebaseai.backend.model.Project;
import com.codebaseai.backend.model.ProjectFile;
import com.codebaseai.backend.repository.CodeChunkRepository;
import com.codebaseai.backend.repository.CodeReferenceRepository;
import com.codebaseai.backend.repository.CodeSymbolRepository;
import com.codebaseai.backend.repository.IndexingJobRepository;
import com.codebaseai.backend.repository.ProjectFileRepository;
import com.codebaseai.backend.repository.ProjectRepository;
import com.codebaseai.backend.service.ZipExtractionService;
import com.codebaseai.backend.service.CodeProcessingService;
import com.codebaseai.backend.storage.StorageService;
import com.codebaseai.backend.config.AppProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Service for managing indexing jobs with job queue semantics.
 * 
 * Responsibilities:
 * - Create jobs with idempotency (deduplication by content hash)
 * - Claim jobs for processing (with FOR UPDATE SKIP LOCKED)
 * - Update job progress and status
 * - Cancel jobs
 * - Handle retry/backoff logic
 * - Crash recovery (stale processing jobs)
 * - Clean up partial data on cancellation/error
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IndexingJobService {

    private final ProjectRepository projectRepository;
    private final ProjectFileRepository projectFileRepository;
    private final CodeChunkRepository codeChunkRepository;
    private final CodeSymbolRepository codeSymbolRepository;
    private final CodeReferenceRepository codeReferenceRepository;
    private final IndexingJobRepository indexingJobRepository;
    private final ZipExtractionService zipExtractionService;
    private final CodeProcessingService codeProcessingService;
    private final StorageService storageService;
    private final AppProperties properties;
    private final IndexingJobStateService jobStateService;

    /**
     * Creates an indexing job for a project upload, or returns an existing
     * queued/processing/ready job for the same project and ZIP content.
     * 
     * @param projectId Project to index
     * @param userId    User making the request (for ownership check)
     * @param file      Uploaded ZIP file
     * @return IndexingJob for the upload
     * @throws IOException if file processing fails
     */
    @Transactional
    public IndexingJob createOrGetJob(UUID projectId, UUID userId, 
            org.springframework.web.multipart.MultipartFile file) throws IOException {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));

        if (!project.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }

        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File is empty");
        }

        String originalFilename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        if (!originalFilename.toLowerCase().endsWith(".zip")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only ZIP files are allowed");
        }

        // Compute SHA-256 of the uploaded ZIP for idempotency
        String contentHash = computeContentHash(file);

        // Check for existing queued/processing/ready job with same hash
        Optional<IndexingJob> existingJob = indexingJobRepository.findFirstByProjectIdAndContentHashAndStatusInOrderByCreatedAtDesc(
                projectId, contentHash, 
                List.of(IndexingJob.QUEUED, IndexingJob.PROCESSING, IndexingJob.READY));

        if (existingJob.isPresent()) {
            log.info("Found existing job {} for project {} with hash {}", 
                    existingJob.get().getId(), projectId, contentHash);
            return existingJob.get();
        }

        // Store the ZIP file so the worker can process it later
        // We store it in the project's upload directory and keep it until processed
        Path stagedZip = storageService.storeZip(file, projectId);
        
        // Create new job
        IndexingJob job = new IndexingJob();
        job.setProjectId(projectId);
        job.setContentHash(contentHash);
        job.setStatus(IndexingJob.QUEUED);
        job.setProgressPercent(0);
        job.setAttempts(0);
        job.setMaxAttempts(properties.getIndexing().getMaxAttempts());
        job.setNextRunAt(LocalDateTime.now());
        
        // Set project status to processing since indexing has started
        project.setStatus("processing");
        projectRepository.save(project);
        
        indexingJobRepository.save(job);
        
        log.info("Created new indexing job {} for project {} (ZIP stored at {}, project status: processing)", 
                job.getId(), projectId, stagedZip);
        return job;
    }

    /**
     * Claims the next runnable job for processing.
     *
     * <p>Kept for compatibility; delegates to {@link IndexingJobStateService}
     * so there is exactly one claim path with attempts+1 and short commits.
     */
    public Optional<IndexingJob> claimNextJob(String workerId, long staleSeconds) {
        return jobStateService.claimNext(workerId, staleSeconds);
    }

    /**
     * Updates job progress during processing.
     *
     * <p>Delegates to the heartbeat so lock renewal stays coupled to progress.
     */
    public void updateProgress(UUID jobId, int progressPercent) {
        jobStateService.heartbeat(jobId, progressPercent);
    }

    /**
     * Marks a job as completed successfully.
     *
     * <p>Guarded: only flips when still processing, so a mid-flight cancel wins.
     */
    public void completeJob(UUID jobId) {
        jobStateService.completeIfProcessing(jobId);
    }

    /**
     * Marks a job as failed and schedules retry if attempts remain.
     */
    public void failJob(UUID jobId, String errorMessage) {
        jobStateService.fail(jobId, errorMessage);
    }

    /**
     * Cancels a job and cleans up partial data.
     *
     * The path project id is authoritative: a job id paired with a different
     * project id reads as NOT_FOUND (never FORBIDDEN), so job ids cannot be
     * used to probe other users' projects.
     *
     * @param projectId Project named in the path
     * @param jobId   Job to cancel
     * @param userId  User requesting cancellation (for ownership check)
     */
    /**
     * Cancels a job: instant status flip, then slow cleanup outside the lock.
     *
     * <p>Kept non-transactional on purpose: {@code cancel} commits its own
     * short transaction, so this facade must NOT wrap it in a longer one.
     * Purge runs afterwards in its own transactions.
     */
    public void cancelJob(UUID projectId, UUID jobId, UUID userId) {
        IndexingJob job = jobStateService.cancel(projectId, jobId, userId);
        jobStateService.purgeAfterCancel(job.getProjectId(), job.getId());
    }

    /**
     * Gets the current status of a job.
     *
     * @param projectId Project named in the path (must own the job)
     * @param jobId   Job to check
     * @param userId  User requesting status (for ownership check)
     * @return Job status DTO
     */
    @Transactional(readOnly = true)
    public IndexingJobResponse getJobStatus(UUID projectId, UUID jobId, UUID userId) {
        IndexingJob job = indexingJobRepository.findById(jobId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found"));

        // Verify project ownership
        Project project = projectRepository.findById(job.getProjectId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));

        if (!project.getId().equals(projectId) || !job.getProjectId().equals(projectId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found in this project");
        }

        if (!project.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }

        return new IndexingJobResponse(
                job.getId(),
                job.getProjectId(),
                job.getStatus(),
                job.getProgressPercent(),
                job.getAttempts(),
                job.getErrorMessage()
        );
    }

    /**
     * Latest job per project for a page of projects (dashboard progress).
     * One query for the whole set — no N+1. Ownership is enforced by the
     * caller's project list, never by the job rows themselves.
     */
    @Transactional(readOnly = true)
    public Map<UUID, IndexingJob> latestJobsFor(Collection<UUID> projectIds) {
        if (projectIds == null || projectIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<UUID, IndexingJob> latest = new HashMap<>();
        for (IndexingJob job : indexingJobRepository.findLatestJobsForProjects(projectIds)) {
            latest.merge(job.getProjectId(), job, (a, b) ->
                    a.getCreatedAt() != null && b.getCreatedAt() != null
                            && b.getCreatedAt().isAfter(a.getCreatedAt()) ? b : a);
        }
        return latest;
    }

    /** Purges any partially indexed data for a project. */
    private void purgePartialIndex(UUID projectId) {
        // Remove any partially created project files, chunks, symbols, references
        codeChunkRepository.deleteByProjectId(projectId);
        codeSymbolRepository.deleteByProjectId(projectId);
        codeReferenceRepository.deleteByProjectId(projectId);
        projectFileRepository.deleteByProjectId(projectId);
        try {
            storageService.deleteProject(projectId);
        } catch (IOException e) {
            log.warn("Failed to delete project storage for {}", projectId, e);
        }
    }

    /** Computes SHA-256 of the uploaded bytes (matches the column comment). */
    private String computeContentHash(org.springframework.web.multipart.MultipartFile file) throws IOException {
        try (var inputStream = file.getInputStream()) {
            byte[] bytes = inputStream.readAllBytes();
            try {
                java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest(bytes);
                StringBuilder hex = new StringBuilder(2 * hash.length);
                for (byte b : hash) {
                    hex.append(String.format("%02x", b));
                }
                return hex.toString();
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IOException("SHA-256 not available", e);
            }
        }
    }
}
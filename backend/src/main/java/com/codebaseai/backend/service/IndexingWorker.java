package com.codebaseai.backend.service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipFile;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;

import com.codebaseai.backend.config.AppProperties;
import com.codebaseai.backend.model.IndexingJob;
import com.codebaseai.backend.model.ProjectFile;
import com.codebaseai.backend.repository.ProjectFileRepository;
import com.codebaseai.backend.service.chunking.Language;
import com.codebaseai.backend.storage.StorageService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Builds a private index for each lease attempt and publishes it atomically. */
@Slf4j
@Component
@RequiredArgsConstructor
public class IndexingWorker {
    private static class InvalidUploadException extends IOException {
        InvalidUploadException(String message) { super(message); }
    }
    private final IndexingJobStateService jobStateService;
    private final ProjectFileRepository projectFileRepository;
    private final ZipExtractionService zipExtractionService;
    private final CodeProcessingService codeProcessingService;
    private final StorageService storageService;
    private final AppProperties properties;
    private final IndexingGenerationCleanup generationCleanup;

    @Scheduled(fixedDelayString = "${app.indexing.workerPollIntervalMs:5000}")
    public void processNextJob() {
        String workerId = "worker-" + UUID.randomUUID();
        long staleSeconds = properties.getIndexing().getStaleJobSeconds();
        jobStateService.requeueStaleJobs(staleSeconds);
        generationCleanup.reapOrphans();
        Optional<IndexingJob> claimed = jobStateService.claimNext(workerId, staleSeconds);
        claimed.ifPresent(job -> {
            // A reclaimed job must never share storage or rows with its old attempt.
            UUID generationId = UUID.randomUUID();
            generationCleanup.stage(job, workerId, generationId, () -> processJob(job, workerId, generationId));
        });
    }

    private void requireLease(UUID jobId, String workerId, int progress) {
        if (!jobStateService.heartbeatOwned(jobId, workerId, progress)) {
            throw new CodeProcessingService.ProcessingCancelledException("Job cancelled or lease lost");
        }
    }

    private void processJob(IndexingJob job, String workerId, UUID generationId) {
        UUID jobId = job.getId();
        UUID projectId = job.getProjectId();
        Path stagedZip = null;
        Path extractionDirectory = null;
        try {
            requireLease(jobId, workerId, 5);
            if (job.getUploadId() == null) {
                jobStateService.failOwned(projectId, jobId, workerId,
                        "Upload must be submitted again after the indexing upgrade", true);
                return;
            }
            stagedZip = storageService.materializeJobUpload(projectId, job.getUploadId());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(stagedZip)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            if (!HexFormat.of().formatHex(digest.digest()).equals(job.getContentHash())) {
                throw new InvalidUploadException("Uploaded archive checksum does not match its job");
            }
            try (ZipFile archive = new ZipFile(stagedZip.toFile())) {
                if (archive.size() == 0) throw new InvalidUploadException("Uploaded ZIP contains no files");
            }
            requireLease(jobId, workerId, 15);
            extractionDirectory = storageService.generationExtractionDirectory(projectId, generationId);
            List<ZipExtractionService.ExtractedFile> extracted =
                    zipExtractionService.extractZip(stagedZip, extractionDirectory);
            List<ZipExtractionService.ExtractedFile> textFiles = new ArrayList<>();
            long totalSize = 0;
            for (ZipExtractionService.ExtractedFile file : extracted) {
                byte[] content = Files.readAllBytes(file.getFilePath());
                if (!isText(content)) {
                    Files.deleteIfExists(file.getFilePath());
                    log.info("Skipping non-UTF-8 or binary file {}", file.getPath());
                    continue;
                }
                ProjectFile projectFile = new ProjectFile();
                projectFile.setProjectId(projectId);
                projectFile.setGenerationId(generationId);
                projectFile.setPath(file.getPath());
                projectFile.setSizeBytes((int) file.getSize());
                projectFile.setLanguage(Language.fromPath(file.getPath()).getId());
                projectFile.setContentHash(DigestUtils.md5DigestAsHex(content));
                projectFileRepository.save(projectFile);
                textFiles.add(file);
                totalSize += file.getSize();
            }
            if (textFiles.isEmpty()) throw new InvalidUploadException("Uploaded ZIP contains no indexable text files");
            requireLease(jobId, workerId, 35);
            storageService.persistGeneration(projectId, generationId, extractionDirectory, textFiles);
            requireLease(jobId, workerId, 45);
            CodeProcessingService.ProcessingResult result =
                    codeProcessingService.processProject(projectId, generationId,
                            progress -> requireLease(jobId, workerId,
                                    45 + (int) Math.round(progress * 0.40)));
            if (result.hasFailures()) {
                throw new IOException("Failed to index file(s): " + String.join(", ", result.failedFiles()));
            }
            if (result.totalChunks() == 0) throw new InvalidUploadException("Uploaded ZIP contains no searchable code");
            requireLease(jobId, workerId, 90);
            boolean published = jobStateService.publish(projectId, jobId, workerId, generationId,
                    textFiles.size(), totalSize);
            if (published) {
                try {
                    storageService.deleteJobUpload(projectId, job.getUploadId());
                } catch (Exception e) {
                    log.warn("Failed to remove completed upload {}", job.getUploadId(), e);
                }
            }
        } catch (CodeProcessingService.ProcessingCancelledException e) {
            log.info("Stopped job {}: {}", jobId, e.getMessage());
        } catch (Exception e) {
            log.error("Job {} failed: {}", jobId, e.getMessage(), e);
            boolean permanent = e instanceof InvalidUploadException || e instanceof java.util.zip.ZipException
                    || e instanceof ZipLimitExceededException;
            jobStateService.failOwned(projectId, jobId, workerId, "Failed to process upload: " + e.getMessage(), permanent);
        } finally {
            if (stagedZip != null) storageService.releaseJobUpload(stagedZip);
            if (extractionDirectory != null) storageService.discardGenerationExtraction(extractionDirectory);
        }
    }

    private boolean isText(byte[] content) {
        for (byte value : content) {
            if (value == 0) return false;
        }
        try {
            StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(content));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }
}

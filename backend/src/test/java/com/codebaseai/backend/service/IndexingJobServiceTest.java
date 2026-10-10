package com.codebaseai.backend.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.codebaseai.backend.config.AppProperties;
import com.codebaseai.backend.config.AppProperties.IndexingConfig;
import com.codebaseai.backend.model.IndexingJob;
import com.codebaseai.backend.model.Project;
import com.codebaseai.backend.model.User;
import com.codebaseai.backend.repository.CodeChunkRepository;
import com.codebaseai.backend.repository.CodeReferenceRepository;
import com.codebaseai.backend.repository.CodeSymbolRepository;
import com.codebaseai.backend.repository.IndexingJobRepository;
import com.codebaseai.backend.repository.ProjectFileRepository;
import com.codebaseai.backend.repository.ProjectRepository;
import com.codebaseai.backend.repository.UserRepository;
import com.codebaseai.backend.storage.StorageService;

/**
 * Unit tests for the indexing job queue service logic.
 */
@ExtendWith(MockitoExtension.class)
class IndexingJobServiceTest {

    @Mock private ProjectRepository projectRepository;
    @Mock private ProjectFileRepository projectFileRepository;
    @Mock private CodeChunkRepository codeChunkRepository;
    @Mock private CodeSymbolRepository codeSymbolRepository;
    @Mock private CodeReferenceRepository codeReferenceRepository;
    @Mock private IndexingJobRepository indexingJobRepository;
    @Mock private IndexingJobStateService jobStateService;
    @Mock private ZipExtractionService zipExtractionService;
    @Mock private CodeProcessingService codeProcessingService;
    @Mock private StorageService storageService;
    @Mock private UserRepository userRepository;

    private IndexingJobService service;
    private AppProperties properties;

    private User testUser;
    private Project testProject;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setId(UUID.randomUUID());
        testUser.setEmail("test@example.com");

        testProject = new Project();
        testProject.setId(UUID.randomUUID());
        testProject.setUserId(testUser.getId());
        testProject.setName("test-project");
        testProject.setStatus("pending");

        properties = new AppProperties();
        properties.getIndexing().setMaxAttempts(3);

        service = new IndexingJobService(
                projectRepository, projectFileRepository, codeChunkRepository,
                codeSymbolRepository, codeReferenceRepository,
                indexingJobRepository, zipExtractionService, codeProcessingService,
                storageService, properties, jobStateService);
    }

    private MockMultipartFile createMockZip() throws IOException {
        return new MockMultipartFile(
                "file", "test.zip", "application/zip",
                "fake-zip-content".getBytes());
    }

    @Test
    void idempotentUploadReturnsExistingJob() throws IOException {
        MockMultipartFile file = createMockZip();

        IndexingJob existing = new IndexingJob();
        existing.setId(UUID.randomUUID());
        existing.setProjectId(testProject.getId());
        existing.setStatus(IndexingJob.QUEUED);
        existing.setProgressPercent(0);
        existing.setAttempts(0);

        when(projectRepository.findById(testProject.getId())).thenReturn(Optional.of(testProject));
        when(indexingJobRepository.findFirstByProjectIdAndContentHashAndStatusInOrderByCreatedAtDesc(
                any(UUID.class), anyString(), any(List.class)))
                .thenReturn(Optional.of(existing));

        IndexingJob result = service.createOrGetJob(testProject.getId(), testUser.getId(), file);

        assertThat(result.getId()).isEqualTo(existing.getId());
        verify(projectRepository, never()).save(any());
        verify(indexingJobRepository, never()).save(any());
    }

    @Test
    void newUniqueContentCreatesNewJob() throws IOException {
        MockMultipartFile file = createMockZip();

        when(projectRepository.findById(testProject.getId())).thenReturn(Optional.of(testProject));
        when(indexingJobRepository.findFirstByProjectIdAndContentHashAndStatusInOrderByCreatedAtDesc(
                any(UUID.class), anyString(), any(List.class)))
                .thenReturn(Optional.empty());

        IndexingJob result = service.createOrGetJob(testProject.getId(), testUser.getId(), file);

        assertThat(result.getId()).isNotNull();
        assertThat(result.getStatus()).isEqualTo(IndexingJob.QUEUED);
        assertThat(result.getProgressPercent()).isEqualTo(0);
        verify(indexingJobRepository).save(result);
    }

    @Test
    void cancelJobSetsCancelledStatus() {
        UUID jobId = UUID.randomUUID();
        IndexingJob job = new IndexingJob();
        job.setId(jobId);
        job.setProjectId(testProject.getId());
        job.setStatus(IndexingJob.QUEUED);
        job.setAttempts(0);

        // cancelJob is now a non-transactional facade: it delegates the status
        // flip to the state service, then purges the partial index afterwards.
        when(jobStateService.cancel(testProject.getId(), jobId, testUser.getId())).thenReturn(job);

        service.cancelJob(testProject.getId(), jobId, testUser.getId());

        verify(jobStateService).cancel(testProject.getId(), jobId, testUser.getId());
        verify(jobStateService).purgeAfterCancel(testProject.getId(), jobId);
    }

    @Test
    void completeJobSetsReadyStatus() {
        UUID jobId = UUID.randomUUID();

        // completeJob now delegates to the guarded completeIfProcessing on the
        // state service, which flips the row to ready only while still processing.
        when(jobStateService.completeIfProcessing(jobId)).thenReturn(true);

        service.completeJob(jobId);

        verify(jobStateService).completeIfProcessing(jobId);
    }

    @Test
    void failJobRetriesBeforeMaxAttempts() {
        UUID jobId = UUID.randomUUID();

        // failJob now just delegates to the state service, which owns the
        // backoff/retry decision. Attempts are incremented at claim time, not
        // here, so the service only forwards the failure.
        service.failJob(jobId, "Test failure");

        verify(jobStateService).fail(jobId, "Test failure");
    }

    @Test
    void failJobReachesMaxAttemptsSetsError() {
        UUID jobId = UUID.randomUUID();

        // Same delegation: the max-attempts -> error transition (and the purge
        // of the partial index) now live in the state service's fail().
        service.failJob(jobId, "Final failure");

        verify(jobStateService).fail(jobId, "Final failure");
    }

    @Test
    void getJobStatusReturnsJobResponse() {
        UUID jobId = UUID.randomUUID();
        IndexingJob job = new IndexingJob();
        job.setId(jobId);
        job.setProjectId(testProject.getId());
        job.setStatus(IndexingJob.QUEUED);
        job.setProgressPercent(0);
        job.setAttempts(0);

        when(indexingJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(projectRepository.findById(testProject.getId())).thenReturn(Optional.of(testProject));

        var response = service.getJobStatus(testProject.getId(), jobId, testUser.getId());

        assertThat(response.id()).isEqualTo(jobId);
        assertThat(response.status()).isEqualTo(IndexingJob.QUEUED);
        assertThat(response.progressPercent()).isEqualTo(0);
    }

    @Test
    void cancelJobRejectsMismatchedProjectId() {
        UUID jobId = UUID.randomUUID();

        // The project/job mismatch (and ownership) check now lives inside the
        // state service's cancel(); the facade just propagates it and must not
        // purge anything when the cancel is rejected.
        when(jobStateService.cancel(any(UUID.class), eq(jobId), any(UUID.class)))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found in this project"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                service.cancelJob(UUID.randomUUID(), jobId, testUser.getId()));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(jobStateService, never()).purgeAfterCancel(any(UUID.class), any(UUID.class));
    }

    @Test
    void getJobStatusRejectsMismatchedProjectId() {
        UUID jobId = UUID.randomUUID();
        IndexingJob job = new IndexingJob();
        job.setId(jobId);
        job.setProjectId(testProject.getId());
        job.setStatus(IndexingJob.QUEUED);

        when(indexingJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(projectRepository.findById(testProject.getId())).thenReturn(Optional.of(testProject));

        assertThrows(ResponseStatusException.class, () ->
                service.getJobStatus(UUID.randomUUID(), jobId, testUser.getId()));
    }

    @Test
    void createOrGetJobVerifiesOwnership() throws IOException {
        MockMultipartFile file = createMockZip();

        User otherUser = new User();
        otherUser.setId(UUID.randomUUID());
        otherUser.setEmail("other@example.com");

        Project otherProject = new Project();
        otherProject.setId(testProject.getId());
        otherProject.setUserId(otherUser.getId());
        otherProject.setName("other-project");

        when(projectRepository.findById(testProject.getId())).thenReturn(Optional.of(otherProject));

        assertThrows(ResponseStatusException.class, () ->
                service.createOrGetJob(testProject.getId(), testUser.getId(), file));
    }

    @Test
    void createOrGetJobRejectsNonZipFile() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.txt", "text/plain", "hello".getBytes());

        when(projectRepository.findById(testProject.getId())).thenReturn(Optional.of(testProject));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                service.createOrGetJob(testProject.getId(), testUser.getId(), file));

        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}

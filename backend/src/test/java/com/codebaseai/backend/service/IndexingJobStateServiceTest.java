package com.codebaseai.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.codebaseai.backend.model.IndexingJob;
import com.codebaseai.backend.model.Project;
import com.codebaseai.backend.repository.IndexingJobRepository;
import com.codebaseai.backend.repository.ProjectFileRepository;
import com.codebaseai.backend.repository.ProjectRepository;
import com.codebaseai.backend.storage.StorageService;

@ExtendWith(MockitoExtension.class)
class IndexingJobStateServiceTest {
    @Mock private IndexingJobRepository jobs;
    @Mock private ProjectRepository projects;
    @Mock private ProjectFileRepository files;
    @Mock private StorageService storage;
    private IndexingJobStateService service;

    @BeforeEach
    void setUp() {
        service = new IndexingJobStateService(jobs, projects, files, storage);
    }

    @Test
    void exhaustedStaleLeaseFailsWithoutReplacingPublishedIndex() {
        UUID projectId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID published = UUID.randomUUID();
        Project project = new Project();
        project.setId(projectId);
        project.setActiveGenerationId(published);
        IndexingJob job = staleJob(projectId, jobId, 3);
        when(jobs.findStaleJobIds(any())).thenReturn(Collections.singletonList(new Object[]{projectId, jobId}));
        when(projects.findByIdForUpdate(projectId)).thenReturn(Optional.of(project));
        when(jobs.findByIdForUpdate(jobId)).thenReturn(Optional.of(job));

        assertThat(service.requeueStaleJobs(60)).isEqualTo(1);
        assertThat(job.getStatus()).isEqualTo(IndexingJob.ERROR);
        assertThat(job.getLockedBy()).isNull();
        assertThat(project.getStatus()).isEqualTo("ready");
        assertThat(project.getActiveGenerationId()).isEqualTo(published);
        verify(jobs).save(job);
    }

    @Test
    void staleLeaseRetriesWithinAttemptLimit() {
        UUID projectId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        Project project = new Project();
        project.setId(projectId);
        IndexingJob job = staleJob(projectId, jobId, 1);
        when(jobs.findStaleJobIds(any())).thenReturn(Collections.singletonList(new Object[]{projectId, jobId}));
        when(projects.findByIdForUpdate(projectId)).thenReturn(Optional.of(project));
        when(jobs.findByIdForUpdate(jobId)).thenReturn(Optional.of(job));

        assertThat(service.requeueStaleJobs(60)).isEqualTo(1);
        assertThat(job.getStatus()).isEqualTo(IndexingJob.QUEUED);
        assertThat(job.getNextRunAt()).isNotNull();
        verify(projects, never()).save(any());
    }

    @Test
    void renewedLeaseIsNotReclaimedAfterCandidateRead() {
        UUID projectId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        IndexingJob job = staleJob(projectId, jobId, 1);
        job.setLockedAt(LocalDateTime.now());
        when(jobs.findStaleJobIds(any())).thenReturn(Collections.singletonList(new Object[]{projectId, jobId}));
        when(projects.findByIdForUpdate(projectId)).thenReturn(Optional.of(new Project()));
        when(jobs.findByIdForUpdate(jobId)).thenReturn(Optional.of(job));

        assertThat(service.requeueStaleJobs(60)).isZero();
        assertThat(job.getStatus()).isEqualTo(IndexingJob.PROCESSING);
        verify(jobs, never()).save(any());
    }

    @Test
    void cleanupKeepsGenerationThatWasPublishedBeforeProjectLock() {
        UUID projectId = UUID.randomUUID();
        UUID generationId = UUID.randomUUID();
        when(projects.findByIdForUpdate(projectId)).thenReturn(Optional.of(new Project()));
        when(projects.isGenerationPublished(projectId, generationId)).thenReturn(true);

        assertThat(service.discardUnpublishedGeneration(projectId, generationId)).isFalse();
        verify(files, never()).deleteByProjectIdAndGenerationId(eq(projectId), eq(generationId));
    }

    private static IndexingJob staleJob(UUID projectId, UUID jobId, int attempts) {
        IndexingJob job = new IndexingJob();
        job.setId(jobId);
        job.setProjectId(projectId);
        job.setStatus(IndexingJob.PROCESSING);
        job.setAttempts(attempts);
        job.setMaxAttempts(3);
        job.setLockedBy("expired-worker");
        job.setLockedAt(LocalDateTime.now().minusMinutes(5));
        return job;
    }
}

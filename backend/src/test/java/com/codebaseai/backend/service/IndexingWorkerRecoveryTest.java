package com.codebaseai.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.codebaseai.backend.storage.StorageService;

@EnabledIfEnvironmentVariable(named = "INDEXING_TEST_DB_URL", matches = ".+")
@SpringBootTest(properties = {
        "spring.datasource.username=postgres",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "app.jwt.secret=only-for-indexing-integration-tests-0123456789",
        "app.ai-service.internal-token=only-for-indexing-integration-tests-0123456789"
})
class IndexingWorkerRecoveryTest {
    @TempDir static Path uploadRoot;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private IndexingJobStateService jobs;
    @Autowired private IndexingGenerationCleanup cleanup;
    @Autowired private ProjectService projects;
    @MockitoSpyBean private StorageService storage;
    @MockitoBean private IndexingWorker scheduledWorker;
    private final UUID user = UUID.randomUUID();

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> System.getenv("INDEXING_TEST_DB_URL"));
        properties.add("app.upload.directory", () -> uploadRoot.toString());
    }

    @AfterEach
    void deleteFixture() {
        jdbc.update("DELETE FROM users WHERE id = ?", user);
    }

    @Test
    void reclaimedLeaseCannotPublishAndItsStagingIsReapedAfterWorkerDisconnects() throws Exception {
        UUID project = UUID.randomUUID();
        UUID job = UUID.randomUUID();
        UUID published = UUID.randomUUID();
        UUID abandoned = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id, email, password_hash) VALUES (?, ?, 'unused')", user, user + "@example.test");
        jdbc.update("INSERT INTO projects(id, user_id, name, active_generation_id) VALUES (?, ?, 'example', ?)",
                project, user, published);
        jdbc.update("INSERT INTO published_generations(project_id, generation_id) VALUES (?, ?)", project, published);
        jdbc.update("INSERT INTO indexing_jobs(id, project_id, content_hash) VALUES (?, ?, 'example')", job, project);
        assertThat(jobs.claimNext("worker-a", 60)).hasValueSatisfying(claim -> assertThat(claim.getId()).isEqualTo(job));
        jdbc.update("INSERT INTO staging_generations(generation_id, job_id, worker_id) VALUES (?, ?, 'worker-a'), (?, ?, 'worker-a')",
                abandoned, job, published, job);
        jdbc.update("INSERT INTO files(project_id, generation_id, path, content_hash) VALUES (?, ?, 'partial.txt', 'x')",
                project, abandoned);

        Path staging = storage.generationExtractionDirectory(project, abandoned);
        Files.writeString(staging.resolve("partial.txt"), "unfinished");
        Path retained = storage.generationExtractionDirectory(project, published);
        Files.writeString(retained.resolve("source.txt"), "published");

        try (var workerSession = DriverManager.getConnection(System.getenv("INDEXING_TEST_DB_URL"), "postgres", "");
             var lock = workerSession.prepareStatement("SELECT pg_advisory_lock(?)")) {
            lock.setLong(1, abandoned.getMostSignificantBits() ^ abandoned.getLeastSignificantBits());
            lock.execute();
            cleanup.reapOrphans();
            assertThat(staging).exists();

            jdbc.update("UPDATE indexing_jobs SET locked_at = NOW() - INTERVAL '5 minutes' WHERE id = ?", job);
            assertThat(jobs.requeueStaleJobs(60)).isEqualTo(1);
            assertThat(jobs.claimNext("worker-b", 60)).hasValueSatisfying(claim -> {
                assertThat(claim.getId()).isEqualTo(job);
                assertThat(claim.getAttempts()).isEqualTo(2);
            });
            assertThat(jobs.heartbeatOwned(job, "worker-a", 90)).isFalse();
            assertThat(jobs.publish(project, job, "worker-a", abandoned, 1, 10)).isFalse();
            cleanup.reapOrphans();
            assertThat(staging).exists();
            assertThat(retained.resolve("source.txt")).hasContent("published");
        }
        jdbc.update("UPDATE staging_generations SET cleanup_after = NOW() WHERE generation_id = ?", abandoned);
        cleanup.reapOrphans();

        assertThat(staging).doesNotExist();
        assertThat(retained.resolve("source.txt")).hasContent("published");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM files WHERE generation_id = ?", Integer.class, abandoned)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staging_generations WHERE job_id = ?", Integer.class, job)).isZero();
    }

    @Test
    void deletedProjectGenerationsAreReapedWhenImmediateStorageDeletionFails() throws Exception {
        UUID project = UUID.randomUUID();
        UUID job = UUID.randomUUID();
        UUID abandoned = UUID.randomUUID();
        UUID previous = UUID.randomUUID();
        UUID active = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id, email, password_hash) VALUES (?, ?, 'unused')", user, user + "@example.test");
        jdbc.update("INSERT INTO projects(id, user_id, name, active_generation_id) VALUES (?, ?, 'example', ?)",
                project, user, active);
        jdbc.update("INSERT INTO indexing_jobs(id, project_id, content_hash) VALUES (?, ?, 'example')", job, project);
        assertThat(jobs.claimNext("worker-a", 60)).isPresent();
        jdbc.update("INSERT INTO staging_generations(generation_id, job_id, worker_id) VALUES (?, ?, 'worker-a')",
                abandoned, job);
        jdbc.update("INSERT INTO published_generations(project_id, generation_id) VALUES (?, ?), (?, ?)",
                project, previous, project, active);

        Path abandonedPath = storage.generationExtractionDirectory(project, abandoned);
        Path previousPath = storage.generationExtractionDirectory(project, previous);
        Path activePath = storage.generationExtractionDirectory(project, active);
        cleanup.reapOrphans();
        assertThat(abandonedPath).exists();
        assertThat(previousPath).exists();
        assertThat(activePath).exists();

        doThrow(new IOException("storage unavailable")).when(storage).deleteProjectGenerations(project);
        doThrow(new IOException("storage still unavailable")).doCallRealMethod()
                .when(storage).deleteGeneration(project, previous);
        projects.deleteProject(project, user);
        assertThat(abandonedPath).exists();
        assertThat(previousPath).exists();
        assertThat(activePath).exists();

        cleanup.reapOrphans();

        assertThat(abandonedPath).doesNotExist();
        assertThat(previousPath).exists();
        assertThat(activePath).doesNotExist();
        jdbc.update("UPDATE staging_generations SET cleanup_after = NOW() WHERE generation_id = ?", previous);
        cleanup.reapOrphans();
        assertThat(previousPath).doesNotExist();
    }
}

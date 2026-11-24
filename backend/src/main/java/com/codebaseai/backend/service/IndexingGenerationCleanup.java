package com.codebaseai.backend.service;

import java.util.UUID;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.codebaseai.backend.model.IndexingJob;
import com.codebaseai.backend.storage.StorageService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
class IndexingGenerationCleanup {
    private final JdbcTemplate jdbc;
    private final IndexingJobStateService jobs;
    private final StorageService storage;

    private record Generation(UUID projectId, UUID generationId) {}

    void stage(IndexingJob job, String workerId, UUID generationId, Runnable work) {
        boolean acquired = withGenerationLock(generationId, () -> {
            jdbc.update("INSERT INTO staging_generations(generation_id, job_id, worker_id) VALUES (?, ?, ?)",
                    generationId, job.getId(), workerId);
            try {
                work.run();
            } finally {
                reclaim(new Generation(job.getProjectId(), generationId));
            }
        });
        if (!acquired) {
            throw new IllegalStateException("Could not acquire a lock for the new indexing generation");
        }
    }

    void queuePublishedGenerationsForDeletion(UUID projectId) {
        jdbc.update("""
                INSERT INTO staging_generations(generation_id, project_id)
                SELECT generation_id, project_id FROM published_generations WHERE project_id = ?
                ON CONFLICT (generation_id) DO UPDATE
                SET project_id = EXCLUDED.project_id, job_id = NULL, worker_id = NULL
                """, projectId);
    }

    void reapOrphans() {
        var candidates = jdbc.query("""
                SELECT COALESCE(j.project_id, s.project_id) AS project_id, s.generation_id
                FROM staging_generations s LEFT JOIN indexing_jobs j ON j.id = s.job_id
                WHERE s.cleanup_after <= NOW()
                  AND (j.id IS NULL OR j.status <> 'processing' OR j.locked_by IS DISTINCT FROM s.worker_id)
                ORDER BY s.cleanup_after, s.generation_id
                LIMIT 8
                """, (row, ignored) -> new Generation(row.getObject(1, UUID.class), row.getObject(2, UUID.class)));
        for (Generation candidate : candidates) {
            if (!withGenerationLock(candidate.generationId(), () -> reclaim(candidate))) {
                defer(candidate.generationId());
            }
        }
    }

    private void reclaim(Generation candidate) {
        UUID generation = candidate.generationId();
        try {
            if (!jobs.discardUnpublishedGeneration(candidate.projectId(), generation)) {
                // Project deletion can replace the staging row while publication is being checked.
                jdbc.update("DELETE FROM staging_generations WHERE generation_id = ? AND worker_id IS NOT NULL",
                        generation);
                return;
            }
            storage.deleteGeneration(candidate.projectId(), generation);
            jdbc.update("DELETE FROM staging_generations WHERE generation_id = ?", generation);
        } catch (Exception e) {
            log.warn("Could not remove staging generation {}", generation, e);
            defer(generation);
        }
    }

    private void defer(UUID generationId) {
        jdbc.update("UPDATE staging_generations SET cleanup_after = NOW() + INTERVAL '1 minute' WHERE generation_id = ?",
                generationId);
    }

    private boolean withGenerationLock(UUID generationId, Runnable work) {
        // A stale lease can be reclaimed while an old worker is still writing. Keep the
        // filesystem fence on a session, so those writes never hold a DB transaction open.
        long key = generationId.getMostSignificantBits() ^ generationId.getLeastSignificantBits();
        return Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) connection -> {
            try (var lock = connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                lock.setLong(1, key);
                try (var result = lock.executeQuery()) {
                    result.next();
                    if (!result.getBoolean(1)) return false;
                }
            }
            try {
                work.run();
                return true;
            } finally {
                try (var unlock = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                    unlock.setLong(1, key);
                    unlock.execute();
                }
            }
        }));
    }
}

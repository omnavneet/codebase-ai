package com.codebaseai.backend.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.codebaseai.backend.model.IndexingJob;

/**
 * Data access for the indexing queue. The claim query is the interesting one:
 * it locks a single row with {@code FOR UPDATE SKIP LOCKED}, so two workers
 * polling concurrently never grab the same job.
 */
public interface IndexingJobRepository extends JpaRepository<IndexingJob, UUID> {

    /**
     * Claims the next runnable job, if any.
     *
     * <p>A job is runnable when it is {@code queued} and due, or when it is
     * {@code processing} but its lease has gone stale (crash recovery). The
     * {@code FOR UPDATE SKIP LOCKED} makes the pick atomic across workers: a
     * row another transaction already holds is skipped rather than blocking, so
     * every worker gets a different job (or none). Must be called inside a
     * transaction so the row lock is held until the caller commits.
     */
    @Query(value = """
            SELECT * FROM indexing_jobs
            WHERE (status = 'queued' AND next_run_at <= now())
               OR (status = 'processing' AND locked_at < now() - (:staleSeconds * interval '1 second'))
            ORDER BY next_run_at
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<IndexingJob> claimNext(@Param("staleSeconds") long staleSeconds);

    /** Idempotency lookup: an existing queued/processing/ready job for this archive. */
    Optional<IndexingJob> findFirstByProjectIdAndContentHashAndStatusInOrderByCreatedAtDesc(
            UUID projectId, String contentHash, Collection<String> statuses);

    /** The job that reflects a project's current indexing state (latest wins). */
    Optional<IndexingJob> findFirstByProjectIdOrderByCreatedAtDesc(UUID projectId);

    /**
     * Latest job per project for a whole page of projects, in ONE query so the
     * project list can carry live progress without an N+1.
     */
    @Query("""
            SELECT j FROM IndexingJob j
            WHERE j.projectId IN :projectIds
              AND j.createdAt = (
                  SELECT MAX(j2.createdAt) FROM IndexingJob j2 WHERE j2.projectId = j.projectId
              )
            """)
    List<IndexingJob> findLatestJobsForProjects(@Param("projectIds") Collection<UUID> projectIds);

    /** Jobs a user may still cancel (anything not yet finished). */
    List<IndexingJob> findByProjectIdAndStatusIn(UUID projectId, Collection<String> statuses);

    /**
     * Finishes a job only when it is still processing.
     *
     * <p>The {@code WHERE status='processing'} guard is what stops a worker
     * that just finished from overwriting a cancellation (or a retry reset)
     * that landed mid-flight: in that case zero rows change and the caller
     * knows to stop. Returns the number of rows updated (0 or 1).
     */
    @org.springframework.data.jpa.repository.Modifying
    @Transactional
    @Query(value = """
            UPDATE indexing_jobs
               SET status = 'ready',
                   progress_percent = 100,
                   error_message = NULL,
                   locked_by = NULL,
                   locked_at = NULL,
                   updated_at = :now
             WHERE id = :jobId AND status = 'processing'
            """, nativeQuery = true)
    int markReadyIfProcessing(@Param("jobId") UUID jobId, @Param("now") LocalDateTime now);

    /**
     * Fails a job permanently only when it is still processing (same guard as
     * {@link #markReadyIfProcessing}: a cancelled job keeps its cancellation).
     */
    @org.springframework.data.jpa.repository.Modifying
    @Transactional
    @Query(value = """
            UPDATE indexing_jobs
               SET status = 'error',
                   error_message = :errorMessage,
                   locked_by = NULL,
                   locked_at = NULL,
                   updated_at = :now
             WHERE id = :jobId AND status = 'processing'
            """, nativeQuery = true)
    int markErrorIfProcessing(@Param("jobId") UUID jobId,
                              @Param("errorMessage") String errorMessage,
                              @Param("now") LocalDateTime now);

    /**
     * Crash recovery: flips every {@code processing} job whose lease
     * ({@code locked_at}) has gone stale back to {@code queued}, so it can be
     * claimed again. Attempts are NOT touched here — they are incremented once,
     * at claim time. A healthy job keeps a fresh {@code locked_at} via the
     * per-batch heartbeat, so it never matches this update.
     *
     * @return number of jobs re-queued.
     */
    @org.springframework.data.jpa.repository.Modifying
    @Transactional
    @Query(value = """
            UPDATE indexing_jobs
               SET status = 'queued',
                   locked_by = NULL,
                   locked_at = NULL,
                   next_run_at = :now,
                   updated_at = :now
              WHERE status = 'processing'
                AND locked_at < now() - (:staleSeconds * interval '1 second')
            """, nativeQuery = true)
    int requeueStale(@Param("staleSeconds") long staleSeconds, @Param("now") LocalDateTime now);
}

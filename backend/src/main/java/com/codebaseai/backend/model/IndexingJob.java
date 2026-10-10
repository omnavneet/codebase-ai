package com.codebaseai.backend.model;

import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * One background indexing run for a project (see V12__create_indexing_jobs.sql).
 *
 * <p>Mirrors the table exactly, because Hibernate runs with
 * {@code ddl-auto=validate}: a mapped column that does not exist in the database
 * fails startup. Status is a plain String (not an enum) to match the
 * {@code projects.status} convention and to keep the vocabulary open.
 */
@Entity
@Table(name = "indexing_jobs")
@Data
public class IndexingJob {

    /** Terminal/running states a job moves through. */
    public static final String QUEUED = "queued";
    public static final String PROCESSING = "processing";
    public static final String READY = "ready";
    public static final String ERROR = "error";
    public static final String CANCELLED = "cancelled";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(nullable = false, length = 20)
    private String status = QUEUED;

    @Column(name = "progress_percent", nullable = false)
    private Integer progressPercent = 0;

    @Column(nullable = false)
    private Integer attempts = 0;

    @Column(name = "max_attempts", nullable = false)
    private Integer maxAttempts = 3;

    @Column(name = "next_run_at", nullable = false)
    private LocalDateTime nextRunAt;

    @Column(name = "locked_by", length = 100)
    private String lockedBy;

    @Column(name = "locked_at")
    private LocalDateTime lockedAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (nextRunAt == null) {
            nextRunAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

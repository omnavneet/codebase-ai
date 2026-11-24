package com.codebaseai.backend.model;

import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;

@Entity
@Table(name = "files")
@Data
public class ProjectFile {
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    
    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "generation_id")
    private UUID generationId;
    
    @Column(nullable = false)
    private String path;
    
    @Column(name = "content_hash", nullable = false)
    private String contentHash;
    
    @Column(name = "size_bytes")
    private Integer sizeBytes;

    /** Detected language id (see chunking.Language); empty for unknown types. */
    @Column(name = "language", length = 32)
    private String language;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}

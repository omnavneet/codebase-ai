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
@Table(name = "chunks")
@Data
public class CodeChunk {
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    
    @Column(name = "file_id", nullable = false)
    private UUID fileId;
    
    @Column(name = "project_id", nullable = false)
    private UUID projectId;
    
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;
    
    @Column(name = "token_count")
    private Integer tokenCount;
    
    @Column(name = "embedding", columnDefinition = "vector(384)")
    private double[] embedding;
    
    @Column(name = "start_line")
    private Integer startLine;
    
    @Column(name = "end_line")
    private Integer endLine;
    
    @Column(name = "chunk_type")
    private String chunkType = "code";
    
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
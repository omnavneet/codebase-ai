package com.codebaseai.backend.dto;

import java.time.LocalDateTime;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ProjectResponse {
    private UUID id;
    private String name;
    private String status;
    private Integer fileCount;
    private Long totalSizeBytes;
    private String errorMessage;
    private LocalDateTime createdAt;
    /** Latest indexing job for this project, when one exists (additive). */
    private UUID latestJobId;
    /** Progress of that job, percent 0-100; null when no job has run yet. */
    private Integer indexingProgressPercent;
}
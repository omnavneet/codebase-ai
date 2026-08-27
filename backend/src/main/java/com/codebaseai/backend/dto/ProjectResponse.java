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
    private LocalDateTime createdAt;
}
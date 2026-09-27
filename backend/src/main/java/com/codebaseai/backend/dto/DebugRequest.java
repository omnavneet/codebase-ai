package com.codebaseai.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class DebugRequest {
    @NotBlank
    @Size(max = 8000)
    private String issueDescription;

    @Size(max = 30000)
    private String stackTrace;

    @Size(max = 1000)
    private String filePath;
}

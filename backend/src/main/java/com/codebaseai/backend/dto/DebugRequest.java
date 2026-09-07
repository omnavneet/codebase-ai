package com.codebaseai.backend.dto;

import lombok.Data;

@Data
public class DebugRequest {
    private String issueDescription;
    private String stackTrace;
    private String filePath;
}

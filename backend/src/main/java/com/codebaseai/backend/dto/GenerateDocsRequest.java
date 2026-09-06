package com.codebaseai.backend.dto;

import lombok.Data;

@Data
public class GenerateDocsRequest {
    private String filePath;
    private String symbol;
}

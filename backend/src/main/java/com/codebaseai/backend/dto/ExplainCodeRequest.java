package com.codebaseai.backend.dto;

import lombok.Data;

@Data
public class ExplainCodeRequest {
    private String filePath;
    private String symbol;
}

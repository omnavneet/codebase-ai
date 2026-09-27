package com.codebaseai.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ExplainCodeRequest {
    @NotBlank
    @Size(max = 1000)
    private String filePath;

    @Size(max = 255)
    private String symbol;
}

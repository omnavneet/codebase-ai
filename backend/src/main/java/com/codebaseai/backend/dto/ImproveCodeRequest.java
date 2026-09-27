package com.codebaseai.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ImproveCodeRequest {
    @NotBlank
    @Size(max = 1000)
    private String filePath;
}

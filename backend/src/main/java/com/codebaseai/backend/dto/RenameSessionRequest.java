package com.codebaseai.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RenameSessionRequest {
    @NotBlank
    @Size(max = 255)
    private String title;
}

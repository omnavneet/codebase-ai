package com.codebaseai.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AgentInvestigateRequest {
    @NotBlank
    @Size(max = 8000)
    private String question;
}

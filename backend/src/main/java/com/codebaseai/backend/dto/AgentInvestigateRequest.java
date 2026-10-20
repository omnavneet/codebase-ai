package com.codebaseai.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import java.util.UUID;

@Data
public class AgentInvestigateRequest {
    @NotBlank
    @Size(max = 8000)
    private String question;

    /**
     * The conversation this run belongs to. When absent the run is still
     * persisted on success — to the project's most recent conversation.
     */
    private UUID conversationId;
}

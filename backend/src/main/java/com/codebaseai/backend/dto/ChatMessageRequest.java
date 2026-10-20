package com.codebaseai.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import java.util.UUID;

@Data
public class ChatMessageRequest {
    @NotBlank
    @Size(max = 8000)
    private String content;

    /**
     * Re-ask of the trailing question: the backend skips persisting the user
     * message again and replaces the previous answer instead of appending a
     * new one, so the stored history never shows the question (or the answer)
     * twice.
     */
    private boolean regenerate;

    /**
     * The conversation this message belongs to. When absent the chat service
     * uses (or creates) the project's most recent conversation, so clients
     * that do not send a conversation keep working.
     */
    private UUID conversationId;
}
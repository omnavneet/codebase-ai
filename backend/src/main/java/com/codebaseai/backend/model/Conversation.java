package com.codebaseai.backend.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "conversations")
@Data
public class Conversation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(nullable = false)
    private String title = "New Chat";

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

        @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** Condensed summary of older messages (kept out of the live history window). */
    @Column(name = "summary", columnDefinition = "TEXT")
    private String summary;

    /**
     * Id of the last message folded into {@code summary}; everything after it
     * is still live history sent to the model.
     */
    @Column(name = "summary_through_message_id")
    private UUID summaryThroughMessageId;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
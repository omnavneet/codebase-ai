package com.codebaseai.backend.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.codebaseai.backend.dto.RenameSessionRequest;
import com.codebaseai.backend.model.ChatMessage;
import com.codebaseai.backend.model.Conversation;
import com.codebaseai.backend.service.ConversationService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Per-project conversations. Newest activity first; the conversation must
 * belong to the project in the path, else 404 (same ownership check as chat).
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService conversationService;

    @GetMapping("/projects/{projectId}/conversations")
    public ResponseEntity<List<Conversation>> listConversations(@PathVariable UUID projectId) {
        UUID userId = getCurrentUserId();
        return ResponseEntity.ok(conversationService.listConversations(projectId, userId));
    }

    @PostMapping("/projects/{projectId}/conversations")
    public ResponseEntity<Conversation> createConversation(@PathVariable UUID projectId) {
        UUID userId = getCurrentUserId();
        return ResponseEntity.ok(conversationService.createConversation(projectId, userId, "New Chat"));
    }

    @PatchMapping("/projects/{projectId}/conversations/{conversationId}")
    public ResponseEntity<Conversation> renameConversation(
            @PathVariable UUID projectId,
            @PathVariable UUID conversationId,
            @Valid @RequestBody RenameSessionRequest request) {
        UUID userId = getCurrentUserId();
        return ResponseEntity.ok(conversationService.renameConversation(
                projectId, userId, conversationId, request.getTitle()));
    }

    @DeleteMapping("/projects/{projectId}/conversations/{conversationId}")
    public ResponseEntity<Void> deleteConversation(
            @PathVariable UUID projectId,
            @PathVariable UUID conversationId) {
        UUID userId = getCurrentUserId();
        conversationService.deleteConversation(projectId, userId, conversationId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/projects/{projectId}/conversations/{conversationId}/messages")
    public ResponseEntity<List<ChatMessage>> getMessages(
            @PathVariable UUID projectId,
            @PathVariable UUID conversationId) {
        UUID userId = getCurrentUserId();
        return ResponseEntity.ok(conversationService.getMessages(projectId, userId, conversationId));
    }

    private UUID getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return UUID.fromString(authentication.getName());
    }
}
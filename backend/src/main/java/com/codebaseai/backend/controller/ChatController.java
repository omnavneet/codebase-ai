package com.codebaseai.backend.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.codebaseai.backend.dto.ChatMessageRequest;
import com.codebaseai.backend.dto.ChatMessageResponse;
import com.codebaseai.backend.model.ChatMessage;
import com.codebaseai.backend.model.ChatSession;
import com.codebaseai.backend.service.ChatService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ChatController {
    
    private final ChatService chatService;
    
    @PostMapping("/projects/{projectId}/sessions")
    public ResponseEntity<ChatSession> createSession(@PathVariable UUID projectId) {
        UUID userId = getCurrentUserId();
        return ResponseEntity.ok(chatService.createSession(projectId, userId));
    }
    
    @GetMapping("/projects/{projectId}/sessions")
    public ResponseEntity<List<ChatSession>> getSessions(@PathVariable UUID projectId) {
        return ResponseEntity.ok(chatService.getSessions(projectId));
    }
    
    @GetMapping("/sessions/{sessionId}/messages")
    public ResponseEntity<List<ChatMessage>> getMessages(@PathVariable UUID sessionId) {
        return ResponseEntity.ok(chatService.getMessages(sessionId));
    }
    
    @PostMapping("/sessions/{sessionId}/messages")
    public ResponseEntity<ChatMessageResponse> sendMessage(
            @PathVariable UUID sessionId,
            @RequestBody ChatMessageRequest request) {
        return ResponseEntity.ok(chatService.sendMessage(sessionId, request.getContent()));
    }
    
    private UUID getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return UUID.fromString(authentication.getName());
    }
}
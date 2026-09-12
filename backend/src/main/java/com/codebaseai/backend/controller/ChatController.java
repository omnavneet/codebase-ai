package com.codebaseai.backend.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.codebaseai.backend.dto.ChatMessageRequest;
import com.codebaseai.backend.dto.ChatMessageResponse;
import com.codebaseai.backend.dto.RenameSessionRequest;
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
        UUID userId = getCurrentUserId();
        return ResponseEntity.ok(chatService.getSessions(projectId, userId));
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public ResponseEntity<List<ChatMessage>> getMessages(@PathVariable UUID sessionId) {
        UUID userId = getCurrentUserId();
        return ResponseEntity.ok(chatService.getMessages(sessionId, userId));
    }

    /**
     * Sync variant of sendMessage. Used directly for non-streamed answers and
     * as the frontend's fallback when the stream fails before producing any
     * token. In the fallback case the client declares {@code userPersisted}
     * (the question was already saved by the streaming prep phase), so the
     * service skips persisting the user message again.
     */
    @PostMapping("/sessions/{sessionId}/messages")
    public ResponseEntity<ChatMessageResponse> sendMessage(
            @PathVariable UUID sessionId,
            @RequestParam(value = "userPersisted", defaultValue = "false") boolean userMessagePersisted,
            @RequestBody ChatMessageRequest request) {
        UUID userId = getCurrentUserId();
        return ResponseEntity.ok(chatService.sendMessage(
                sessionId, userId, request.getContent(), userMessagePersisted));
    }

    /**
     * Streaming variant of sendMessage. The blocking prep phase runs first
     * (so auth/validation errors surface as normal HTTP errors); the answer
     * is then streamed to the client as Server-Sent Events and persisted
     * server-side when the stream ends.
     */
    @PostMapping(value = "/sessions/{sessionId}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendMessageStream(
            @PathVariable UUID sessionId,
            @RequestBody ChatMessageRequest request) {
        UUID userId = getCurrentUserId();

        ChatService.PreparedChat prepared = chatService.prepareChat(sessionId, userId, request.getContent());

        SseEmitter emitter = new SseEmitter(120_000L);
        chatService.streamMessage(prepared, emitter);
        return emitter;
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Void> deleteSession(@PathVariable UUID sessionId) {
        UUID userId = getCurrentUserId();
        chatService.deleteSession(sessionId, userId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/sessions/{sessionId}")
    public ResponseEntity<ChatSession> renameSession(
            @PathVariable UUID sessionId,
            @RequestBody RenameSessionRequest request) {
        UUID userId = getCurrentUserId();
        return ResponseEntity.ok(chatService.renameSession(sessionId, userId, request.getTitle()));
    }
    
    private UUID getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return UUID.fromString(authentication.getName());
    }
}
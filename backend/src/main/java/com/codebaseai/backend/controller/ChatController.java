package com.codebaseai.backend.controller;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.codebaseai.backend.dto.ChatMessageRequest;
import com.codebaseai.backend.dto.ChatMessageResponse;
import com.codebaseai.backend.config.AppProperties;
import com.codebaseai.backend.service.ChatService;
import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ChatController {
    
    private final ChatService chatService;
    private final AppProperties properties;
    
    /**
     * Conversation-scoped sync chat. {@code conversationId} is optional: when
     * absent the project's most recent conversation is used (created when the
     * project has none), so old clients that never send an id keep working.
     */
    @PostMapping({"/projects/{projectId}/conversations/{conversationId}/messages",
            "/projects/{projectId}/conversations/messages"})
    public ResponseEntity<ChatMessageResponse> sendConversationMessage(
            @PathVariable UUID projectId,
            @PathVariable(required = false) UUID conversationId,
            @RequestParam(value = "userPersisted", defaultValue = "false") boolean userMessagePersisted,
            @Valid @RequestBody ChatMessageRequest request) {
        UUID userId = getCurrentUserId();
        return ResponseEntity.ok(chatService.sendMessage(
                projectId, userId, request.getContent(),
                userMessagePersisted, request.isRegenerate(), conversationId));
    }

    /**
     * Conversation-scoped streaming variant. Same fallback contract as the
     * session stream: {@code userPersisted} skips saving the question again,
     * and the answer is persisted server-side when the stream ends.
     */
    @PostMapping(value = {"/projects/{projectId}/conversations/{conversationId}/messages/stream",
            "/projects/{projectId}/conversations/messages/stream"},
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendConversationMessageStream(
            @PathVariable UUID projectId,
            @PathVariable(required = false) UUID conversationId,
            @RequestParam(value = "userPersisted", defaultValue = "false") boolean userMessagePersisted,
            @Valid @RequestBody ChatMessageRequest request) {
        UUID userId = getCurrentUserId();

        ChatService.PreparedChat prepared = chatService.prepareChat(
                projectId, userId, request.getContent(),
                !userMessagePersisted, request.isRegenerate(), conversationId);

        SseEmitter emitter = new SseEmitter(properties.getChat().getStreamTimeoutMs());
        chatService.streamMessage(prepared, emitter);
        return emitter;
    }

    private UUID getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return UUID.fromString(authentication.getName());
    }
}
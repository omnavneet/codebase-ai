package com.codebaseai.backend.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.codebaseai.backend.model.Conversation;
import com.codebaseai.backend.model.Project;
import com.codebaseai.backend.repository.ChatMessageRepository;
import com.codebaseai.backend.repository.ConversationRepository;
import com.codebaseai.backend.repository.ProjectRepository;

@ExtendWith(MockitoExtension.class)
class ChatTurnServiceTest {
    @Mock ProjectRepository projects;
    @Mock ConversationRepository conversations;
    @Mock ChatMessageRepository messages;
    @Mock JdbcTemplate jdbc;
    ChatTurnService service;
    UUID projectId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID conversationId = UUID.randomUUID();
    Conversation conversation;

    @BeforeEach
    void setup() {
        service = new ChatTurnService(projects, conversations, messages, jdbc);
        Project project = new Project();
        project.setId(projectId);
        project.setUserId(userId);
        project.setStatus("ready");
        conversation = new Conversation();
        conversation.setId(conversationId);
        conversation.setProjectId(projectId);
        lenient().when(projects.findById(projectId)).thenReturn(Optional.of(project));
        lenient().when(conversations.lockByIdAndProjectId(conversationId, projectId)).thenReturn(Optional.of(conversation));
    }

    @Test
    void completedRequestReplaysWithoutCreatingAnotherMessage() {
        UUID requestId = UUID.randomUUID();
        UUID userMessageId = UUID.randomUUID();
        UUID assistantId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        when(jdbc.queryForList(anyString(), eq(requestId))).thenReturn(List.of(Map.of(
                "conversation_id", conversationId, "user_message_id", userMessageId, "question", "Explain",
                "mode", "chat", "regenerate", false, "status", "complete", "attempt_id", attemptId,
                "result_data", "saved", "assistant_message_id", assistantId)));
        ChatTurnService.Turn turn = service.accept(projectId, userId, conversationId, requestId, null, "chat", "Explain", false);
        assertThat(turn.completed()).isTrue();
        assertThat(turn.resultData()).isEqualTo("saved");
        verify(messages, never()).saveAndFlush(any());
    }

    @Test
    void requestIdCannotBeReusedForDifferentQuestion() {
        UUID requestId = UUID.randomUUID();
        when(jdbc.queryForList(anyString(), eq(requestId))).thenReturn(List.of(Map.of(
                "conversation_id", conversationId, "user_message_id", UUID.randomUUID(),
                "question", "Original", "mode", "chat", "regenerate", false)));
        assertThatThrownBy(() -> service.accept(projectId, userId, conversationId, requestId, null, "chat", "Different", false))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Request ID belongs");
        verify(messages, never()).saveAndFlush(any());
    }

    @Test
    void interruptedRequestReusesQuestionWithNewAttempt() {
        UUID requestId = UUID.randomUUID();
        UUID userMessageId = UUID.randomUUID();
        UUID oldAttempt = UUID.randomUUID();
        when(jdbc.queryForList(anyString(), eq(requestId))).thenReturn(List.of(Map.of(
                "conversation_id", conversationId, "user_message_id", userMessageId, "question", "Explain",
                "mode", "chat", "regenerate", false, "status", "interrupted", "attempt_id", oldAttempt,
                "started_at", Timestamp.valueOf(LocalDateTime.now()))));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(conversationId))).thenReturn(0);
        com.codebaseai.backend.model.ChatMessage existing = new com.codebaseai.backend.model.ChatMessage();
        existing.setId(userMessageId);
        existing.setRole("user");
        when(messages.findByConversationIdOrderByCreatedAtAsc(conversationId)).thenReturn(List.of(existing));
        ChatTurnService.Turn turn = service.accept(projectId, userId, conversationId, requestId, null, "chat", "Explain", false);
        assertThat(turn.userMessageId()).isEqualTo(userMessageId);
        assertThat(turn.attemptId()).isNotEqualTo(oldAttempt);
        verify(messages, never()).saveAndFlush(any());
    }

    @Test
    void interruptedRequestCannotRunAfterNewerQuestion() {
        UUID requestId = UUID.randomUUID();
        UUID originalUserId = UUID.randomUUID();
        when(jdbc.queryForList(anyString(), eq(requestId))).thenReturn(List.of(Map.of(
                "conversation_id", conversationId, "user_message_id", originalUserId, "question", "Explain",
                "mode", "chat", "regenerate", false, "status", "interrupted", "attempt_id", UUID.randomUUID(),
                "started_at", Timestamp.valueOf(LocalDateTime.now()))));
        com.codebaseai.backend.model.ChatMessage newer = new com.codebaseai.backend.model.ChatMessage();
        newer.setId(UUID.randomUUID());
        newer.setRole("user");
        when(messages.findByConversationIdOrderByCreatedAtAsc(conversationId)).thenReturn(List.of(newer));
        assertThatThrownBy(() -> service.accept(projectId, userId, conversationId, requestId, null, "chat", "Explain", false))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("newer question");
    }

    @Test
    void followUpRetrievalUsesPreviousQuestionButSpecificQuestionsStayUnchanged() {
        com.codebaseai.backend.model.ChatMessage prior = new com.codebaseai.backend.model.ChatMessage();
        prior.setRole("user");
        prior.setContent("Where is PaymentService?");
        assertThat(ChatService.retrievalQuestion("How does it work?", List.of(prior)))
                .contains("PaymentService");
        assertThat(ChatService.retrievalQuestion("Where is AccountService?", List.of(prior)))
                .isEqualTo("Where is AccountService?");
    }

    @Test
    void staleCompletionCannotReplaceAnswer() {
        UUID requestId = UUID.randomUUID();
        ChatTurnService.Turn turn = new ChatTurnService.Turn(requestId, UUID.randomUUID(), conversationId,
                UUID.randomUUID(), false, null, null);
        when(conversations.findById(conversationId)).thenReturn(Optional.of(conversation));
        when(jdbc.queryForList(anyString(), eq(requestId))).thenReturn(List.of(Map.of(
                "status", "running", "attempt_id", UUID.randomUUID())));
        assertThatThrownBy(() -> service.complete(turn, "answer", "[]", null, "result"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("no longer active");
        verify(messages, never()).saveAndFlush(any());
    }
}

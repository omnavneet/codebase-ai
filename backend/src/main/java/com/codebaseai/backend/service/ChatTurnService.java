package com.codebaseai.backend.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.codebaseai.backend.model.ChatMessage;
import com.codebaseai.backend.model.Conversation;
import com.codebaseai.backend.model.Project;
import com.codebaseai.backend.repository.ChatMessageRepository;
import com.codebaseai.backend.repository.ConversationRepository;
import com.codebaseai.backend.repository.ProjectRepository;

import lombok.RequiredArgsConstructor;

/** Transactions for accepting and completing a chat turn. Provider calls happen outside them. */
@Service
@RequiredArgsConstructor
public class ChatTurnService {
    private final ProjectRepository projects;
    private final ConversationRepository conversations;
    private final ChatMessageRepository messages;
    private final JdbcTemplate jdbc;

    public record Turn(UUID requestId, UUID attemptId, UUID conversationId, UUID userMessageId,
                       boolean regenerate, String resultData, UUID assistantMessageId) {
        public boolean completed() { return resultData != null; }
    }

    @Transactional
    public Turn accept(UUID projectId, UUID userId, UUID conversationId, UUID requestedId,
                       UUID replyToId, String mode, String question, boolean regenerate) {
        Project project = projects.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
        if (!project.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }
        if (!"ready".equals(project.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Project is not ready");
        }
        String text = question == null ? "" : question.trim();
        if (text.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Question cannot be empty");
        regenerate = regenerate || replyToId != null;
        Conversation conversation;
        if (conversationId == null) {
            conversation = conversations.findByProjectIdOrderByUpdatedAtDesc(projectId).stream().findFirst()
                    .orElseGet(() -> {
                        Conversation created = new Conversation();
                        created.setProjectId(projectId);
                        return conversations.saveAndFlush(created);
                    });
            conversationId = conversation.getId();
        }
        conversation = conversations.lockByIdAndProjectId(conversationId, projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found"));
        UUID requestId = requestedId == null ? UUID.randomUUID() : requestedId;
        jdbc.update("UPDATE chat_runs SET status = 'interrupted' WHERE conversation_id = ? AND status = 'running' AND started_at <= ?",
                conversationId, LocalDateTime.now().minusMinutes(10));
        List<Map<String, Object>> existing = jdbc.queryForList("SELECT * FROM chat_runs WHERE request_id = ?", requestId);
        if (!existing.isEmpty()) {
            Map<String, Object> run = existing.get(0);
            UUID storedUserId = (UUID) run.get("user_message_id");
            if (!conversationId.equals(run.get("conversation_id")) || !text.equals(run.get("question"))
                    || !mode.equals(run.get("mode")) || regenerate != (Boolean) run.get("regenerate")
                    || (replyToId != null && !replyToId.equals(storedUserId))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Request ID belongs to another turn");
            }
            if ("complete".equals(run.get("status"))) {
                return new Turn(requestId, (UUID) run.get("attempt_id"), conversationId, storedUserId,
                        regenerate, (String) run.get("result_data"), (UUID) run.get("assistant_message_id"));
            }
            LocalDateTime started = ((java.sql.Timestamp) run.get("started_at")).toLocalDateTime();
            if ("running".equals(run.get("status")) && started.isAfter(LocalDateTime.now().minusMinutes(10))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "This request is still running");
            }
            List<ChatMessage> history = messages.findByConversationIdOrderByCreatedAtAsc(conversationId);
            UUID latestUserId = null;
            for (int i = history.size() - 1; i >= 0; i--) {
                if ("user".equals(history.get(i).getRole())) {
                    latestUserId = history.get(i).getId();
                    break;
                }
            }
            if (!storedUserId.equals(latestUserId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "A newer question exists in this conversation");
            }
            Integer running = jdbc.queryForObject(
                    "SELECT count(*) FROM chat_runs WHERE conversation_id = ? AND status = 'running'",
                    Integer.class, conversationId);
            if (running != null && running > 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Another request is running in this conversation");
            }
            UUID attempt = UUID.randomUUID();
            jdbc.update("UPDATE chat_runs SET status = 'running', attempt_id = ?, started_at = ? WHERE request_id = ?",
                    attempt, LocalDateTime.now(), requestId);
            return new Turn(requestId, attempt, conversationId, storedUserId, regenerate, null, null);
        }
        Integer active = jdbc.queryForObject("SELECT count(*) FROM chat_runs WHERE conversation_id = ? AND status = 'running'",
                Integer.class, conversationId);
        if (active != null && active > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Another request is running in this conversation");
        }
        List<ChatMessage> history = messages.findByConversationIdOrderByCreatedAtAsc(conversationId);
        ChatMessage userMessage;
        if (regenerate || replyToId != null) {
            int lastUser = -1;
            for (int i = history.size() - 1; i >= 0; i--) {
                if ("user".equals(history.get(i).getRole())) { lastUser = i; break; }
            }
            if (lastUser < 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "No question to regenerate");
            userMessage = history.get(lastUser);
            if ((replyToId != null && !replyToId.equals(userMessage.getId())) || !text.equals(userMessage.getContent())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Regeneration must target the latest question");
            }
            regenerate = true;
        } else {
            userMessage = new ChatMessage();
            userMessage.setConversationId(conversationId);
            userMessage.setRole("user");
            userMessage.setContent(text);
            userMessage = messages.saveAndFlush(userMessage);
            if (conversation.getTitle() == null || conversation.getTitle().isBlank() || "New Chat".equals(conversation.getTitle())) {
                String firstLine = text.lines().findFirst().orElse(text).trim();
                String title = firstLine.substring(0, Math.min(40, firstLine.length()));
                if (!title.isEmpty() && Character.isHighSurrogate(title.charAt(title.length() - 1))) title = title.substring(0, title.length() - 1);
                conversation.setTitle(title);
            }
        }
        if (regenerate) {
            conversation.setSummary(null);
            conversation.setSummaryThroughMessageId(null);
        }
        conversation.setHistoryRevision(conversation.getHistoryRevision() + 1);
        conversations.save(conversation);
        UUID attempt = UUID.randomUUID();
        jdbc.update("INSERT INTO chat_runs(request_id, conversation_id, user_message_id, mode, question, regenerate, status, attempt_id, started_at) VALUES (?, ?, ?, ?, ?, ?, 'running', ?, ?)",
                requestId, conversationId, userMessage.getId(), mode, text, regenerate, attempt, LocalDateTime.now());
        return new Turn(requestId, attempt, conversationId, userMessage.getId(), regenerate, null, null);
    }

    @Transactional
    public UUID complete(Turn turn, String answer, String citations, String metadata, String resultData) {
        Conversation conversation = conversations.findById(turn.conversationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found"));
        conversations.lockByIdAndProjectId(turn.conversationId(), conversation.getProjectId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found"));
        List<Map<String, Object>> runs = jdbc.queryForList("SELECT status, attempt_id FROM chat_runs WHERE request_id = ? FOR UPDATE", turn.requestId());
        if (runs.isEmpty() || !"running".equals(runs.get(0).get("status")) || !turn.attemptId().equals(runs.get(0).get("attempt_id"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Chat attempt is no longer active");
        }
        ChatMessage assistant = messages.findByReplyToMessageId(turn.userMessageId()).orElse(null);
        if (assistant == null && turn.regenerate()) {
            List<ChatMessage> history = messages.findByConversationIdOrderByCreatedAtAsc(turn.conversationId());
            for (int i = 0; i + 1 < history.size(); i++) {
                if (turn.userMessageId().equals(history.get(i).getId()) && "assistant".equals(history.get(i + 1).getRole())) {
                    assistant = history.get(i + 1);
                    break;
                }
            }
        }
        if (assistant == null) {
            assistant = new ChatMessage();
            assistant.setConversationId(turn.conversationId());
            assistant.setRole("assistant");
        }
        assistant.setReplyToMessageId(turn.userMessageId());
        assistant.setContent(answer);
        assistant.setCitations(citations);
        assistant.setRunMetadata(metadata);
        assistant = messages.saveAndFlush(assistant);
        jdbc.update("UPDATE chat_runs SET status = 'complete', result_data = ?, assistant_message_id = ? WHERE request_id = ? AND attempt_id = ?",
                resultData, assistant.getId(), turn.requestId(), turn.attemptId());
        conversation.setUpdatedAt(LocalDateTime.now());
        conversations.save(conversation);
        return assistant.getId();
    }

    @Transactional
    public void interrupt(Turn turn) {
        jdbc.update("UPDATE chat_runs SET status = 'interrupted' WHERE request_id = ? AND attempt_id = ? AND status = 'running'",
                turn.requestId(), turn.attemptId());
    }
}

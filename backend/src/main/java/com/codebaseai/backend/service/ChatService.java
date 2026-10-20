package com.codebaseai.backend.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import reactor.core.Disposable;
import reactor.core.scheduler.Schedulers;

import com.codebaseai.backend.dto.ChatMessageResponse;
import com.codebaseai.backend.config.AppProperties;
import com.codebaseai.backend.model.ChatMessage;
import com.codebaseai.backend.model.ChatSession;
import com.codebaseai.backend.model.CodeChunk;
import com.codebaseai.backend.model.Project;
import com.codebaseai.backend.model.ProjectFile;
import com.codebaseai.backend.repository.ChatMessageRepository;
import com.codebaseai.backend.repository.ChatSessionRepository;
import com.codebaseai.backend.repository.CodeChunkRepository;
import com.codebaseai.backend.repository.ProjectFileRepository;
import com.codebaseai.backend.repository.ProjectRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {
    
    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final CodeChunkRepository codeChunkRepository;
    private final ProjectFileRepository projectFileRepository;
    private final ProjectRepository projectRepository;
    private final AiServiceClient aiServiceClient;
    private final ObjectMapper objectMapper;
    private final AppProperties properties;
    
    @Transactional
    public ChatSession createSession(UUID projectId, UUID userId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
        if (!project.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }

        ChatSession session = new ChatSession();
        session.setProjectId(projectId);
        session.setUserId(userId);
        session.setTitle("New Chat");
        return chatSessionRepository.save(session);
    }

    public List<ChatSession> getSessions(UUID projectId, UUID userId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
        if (!project.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }

        return chatSessionRepository.findByProjectIdOrderByUpdatedAtDesc(projectId);
    }

    public List<ChatMessage> getMessages(UUID sessionId, UUID userId) {
        ChatSession session = chatSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));
        if (!session.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }

        return chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
    }

    @Transactional
    public void deleteSession(UUID sessionId, UUID userId) {
        ChatSession session = chatSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));
        if (!session.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }

        chatMessageRepository.deleteAll(
                chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId));
        chatSessionRepository.deleteById(sessionId);
    }

    @Transactional
    public ChatSession renameSession(UUID sessionId, UUID userId, String title) {
        if (title == null || title.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Session title cannot be empty");
        }

        ChatSession session = chatSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));
        if (!session.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }

        session.setTitle(title.trim());
        return chatSessionRepository.save(session);
    }

    /**
     * Result of the blocking prep phase: session, question, retrieved context
     * and the recent conversation history (for LLM continuity). The flag says
     * whether this run is a re-ask that must replace the trailing answer.
     */
    public record PreparedChat(
            ChatSession session,
            String question,
            List<Map<String, Object>> context,
            List<Map<String, String>> history,
            boolean regenerate) {}

    @Transactional
    public ChatMessageResponse sendMessage(
            UUID sessionId, UUID userId, String question,
            boolean userMessagePersisted, boolean regenerate) {
        PreparedChat prepared = prepareChat(
                sessionId, userId, question, !userMessagePersisted, regenerate);

        Map<String, Object> aiResponse = aiServiceClient.chat(
                prepared.question(), prepared.context(), prepared.history());
        String answer = (String) aiResponse.get("answer");

        persistAssistantMessage(
                prepared.session(), answer != null ? answer : "",
                prepared.context(), prepared.regenerate());

        return new ChatMessageResponse(answer, prepared.context());
    }

    /**
     * Blocking prep phase shared by the sync and streaming paths: validates
     * access, persists the user message and retrieves RAG context.
     *
     * Deliberately NOT @Transactional: each repository operation opens its own
     * short transaction via {@link org.springframework.data.jpa.repository.JpaRepository},
     * so no DB connection is ever held across the remote embedding HTTP call.
     * (It also was not applied on the sync path — Spring proxies bypass
     * self-invocation — so removing it changes nothing there.)
     */
    public PreparedChat prepareChat(UUID sessionId, UUID userId, String question) {
        return prepareChat(sessionId, userId, question, true, false);
    }

    /**
     * Variant that lets the sync endpoint skip persisting the user message.
     * The streaming path persists the user message in this prep phase, so the
     * sync endpoint — used as a fallback when streaming fails — must not save
     * the question again, otherwise it appears duplicated in the conversation.
     *
     * <p>{@code regenerate} marks a re-ask of the trailing question: the stale
     * answer and the stored copy of the question are dropped from the LLM
     * history (the question itself is passed to the model separately) and the
     * user message is never persisted again, so history keeps exactly one
     * copy of the question and one answer per ask.
     */
    public PreparedChat prepareChat(
            UUID sessionId, UUID userId, String question,
            boolean persistUserMessage, boolean regenerate) {
        ChatSession session = chatSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));
        if (!session.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }

        if (question == null || question.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Question cannot be empty");
        }
        String trimmedQuestion = question.trim();

        // Build the LLM conversation history BEFORE the user message is
        // persisted, so the current question is never a history entry on the
        // streaming path.
        List<ChatMessage> recentMessages =
                chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        // Re-ask (regenerate): drop the stale trailing answer first; the rule
        // below then drops the trailing copy of this question, so the prompt
        // matches the original ask and nothing is prompted (or stored) twice.
        if (regenerate && !recentMessages.isEmpty()) {
            int end = recentMessages.size();
            if ("assistant".equals(recentMessages.get(end - 1).getRole())) {
                recentMessages = recentMessages.subList(0, end - 1);
            }
        }
        // Fallback path (persistUserMessage=false): the failed stream already
        // persisted this exact question, so it is the trailing entry — drop it
        // to avoid prompting it twice.
        if (!recentMessages.isEmpty()) {
            ChatMessage last = recentMessages.get(recentMessages.size() - 1);
            if ("user".equals(last.getRole()) && trimmedQuestion.equals(last.getContent())) {
                recentMessages = recentMessages.subList(0, recentMessages.size() - 1);
            }
        }
        // Last N messages (see app.chat.history-max-messages, default 8), each
        // truncated to bound the prompt size sent to the LLM. No summarisation.
        int historySize = Math.max(0, properties.getChat().getHistoryMaxMessages());
        int from = Math.max(0, recentMessages.size() - historySize);
        List<Map<String, String>> history = new ArrayList<>();
        for (ChatMessage message : recentMessages.subList(from, recentMessages.size())) {
            String content = message.getContent();
            history.add(Map.of(
                    "role", message.getRole(),
                    "content", content.length() > 2000 ? content.substring(0, 2000) : content));
        }

        // A regenerate never re-persists the question: it is already stored.
        if (persistUserMessage && !regenerate) {
            ChatMessage userMessage = new ChatMessage();
            userMessage.setSessionId(sessionId);
            userMessage.setRole("user");
            userMessage.setContent(trimmedQuestion);
            chatMessageRepository.save(userMessage);
        }

        List<List<Double>> questionEmbeddings = aiServiceClient.generateEmbeddings(
            List.of(trimmedQuestion)
        );
        List<Double> questionEmbedding = questionEmbeddings.get(0);

        String embeddingString = Arrays.toString(questionEmbedding.toArray());
        List<CodeChunk> similarChunks = codeChunkRepository.findSimilarChunks(
            session.getProjectId(),
            embeddingString,
            properties.getRetrieval().getChatTopK()
        );

        List<Map<String, Object>> context = similarChunks.stream()
                .map(chunk -> {
                    Map<String, Object> ctx = new HashMap<>();
                    // Cap each chunk (app.retrieval.chat-context-max-chars,
                    // default 2000) so one huge chunk cannot blow the model's
                    // context window; chatTopK caps the count.
                    ctx.put("content", truncate(
                            chunk.getContent(),
                            properties.getRetrieval().getChatContextMaxChars()));
                    ctx.put("start_line", chunk.getStartLine());
                    ctx.put("end_line", chunk.getEndLine());
                    String filePath = projectFileRepository.findById(chunk.getFileId())
                            .map(ProjectFile::getPath)
                            .orElse("unknown");
                    ctx.put("file_path", filePath);
                    return ctx;
                })
                .collect(Collectors.toList());

        if (session.getTitle().equals("New Chat")) {
            String title = trimmedQuestion.substring(0, Math.min(50, trimmedQuestion.length()));
            // Avoid cutting a surrogate pair (emoji) in half.
            if (!title.isEmpty() && Character.isHighSurrogate(title.charAt(title.length() - 1))) {
                title = title.substring(0, title.length() - 1);
            }
            session.setTitle(title);
        }
        // Keep the sidebar's "recently updated" ordering meaningful: bump the
        // session on every message. The @PreUpdate hook only fires when the
        // entity is dirty, so set the column explicitly and save.
        session.setUpdatedAt(LocalDateTime.now());
        chatSessionRepository.save(session);

        return new PreparedChat(session, trimmedQuestion, context, history, regenerate);
    }

    /**
     * Persist the answer. On a regenerate the trailing assistant message is
     * overwritten in place (one answer per question in history); when no such
     * message exists — the first attempt errored before any token — a new row
     * is inserted as usual.
     */
    private ChatMessage persistAssistantMessage(
            ChatSession session, String content,
            List<Map<String, Object>> context, boolean regenerate) {
        ChatMessage assistantMessage = null;
        if (regenerate) {
            List<ChatMessage> messages =
                    chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId());
            if (!messages.isEmpty()) {
                ChatMessage last = messages.get(messages.size() - 1);
                if ("assistant".equals(last.getRole())) {
                    assistantMessage = last;
                }
            }
        }
        if (assistantMessage == null) {
            assistantMessage = new ChatMessage();
            assistantMessage.setSessionId(session.getId());
            assistantMessage.setRole("assistant");
        }
        assistantMessage.setContent(content);

        try {
            assistantMessage.setCitations(objectMapper.writeValueAsString(context));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize citations", e);
        }

        return chatMessageRepository.save(assistantMessage);
    }

    /**
     * Stream an answer to the client via SSE. Persists the (possibly partial)
     * answer exactly once, whether the stream completes, fails, or the client
     * disconnects mid-stream.
     */
    public void streamMessage(PreparedChat prepared, SseEmitter emitter) {
        ChatSession session = prepared.session();
        StringBuilder content = new StringBuilder();
        AtomicBoolean persisted = new AtomicBoolean(false);
        AtomicReference<Disposable> subscriptionRef = new AtomicReference<>();

        // Persist whatever we have, exactly once, on any exit path. A DB
        // failure here must never escape into the stream or to Spring.
        Runnable persistPartial = () -> {
            if (persisted.compareAndSet(false, true) && content.length() > 0) {
                try {
                    persistAssistantMessage(session, content.toString(),
                            prepared.context(), prepared.regenerate());
                } catch (RuntimeException e) {
                    log.error("Failed to persist partial answer for session {}", session.getId(), e);
                }
            }
        };

        emitter.onCompletion(persistPartial::run);
        emitter.onTimeout(() -> {
            Disposable sub = subscriptionRef.get();
            if (sub != null) {
                sub.dispose();
            }
            persistPartial.run();
        });
        emitter.onError(t -> {
            Disposable sub = subscriptionRef.get();
            if (sub != null) {
                sub.dispose();
            }
            persistPartial.run();
        });

        // Deliver the sources up front. They are also persisted with the answer,
        // but without this event a streamed answer would show no citations until
        // the session is re-opened — the sync path returns them in its body, so
        // the two paths must agree. Sends before the emitter is initialized are
        // buffered by Spring and flushed in order by initialize().
        try {
            emitter.send(SseEmitter.event()
                    .name("meta")
                    .data(Map.of("citations", prepared.context())));
        } catch (IOException | RuntimeException e) {
            // Client is already gone (or the emitter closed): nothing left to stream.
            log.debug("Chat meta event undeliverable session={}: {}", session.getId(), e.toString());
            persistPartial.run();
            completeQuietly(emitter);
            return;
        }

        // Run the streaming callbacks (JDBC persistence and SseEmitter sends)
        // on boundedElastic instead of the WebClient's event-loop threads,
        // which must never be blocked.
        subscriptionRef.set(aiServiceClient
                .chatStream(prepared.question(), prepared.context(), prepared.history())
                .publishOn(Schedulers.boundedElastic())
                .subscribe(
                        event -> {
                            String type = event.event() == null ? "" : event.event();
                            String data = event.data() == null ? "" : event.data();

                            if ("token".equals(type)) {
                                String token = extractToken(data);
                                if (token.isEmpty()) {
                                    return;
                                }
                                content.append(token);
                                try {
                                    emitter.send(SseEmitter.event()
                                            .name("token")
                                            .data(Map.of("t", token)));
                                } catch (IOException e) {
                                    // Client disconnected mid-stream: stop upstream and persist.
                                    Disposable sub = subscriptionRef.get();
                                    if (sub != null) {
                                        sub.dispose();
                                    }
                                    persistPartial.run();
                                    completeQuietly(emitter);
                                } catch (RuntimeException e) {
                                    // Emitter already closed; never let this reach
                                    // Spring after the response is committed.
                                    log.debug("Chat token send failed session={}: {}",
                                            session.getId(), e.toString());
                                    Disposable sub = subscriptionRef.get();
                                    if (sub != null) {
                                        sub.dispose();
                                    }
                                    persistPartial.run();
                                    completeQuietly(emitter);
                                }
                            } else if ("error".equals(type)) {
                                log.warn("AI service reported a stream error for session {} (code={}): {}",
                                        session.getId(),
                                        extractField(data, "code"),
                                        extractField(data, "message"));
                                persistPartial.run();
                                // Ship the upstream user-safe message and code
                                // verbatim (already sanitized in Python).
                                sendError(emitter, errorPayload(data,
                                        "The AI service failed while generating the answer.",
                                        "upstream_error"));
                                completeQuietly(emitter);
                            }
                            // The Python `done` event is ignored: this service owns the
                            // terminal `done`, sent after the answer is persisted.
                        },
                        error -> {
                            Map<String, String> payload = AiStreamErrors.describe(error);
                            log.error("Chat stream failed session={} project={} code={}",
                                    session.getId(), session.getProjectId(), payload.get("code"), error);
                            persistPartial.run();
                            sendError(emitter, payload);
                            completeQuietly(emitter);
                        },
                        () -> {
                            // Own the persisted flag so the onCompletion safety
                            // net below cannot persist the same answer twice.
                            String messageId = "";
                            if (persisted.compareAndSet(false, true) && content.length() > 0) {
                                try {
                                    ChatMessage saved = persistAssistantMessage(
                                            session, content.toString(),
                                            prepared.context(), prepared.regenerate());
                                    messageId = String.valueOf(saved.getId());
                                } catch (RuntimeException e) {
                                    // Still send `done`: losing history must not
                                    // break the live stream.
                                    log.error("Failed to persist answer for session {}", session.getId(), e);
                                }
                            }
                            try {
                                emitter.send(SseEmitter.event()
                                        .name("done")
                                        .data(Map.of("messageId", messageId)));
                            } catch (IOException | RuntimeException e) {
                                // Client is gone or the emitter already closed;
                                // persistence already happened.
                                log.debug("Chat done event undeliverable session={}: {}",
                                        session.getId(), e.toString());
                            }
                            completeQuietly(emitter);
                        }));
    }

    private String extractToken(String data) {
        try {
            return objectMapper.readTree(data).path("t").asText("");
        } catch (JsonProcessingException e) {
            return "";
        }
    }

    /** Truncate to {@code maxChars} without ever returning more than asked. */
    private static String truncate(String value, int maxChars) {
        if (value == null || maxChars <= 0 || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars);
    }

    /** Send the terminal {@code error} event; never throws after commit. */
    private void sendError(SseEmitter emitter, Map<String, String> payload) {
        try {
            emitter.send(SseEmitter.event().name("error").data(payload));
        } catch (IOException | RuntimeException e) {
            // Client is gone or the emitter already closed.
            log.debug("Chat error event undeliverable: {}", e.toString());
        }
    }

    /** Complete the emitter, swallowing the "already closed" race. */
    private void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (RuntimeException e) {
            log.debug("Chat emitter already closed: {}", e.toString());
        }
    }

    /** Read a string field from an SSE JSON payload ("" when absent). */
    private String extractField(String data, String field) {
        if (data == null || data.isBlank()) {
            return "";
        }
        try {
            return objectMapper.readTree(data).path(field).asText("");
        } catch (JsonProcessingException e) {
            return "";
        }
    }

    /**
     * Build the SSE {@code error} payload from an upstream (Python) error
     * event, keeping its user-safe message and code when present and falling
     * back to {@code fallbackMessage}/{@code fallbackCode} otherwise. Raw
     * provider detail is never accepted from the upstream payload beyond the
     * message the Python service already sanitized.
     */
    private Map<String, String> errorPayload(String data, String fallbackMessage, String fallbackCode) {
        if (data != null && !data.isBlank()) {
            try {
                var node = objectMapper.readTree(data);
                String message = node.path("message").asText("").trim();
                String code = node.path("code").asText("").trim();
                if (!message.isEmpty()) {
                    Map<String, String> payload = new HashMap<>();
                    payload.put("message", message);
                    payload.put("code", code.isEmpty() ? fallbackCode : code);
                    return payload;
                }
            } catch (JsonProcessingException e) {
                // Not JSON (or truncated): use the fallback below.
            }
        }
        Map<String, String> payload = new HashMap<>();
        payload.put("message", fallbackMessage);
        payload.put("code", fallbackCode);
        return payload;
    }
}
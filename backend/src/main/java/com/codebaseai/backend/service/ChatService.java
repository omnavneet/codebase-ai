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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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
import com.codebaseai.backend.model.Conversation;
import com.codebaseai.backend.model.Project;
import com.codebaseai.backend.model.ProjectFile;
import com.codebaseai.backend.repository.ChatMessageRepository;
import com.codebaseai.backend.repository.ChatSessionRepository;
import com.codebaseai.backend.repository.CodeChunkRepository;
import com.codebaseai.backend.repository.ConversationRepository;
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
    private final ConversationRepository conversationRepository;
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
     * Result of the blocking prep phase: the conversation (or legacy session)
     * the messages belong to, the question, the retrieved context and the
     * recent conversation history (for LLM continuity). The flag says whether
     * this run is a re-ask that must replace the trailing answer.
     */








        public record PreparedChat(
            UUID conversationId, // conversation-scoped path
            UUID sessionId, // legacy session-scoped path
            UUID projectId,
            String question,
            List<Map<String, Object>> context,
            List<Map<String, String>> history,
            boolean regenerate,
            int usedChars) {}

    @Transactional
    public ChatMessageResponse sendMessage(
            UUID sessionId, UUID userId, String question,
            boolean userMessagePersisted, boolean regenerate) {
        ChatSession session = chatSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));
        if (!session.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }
        PreparedChat prepared = prepareChat(
                sessionId, userId, question, !userMessagePersisted, regenerate);

        int usedChars = calculateUsedChars(
            prepared.question(), prepared.history(), prepared.context());
        int budgetChars = properties.getChat().getChatContextBudgetChars();

        Map<String, Object> aiResponse = aiServiceClient.chat(
                prepared.question(), prepared.context(), prepared.history());
        String answer = (String) aiResponse.get("answer");

        persistAssistantMessage(
                prepared.sessionId(), false, answer != null ? answer : "",
                prepared.context(), prepared.regenerate());
        // Bump the session's updated_at so the sidebar ordering stays current.
        session.setUpdatedAt(LocalDateTime.now());
        chatSessionRepository.save(session);

        CompletableFuture.runAsync(() -> maybeSummarize(session.getId()));

        return new ChatMessageResponse(answer, prepared.context(), usedChars, budgetChars);
    }

    /**
     * Conversation-scoped variant of sendMessage. {@code conversationId} is
     * optional: when absent the project's most recent conversation is used,
     * creating one if none exists. History is the last N messages of that
     * conversation only, and every message is tagged with its conversation_id.
     */
    @Transactional
    public ChatMessageResponse sendMessage(
            UUID projectId, UUID userId, String question,
            boolean userMessagePersisted, boolean regenerate,
            UUID conversationId) {
        PreparedChat prepared = prepareChat(
                projectId, userId, question, !userMessagePersisted, regenerate,
                conversationId);

        int usedChars = calculateUsedChars(
            prepared.question(), prepared.history(), prepared.context());
        int budgetChars = properties.getChat().getChatContextBudgetChars();

        Map<String, Object> aiResponse = aiServiceClient.chat(
                prepared.question(), prepared.context(), prepared.history());
        String answer = (String) aiResponse.get("answer");

        persistAssistantMessage(
                prepared.conversationId(), true, answer != null ? answer : "",
                prepared.context(), prepared.regenerate());
        // Bump the conversation's updated_at so the sidebar ordering stays current.
        Conversation conversation = conversationRepository.findById(prepared.conversationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found"));
        conversation.setUpdatedAt(LocalDateTime.now());
        conversationRepository.save(conversation);
        CompletableFuture.runAsync(() -> maybeSummarize(conversation.getId()));

        return new ChatMessageResponse(answer, prepared.context(), usedChars, budgetChars);
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

        return new PreparedChat(null, sessionId, session.getProjectId(),
                trimmedQuestion, context, history, regenerate, 0);
    }

    /**
     * Conversation-scoped prep phase. {@code conversationId} is optional: when
     * absent the project's most recent conversation is used (created if none).
     * History is the last N messages of that conversation only.
     */
    public PreparedChat prepareChat(
            UUID projectId, UUID userId, String question,
            boolean persistUserMessage, boolean regenerate,
            UUID conversationId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
        if (!project.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }

        Conversation conversation;
        if (conversationId != null) {
            conversation = conversationRepository.findByIdAndProjectId(conversationId, projectId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found"));
        } else {
            conversation = conversationRepository.findByProjectIdOrderByUpdatedAtDesc(projectId).stream()
                    .findFirst()
                    .orElseGet(() -> {
                        Conversation created = new Conversation();
                        created.setProjectId(projectId);
                        created.setTitle("New Chat");
                        return conversationRepository.save(created);
                    });
        }

        if (question == null || question.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Question cannot be empty");
        }
        String trimmedQuestion = question.trim();

                List<ChatMessage> allMessages =
                chatMessageRepository.findByConversationIdOrderByCreatedAtAsc(conversation.getId());
        // Fold older turns into a running summary so the prompt never grows
        // unboundedly: messages covered by the conversation's summary are not
        // re-sent as live history; their condensed form is prepended instead.
        List<ChatMessage> liveMessages = liveHistory(conversation, allMessages);
        if (regenerate && !liveMessages.isEmpty()) {
            int end = liveMessages.size();
            if ("assistant".equals(liveMessages.get(end - 1).getRole())) {
                liveMessages = liveMessages.subList(0, end - 1);
            }
        }
        if (regenerate) {
            conversation.setSummary(null);
            conversation.setSummaryThroughMessageId(null);
        }
        if (!liveMessages.isEmpty()) {
            ChatMessage last = liveMessages.get(liveMessages.size() - 1);
            if ("user".equals(last.getRole()) && trimmedQuestion.equals(last.getContent())) {
                liveMessages = liveMessages.subList(0, liveMessages.size() - 1);
            }
        }
        int historySize = Math.max(0, properties.getChat().getHistoryMaxMessages());
        int from = Math.max(0, liveMessages.size() - historySize);
        List<Map<String, String>> history = new ArrayList<>();
        if (conversation.getSummary() != null && !conversation.getSummary().isBlank()) {
            history.add(Map.of("role", "system",
                    "content", "Earlier discussion summary: " + conversation.getSummary()));
        }
        for (ChatMessage message : liveMessages.subList(from, liveMessages.size())) {
            String content = message.getContent();
            history.add(Map.of("role", message.getRole(),
                    "content", content.length() > 2000 ? content.substring(0, 2000) : content));
        }

        if (persistUserMessage && !regenerate) {
            ChatMessage userMessage = new ChatMessage();
            userMessage.setConversationId(conversation.getId());
            userMessage.setRole("user");
            userMessage.setContent(trimmedQuestion);
            chatMessageRepository.save(userMessage);

            String currentTitle = conversation.getTitle();
            if (currentTitle == null || currentTitle.isBlank() || "New Chat".equals(currentTitle)) {
                String firstLine = trimmedQuestion.lines().findFirst().orElse(trimmedQuestion).trim();
                String title = firstLine.substring(0, Math.min(40, firstLine.length()));
                if (!title.isEmpty()
                        && Character.isHighSurrogate(title.charAt(title.length() - 1))) {
                    title = title.substring(0, title.length() - 1);
                }
                conversation.setTitle(title);
            }
        }

        List<List<Double>> questionEmbeddings = aiServiceClient.generateEmbeddings(
            List.of(trimmedQuestion)
        );
        List<Double> questionEmbedding = questionEmbeddings.get(0);
        String embeddingString = Arrays.toString(questionEmbedding.toArray());
        List<CodeChunk> similarChunks = codeChunkRepository.findSimilarChunks(
            projectId, embeddingString, properties.getRetrieval().getChatTopK());
        List<Map<String, Object>> context = similarChunks.stream()
                .map(chunk -> {
                    Map<String, Object> ctx = new HashMap<>();
                    ctx.put("content", truncate(chunk.getContent(),
                            properties.getRetrieval().getChatContextMaxChars()));
                    ctx.put("start_line", chunk.getStartLine());
                    ctx.put("end_line", chunk.getEndLine());
                    String filePath = projectFileRepository.findById(chunk.getFileId())
                            .map(ProjectFile::getPath).orElse("unknown");
                    ctx.put("file_path", filePath);
                    return ctx;
                })
                .collect(Collectors.toList());

        conversation.setUpdatedAt(LocalDateTime.now());
        conversationRepository.save(conversation);

        return new PreparedChat(conversation.getId(), null, projectId,
                trimmedQuestion, context, history, regenerate, 0);
    }

    /**
     * Persist the answer. {@code asConversation} selects whether {@code
     * targetId} is a conversation_id or a legacy session_id. On a regenerate
     * the trailing assistant message is overwritten in place (one answer per
     * question in history); when no such message exists a new row is inserted.
     */
    private ChatMessage persistAssistantMessage(
            UUID targetId, boolean asConversation,
            String content, List<Map<String, Object>> context, boolean regenerate) {
        ChatMessage assistantMessage = null;
        if (regenerate) {
            List<ChatMessage> messages = asConversation
                    ? chatMessageRepository.findByConversationIdOrderByCreatedAtAsc(targetId)
                    : chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(targetId);
            if (!messages.isEmpty()) {
                ChatMessage last = messages.get(messages.size() - 1);
                if ("assistant".equals(last.getRole())) {
                    assistantMessage = last;
                }
            }
        }
        if (assistantMessage == null) {
            assistantMessage = new ChatMessage();
            if (asConversation) {
                assistantMessage.setConversationId(targetId);
            } else {
                assistantMessage.setSessionId(targetId);
            }
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
        UUID targetId = prepared.conversationId() != null
                ? prepared.conversationId() : prepared.sessionId();
        boolean asConversation = prepared.conversationId() != null;
        UUID projectId = prepared.projectId();
        StringBuilder content = new StringBuilder();
        AtomicBoolean persisted = new AtomicBoolean(false);
        AtomicReference<Disposable> subscriptionRef = new AtomicReference<>();

        // Persist whatever we have, exactly once, on any exit path. A DB
        // failure here must never escape into the stream or to Spring.
        Runnable persistPartial = () -> {
            if (persisted.compareAndSet(false, true) && content.length() > 0) {
                try {
                    persistAssistantMessage(targetId, asConversation, content.toString(),
                            prepared.context(), prepared.regenerate());
                } catch (RuntimeException e) {
                    log.error("Failed to persist partial answer for session {}", targetId, e);
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
            log.debug("Chat meta event undeliverable session={}: {}", targetId, e.toString());
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
                                            targetId, e.toString());
                                    Disposable sub = subscriptionRef.get();
                                    if (sub != null) {
                                        sub.dispose();
                                    }
                                    persistPartial.run();
                                    completeQuietly(emitter);
                                }
                            } else if ("error".equals(type)) {
                                log.warn("AI service reported a stream error for session {} (code={}): {}",
                                        targetId,
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
                                    targetId, projectId, payload.get("code"), error);
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
                                            targetId, asConversation, content.toString(),
                                            prepared.context(), prepared.regenerate());
                                    messageId = String.valueOf(saved.getId());
                                } catch (RuntimeException e) {
                                    // Still send `done`: losing history must not
                                    // break the live stream.
                                    log.error("Failed to persist answer for session {}", targetId, e);
                                }
                            }
                            try {
                                int usedChars = calculateUsedChars(
                                        prepared.question(), prepared.history(), prepared.context());
                                int budgetChars = properties.getChat().getChatContextBudgetChars();
                                emitter.send(SseEmitter.event()
                                        .name("done")
                                        .data(Map.of("messageId", messageId,
                                                "context", Map.of("usedChars", usedChars,
                                                "budgetChars", budgetChars))));
                            } catch (IOException | RuntimeException e) {
                                // Client is gone or the emitter already closed;
                                // persistence already happened.
                                log.debug("Chat done event undeliverable session={}: {}",
                                        targetId, e.toString());
                            }
                            completeQuietly(emitter);
                            // Trigger background summarization after the answer is persisted.
                            if (asConversation) {
                                CompletableFuture.runAsync(() -> maybeSummarize(targetId));
                            }
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

    /**
     * Return the "live" messages from {@code allMessages} that are NOT already
     * covered by the conversation's summary. Messages up to and including
     * {@code summary_through_message_id} are folded into the summary; only
     * messages after it are sent as live history.
     */
    private List<ChatMessage> liveHistory(Conversation conversation, List<ChatMessage> allMessages) {
        if (conversation.getSummary() == null || conversation.getSummaryThroughMessageId() == null) {
            return allMessages;
        }
        int cutoffIndex = -1;
        for (int i = 0; i < allMessages.size(); i++) {
            if (allMessages.get(i).getId().equals(conversation.getSummaryThroughMessageId())) {
                cutoffIndex = i;
                break;
            }
        }
        if (cutoffIndex >= 0) {
            return allMessages.subList(cutoffIndex + 1, allMessages.size());
        }
        return allMessages;
    }

    /** Calculate estimated characters in the assembled prompt. */
    private static int calculateUsedChars(
            String trimmedQuestion,
            List<Map<String, String>> history,
            List<Map<String, Object>> context) {
        int total = trimmedQuestion.length();
        for (Map<String, String> msg : history) {
            String content = msg.get("content");
            if (content != null) {
                total += content.length();
            }
        }
        for (Map<String, Object> chunk : context) {
            @SuppressWarnings("unchecked")
            String content = (String) chunk.get("content");
            if (content != null) {
                total += content.length();
            }
        }
        return total;
    }

    private final Map<UUID, Boolean> summarizing = new ConcurrentHashMap<>();

    /**
     * After a response is saved, check if summarization is needed and trigger it
     * in the background. Summarization conditions:
     *   1. Messages older than the recent window and not covered by summary
      *      exceed {@code chatHistorySummaryThreshold} (default ~6), OR
     *   2. Estimated prompt size (chars) exceeds 70% of {@code chatContextBudgetChars}.
     * If it runs, it never delays the answer: it runs async after the answer
     * is persisted. If it throws, we log and continue; chat must never fail
     * because of summarization.
     */
    private void maybeSummarize(UUID conversationId) {
        // Guard against two summaries running at once for the same conversation.
        if (summarizing.putIfAbsent(conversationId, true) != null) {
            return; // already summarizing
        }
        try {
            Conversation conversation = conversationRepository.findById(conversationId)
                    .orElse(null);
            if (conversation == null) return;

            int budgetChars = properties.getChat().getChatContextBudgetChars();
            int threshold = properties.getChat().getChatHistorySummaryThreshold();

            // Get all messages for the conversation
            List<ChatMessage> allMessages =
                    chatMessageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);

            // Determine messages not covered by the summary
            List<ChatMessage> uncoveredMessages;
            List<ChatMessage> liveMessages;
            if (conversation.getSummary() == null
                    || conversation.getSummaryThroughMessageId() == null) {
                uncoveredMessages = allMessages;
                liveMessages = allMessages;
            } else {
                // Find the index of the message that's the cutoff for the summary
                int cutoffIndex = -1;
                for (int i = 0; i < allMessages.size(); i++) {
                    if (allMessages.get(i).getId()
                            .equals(conversation.getSummaryThroughMessageId())) {
                        cutoffIndex = i;
                        break;
                    }
                }
                if (cutoffIndex >= 0) {
                    uncoveredMessages = allMessages.subList(cutoffIndex + 1,
                            allMessages.size());
                    liveMessages = allMessages.subList(cutoffIndex + 1, allMessages.size());
                } else {
                    uncoveredMessages = allMessages;
                    liveMessages = allMessages;
                }
            }

            // Count messages older than the recent window and not covered by summary
            int historySize = Math.max(0, properties.getChat().getHistoryMaxMessages());
            int recentStart = Math.max(0, allMessages.size() - historySize);
            int oldNotCoveredCount = 0;
            for (int i = 0; i < recentStart; i++) {
                ChatMessage msg = allMessages.get(i);
                boolean isCovered = conversation.getSummary() != null
                        && conversation.getSummaryThroughMessageId() != null
                        && msg.getId().equals(conversation.getSummaryThroughMessageId());
                // A message is "old and not covered" if it's before the recent window
                // and not the cutoff message itself.
                if (i < recentStart && !msg.getId().equals(conversation.getSummaryThroughMessageId())) {
                    oldNotCoveredCount++;
                }
            }

            boolean trigger1 = oldNotCoveredCount > threshold;

            // Condition 2: estimated prompt size > 70% of budget
            // Approximate by summing all message content lengths
            int totalMessageChars = 0;
            for (ChatMessage msg : allMessages) {
                totalMessageChars += msg.getContent() != null ? msg.getContent().length() : 0;
            }
            boolean trigger2 = totalMessageChars > (int) (0.7 * budgetChars);

            if (!trigger1 && !trigger2) {
                summarizing.remove(conversationId);
                return;
            }

            // Build the history for the summarization call:
            // previous summary (as system block) + live messages not covered by summary
            List<Map<String, String>> sumHistory = new ArrayList<>();
            if (conversation.getSummary() != null && !conversation.getSummary().isBlank()) {
                sumHistory.add(Map.of("role", "system",
                        "content", "Earlier discussion summary: " + conversation.getSummary()));
            }
            for (ChatMessage msg : liveMessages) {
                String content = msg.getContent();
                sumHistory.add(Map.of("role", msg.getRole(),
                        "content", content.length() > 2000 ? content.substring(0, 2000) : content));
            }

            // Cheap, non-streaming model call to condense the conversation.
            // The question field carries the instruction; the history carries the conversation.
            String instruction = "Condense this conversation into at most 150 words. "
                    + "Keep: the user's goal, key decisions, important file paths and symbol "
                    + "names, and any open question. Drop pleasantries and repeated content.";
            try {
                Map<String, Object> summaryResp = aiServiceClient.chat(
                        instruction, new ArrayList<>(), sumHistory);
                String summaryText = (String) summaryResp.get("answer");
                if (summaryText == null || summaryText.isBlank()) {
                    log.warn("Summarization returned empty answer for conversation {}", conversationId);
                    summarizing.remove(conversationId);
                    return;
                }

                // Store the new summary and the id of the last message it covers.
                // The summary covers all live messages up to the last one.
                UUID lastMessageId = liveMessages.isEmpty() ? null : liveMessages.get(liveMessages.size() - 1).getId();
                conversation.setSummary(summaryText);
                conversation.setSummaryThroughMessageId(lastMessageId);
                conversationRepository.save(conversation);
                log.info("Summarized conversation {} into {} chars, covering message {}",
                        conversationId, summaryText.length(), lastMessageId);
            } catch (Exception e) {
                log.error("Summarization failed for conversation {}: {}", conversationId, e.getMessage());
                // Chat must never fail because of summarization.
            }
        } finally {
            summarizing.remove(conversationId);
        }
    }

    /** Truncate to {@code maxChars} without ever returning more than asked. */
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
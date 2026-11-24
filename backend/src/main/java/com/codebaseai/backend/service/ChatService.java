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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import reactor.core.Disposable;
import reactor.core.scheduler.Schedulers;

import com.codebaseai.backend.dto.ChatMessageResponse;
import com.codebaseai.backend.config.AppProperties;
import com.codebaseai.backend.model.ChatMessage;
import com.codebaseai.backend.model.CodeChunk;
import com.codebaseai.backend.model.Conversation;
import com.codebaseai.backend.model.Project;
import com.codebaseai.backend.model.ProjectFile;
import com.codebaseai.backend.repository.ChatMessageRepository;
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
    
    private final ConversationRepository conversationRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final CodeChunkRepository codeChunkRepository;
    private final ProjectFileRepository projectFileRepository;
    private final ProjectRepository projectRepository;
    private final AiServiceClient aiServiceClient;
    private final ObjectMapper objectMapper;
    private final AppProperties properties;
    private final ChatTurnService turns;
    private final JdbcTemplate jdbc;
    
    /**
     * Result of the blocking prep phase: the conversation the messages belong
     * to, the question, the retrieved context and the recent conversation
     * history (for LLM continuity). The flag says whether this run is a
     * re-ask that must replace the trailing answer.
     */
    public record PreparedChat(
        UUID conversationId,
        UUID projectId,
        String question,
        List<Map<String, Object>> context,
        List<Map<String, String>> history,
        boolean regenerate,
        int usedChars,
        String chatInstructions, ChatTurnService.Turn turn) {}

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
                prepared.question(), prepared.context(), prepared.history(),
                prepared.chatInstructions());
        String answer = (String) aiResponse.get("answer");

        persistAssistantMessage(
                prepared.conversationId(), answer != null ? answer : "",
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
            List.of(retrievalQuestion(trimmedQuestion, liveMessages))
        );
        List<Double> questionEmbedding = questionEmbeddings.get(0);
        String embeddingString = Arrays.toString(questionEmbedding.toArray());
        List<CodeChunk> similarChunks = codeChunkRepository.findSimilarChunks(
            projectId, project.getActiveGenerationId(), embeddingString, properties.getRetrieval().getChatTopK());
        List<Map<String, Object>> context = similarChunks.stream()
                .map(chunk -> {
                    Map<String, Object> ctx = new HashMap<>();
                    String excerpt = truncate(chunk.getContent(), properties.getRetrieval().getChatContextMaxChars());
                    ctx.put("content", excerpt);
                    ctx.put("start_line", chunk.getStartLine());
                    int excerptLines = Math.max(1, (int) excerpt.lines().count());
                    ctx.put("end_line", Math.min(chunk.getEndLine(), chunk.getStartLine() + excerptLines - 1));
                    String filePath = projectFileRepository.findById(chunk.getFileId())
                            .map(ProjectFile::getPath).orElse("unknown");
                    ctx.put("file_path", filePath);
                    ctx.put("file_id", chunk.getFileId());
                    return ctx;
                })
                .collect(Collectors.toList());

        if (persistUserMessage) {
            conversation.setUpdatedAt(LocalDateTime.now());
            conversationRepository.save(conversation);
        }

        return new PreparedChat(conversation.getId(), projectId,
                trimmedQuestion, context, history, regenerate, 0,
                project.getChatInstructions(), null);
    }

    /**
     * Persist the answer for a conversation. On a regenerate the trailing
     * assistant message is overwritten in place (one answer per question in
     * history); when no such message exists a new row is inserted.
     */
    private ChatMessage persistAssistantMessage(
            UUID conversationId,
            String content, List<Map<String, Object>> context, boolean regenerate) {
        ChatMessage assistantMessage = null;
        if (regenerate) {
            List<ChatMessage> messages =
                    chatMessageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
            if (!messages.isEmpty()) {
                ChatMessage last = messages.get(messages.size() - 1);
                if ("assistant".equals(last.getRole())) {
                    assistantMessage = last;
                }
            }
        }
        if (assistantMessage == null) {
            assistantMessage = new ChatMessage();
            assistantMessage.setConversationId(conversationId);
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

    /** Accept the question in a short transaction before making provider calls. */
    public PreparedChat prepareDurableChat(UUID projectId, UUID userId, String question,
            UUID conversationId, UUID requestId, UUID replyToMessageId, boolean regenerate) {
        ChatTurnService.Turn turn = turns.accept(projectId, userId, conversationId, requestId,
                replyToMessageId, "chat", question, regenerate);
        if (turn.completed()) {
            return new PreparedChat(turn.conversationId(), projectId, question, List.of(), List.of(),
                    turn.regenerate(), 0, null, turn);
        }
        try {
            PreparedChat prepared = prepareChat(projectId, userId, question, false,
                    turn.regenerate(), turn.conversationId());
            return new PreparedChat(prepared.conversationId(), projectId, prepared.question(),
                    prepared.context(), prepared.history(), prepared.regenerate(), prepared.usedChars(),
                    prepared.chatInstructions(), turn);
        } catch (RuntimeException e) {
            turns.interrupt(turn);
            throw e;
        }
    }

    public ChatMessageResponse sendDurableMessage(UUID projectId, UUID userId, String question,
            UUID conversationId, UUID requestId, UUID replyToMessageId, boolean regenerate) {
        PreparedChat prepared = prepareDurableChat(projectId, userId, question, conversationId,
                requestId, replyToMessageId, regenerate);
        ChatTurnService.Turn turn = prepared.turn();
        try {
            if (turn.completed()) return objectMapper.readValue(turn.resultData(), ChatMessageResponse.class);
            Map<String, Object> response = aiServiceClient.chat(prepared.question(), prepared.context(),
                    prepared.history(), prepared.chatInstructions());
            String answer = (String) response.get("answer");
            if (answer == null || answer.isBlank()) throw new IllegalStateException("Empty answer");
            ChatMessageResponse result = new ChatMessageResponse(answer, prepared.context(),
                    calculateUsedChars(prepared.question(), prepared.history(), prepared.context()),
                    properties.getChat().getChatContextBudgetChars());
            turns.complete(turn, answer, objectMapper.writeValueAsString(prepared.context()), null,
                    objectMapper.writeValueAsString(result));
            CompletableFuture.runAsync(() -> maybeSummarize(turn.conversationId()));
            return result;
        } catch (Exception e) {
            if (!turn.completed()) turns.interrupt(turn);
            if (e instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Could not save chat answer", e);
        }
    }

    public void streamMessage(PreparedChat prepared, SseEmitter emitter) {
        ChatTurnService.Turn turn = prepared.turn();
        if (turn.completed()) {
            try {
                ChatMessageResponse result = objectMapper.readValue(turn.resultData(), ChatMessageResponse.class);
                emitter.send(SseEmitter.event().name("meta").data(Map.of("citations", result.getCitations(),
                        "requestId", turn.requestId(), "userMessageId", turn.userMessageId())));
                emitter.send(SseEmitter.event().name("token").data(Map.of("t", result.getAnswer())));
                emitter.send(SseEmitter.event().name("done").data(Map.of("messageId", turn.assistantMessageId().toString(),
                        "context", Map.of("usedChars", result.getUsedChars(), "budgetChars", result.getBudgetChars()))));
            } catch (IOException | RuntimeException e) {
                log.debug("Could not replay chat response", e);
            }
            completeQuietly(emitter);
            return;
        }
        StringBuilder answer = new StringBuilder();
        AtomicBoolean terminal = new AtomicBoolean();
        AtomicBoolean upstreamDone = new AtomicBoolean();
        AtomicReference<Disposable> subscription = new AtomicReference<>();
        Runnable interrupt = () -> {
            if (terminal.compareAndSet(false, true)) {
                try { turns.interrupt(turn); } catch (RuntimeException e) { log.warn("Could not interrupt chat", e); }
            }
            Disposable active = subscription.get();
            if (active != null) active.dispose();
        };
        emitter.onTimeout(interrupt);
        emitter.onError(error -> interrupt.run());
        emitter.onCompletion(interrupt);
        try {
            emitter.send(SseEmitter.event().name("meta").data(Map.of("citations", prepared.context(),
                    "requestId", turn.requestId(), "userMessageId", turn.userMessageId())));
        } catch (IOException | RuntimeException e) {
            interrupt.run();
            completeQuietly(emitter);
            return;
        }
        Disposable active = aiServiceClient.chatStream(prepared.question(), prepared.context(), prepared.history(),
                prepared.chatInstructions()).publishOn(Schedulers.boundedElastic()).subscribe(event -> {
            if (terminal.get()) return;
            String type = event.event() == null ? "" : event.event();
            String data = event.data() == null ? "" : event.data();
            if ("done".equals(type)) {
                upstreamDone.set(true);
            } else if ("token".equals(type)) {
                String token = extractToken(data);
                answer.append(token);
                try { emitter.send(SseEmitter.event().name("token").data(Map.of("t", token))); }
                catch (IOException | RuntimeException e) { interrupt.run(); }
            } else if ("error".equals(type) && terminal.compareAndSet(false, true)) {
                turns.interrupt(turn);
                sendError(emitter, errorPayload(data, "The AI service failed while generating the answer.", "upstream_error"));
                completeQuietly(emitter);
            }
        }, error -> {
            if (terminal.compareAndSet(false, true)) {
                turns.interrupt(turn);
                sendError(emitter, AiStreamErrors.describe(error));
                completeQuietly(emitter);
            }
        }, () -> {
            if (!terminal.compareAndSet(false, true)) return;
            if (!upstreamDone.get() || answer.length() == 0) {
                turns.interrupt(turn);
                sendError(emitter, Map.of("message", "The answer ended before completing.", "code", "interrupted"));
                completeQuietly(emitter);
                return;
            }
            try {
                int usedChars = calculateUsedChars(prepared.question(), prepared.history(), prepared.context());
                int budgetChars = properties.getChat().getChatContextBudgetChars();
                ChatMessageResponse result = new ChatMessageResponse(answer.toString(), prepared.context(), usedChars, budgetChars);
                UUID messageId = turns.complete(turn, answer.toString(), objectMapper.writeValueAsString(prepared.context()),
                        null, objectMapper.writeValueAsString(result));
                CompletableFuture.runAsync(() -> maybeSummarize(turn.conversationId()));
                emitter.send(SseEmitter.event().name("done").data(Map.of("messageId", messageId.toString(),
                        "context", Map.of("usedChars", usedChars, "budgetChars", budgetChars))));
            } catch (Exception e) {
                log.error("Could not save chat answer", e);
                try { turns.interrupt(turn); } catch (RuntimeException ignored) { }
                sendError(emitter, Map.of("message", "The answer could not be saved.", "code", "persistence_error"));
            }
            completeQuietly(emitter);
        });
        subscription.set(active);
        if (terminal.get()) active.dispose();
    }

    private String extractToken(String data) {
        try {
            return objectMapper.readTree(data).path("t").asText("");
        } catch (JsonProcessingException e) {
            return "";
        }
    }

    static String retrievalQuestion(String question, List<ChatMessage> history) {
        String lower = question.toLowerCase(java.util.Locale.ROOT).trim();
        boolean followUp = lower.matches("^(it|its|this|that|these|those|they|them|their)\\b.*")
                || lower.matches(".*\\b(it|its|this|that|these|those|they|them|their)\\b.*")
                && lower.split("\\s+").length <= 12;
        if (!followUp) return question;
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessage message = history.get(i);
            if ("user".equals(message.getRole()) && message.getContent() != null
                    && !message.getContent().isBlank()) {
                return question + "\nPrevious question: " + truncate(message.getContent(), 1000);
            }
        }
        return question;
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
                        instruction, new ArrayList<>(), sumHistory, null);
                String summaryText = (String) summaryResp.get("answer");
                if (summaryText == null || summaryText.isBlank()) {
                    log.warn("Summarization returned empty answer for conversation {}", conversationId);
                    summarizing.remove(conversationId);
                    return;
                }

                // Store the new summary and the id of the last message it covers.
                // The summary covers all live messages up to the last one.
                UUID lastMessageId = liveMessages.isEmpty() ? null : liveMessages.get(liveMessages.size() - 1).getId();
                int updated = jdbc.update(
                        "UPDATE conversations SET summary = ?, summary_through_message_id = ? WHERE id = ? AND history_revision = ?",
                        summaryText, lastMessageId, conversationId, conversation.getHistoryRevision());
                if (updated == 1) {
                    log.info("Summarized conversation {} into {} chars, covering message {}",
                            conversationId, summaryText.length(), lastMessageId);
                }
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

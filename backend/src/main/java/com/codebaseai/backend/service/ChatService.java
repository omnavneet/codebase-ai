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
     * and the recent conversation history (for LLM continuity).
     */
    public record PreparedChat(
            ChatSession session,
            String question,
            List<Map<String, Object>> context,
            List<Map<String, String>> history) {}

    @Transactional
    public ChatMessageResponse sendMessage(
            UUID sessionId, UUID userId, String question, boolean userMessagePersisted) {
        PreparedChat prepared = prepareChat(sessionId, userId, question, !userMessagePersisted);

        Map<String, Object> aiResponse = aiServiceClient.chat(
                prepared.question(), prepared.context(), prepared.history());
        String answer = (String) aiResponse.get("answer");

        persistAssistantMessage(prepared.session(), answer != null ? answer : "", prepared.context());

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
        return prepareChat(sessionId, userId, question, true);
    }

    /**
     * Variant that lets the sync endpoint skip persisting the user message.
     * The streaming path persists the user message in this prep phase, so the
     * sync endpoint — used as a fallback when streaming fails — must not save
     * the question again, otherwise it appears duplicated in the conversation.
     */
    public PreparedChat prepareChat(
            UUID sessionId, UUID userId, String question, boolean persistUserMessage) {
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
        // Fallback path (persistUserMessage=false): the failed stream already
        // persisted this exact question, so it is the trailing entry — drop it
        // to avoid prompting it twice.
        if (!recentMessages.isEmpty()) {
            ChatMessage last = recentMessages.get(recentMessages.size() - 1);
            if ("user".equals(last.getRole()) && trimmedQuestion.equals(last.getContent())) {
                recentMessages = recentMessages.subList(0, recentMessages.size() - 1);
            }
        }
        // Last 10 messages (5 question/answer turns), each truncated to bound
        // the prompt size sent to the LLM.
        int from = Math.max(0, recentMessages.size() - 10);
        List<Map<String, String>> history = new ArrayList<>();
        for (ChatMessage message : recentMessages.subList(from, recentMessages.size())) {
            String content = message.getContent();
            history.add(Map.of(
                    "role", message.getRole(),
                    "content", content.length() > 2000 ? content.substring(0, 2000) : content));
        }

        if (persistUserMessage) {
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
            5
        );

        List<Map<String, Object>> context = similarChunks.stream()
                .map(chunk -> {
                    Map<String, Object> ctx = new HashMap<>();
                    ctx.put("content", chunk.getContent());
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

        return new PreparedChat(session, trimmedQuestion, context, history);
    }

    private ChatMessage persistAssistantMessage(ChatSession session, String content, List<Map<String, Object>> context) {
        ChatMessage assistantMessage = new ChatMessage();
        assistantMessage.setSessionId(session.getId());
        assistantMessage.setRole("assistant");
        assistantMessage.setContent(content);

        try {
            assistantMessage.setCitations(objectMapper.writeValueAsString(context));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize citations", e);
        }

        chatMessageRepository.save(assistantMessage);
        return assistantMessage;
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

        // Persist whatever we have, exactly once, on any exit path.
        Runnable persistPartial = () -> {
            if (persisted.compareAndSet(false, true) && content.length() > 0) {
                persistAssistantMessage(session, content.toString(), prepared.context());
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
                                    emitter.complete();
                                }
                            } else if ("error".equals(type)) {
                                log.warn("AI service reported a stream error for session {}", session.getId());
                                persistPartial.run();
                                try {
                                    emitter.send(SseEmitter.event()
                                            .name("error")
                                            .data(Map.of("message", "The AI service failed while generating the answer.")));
                                } catch (IOException ignored) {
                                    // Client is gone.
                                }
                                emitter.complete();
                            }
                            // The Python `done` event is ignored: this service owns the
                            // terminal `done`, sent after the answer is persisted.
                        },
                        error -> {
                            log.error("Chat stream failed for session {}", session.getId(), error);
                            persistPartial.run();
                            try {
                                emitter.send(SseEmitter.event()
                                        .name("error")
                                        .data(Map.of("message", "The AI service failed while generating the answer.")));
                            } catch (IOException ignored) {
                                // Client is gone.
                            }
                            emitter.complete();
                        },
                        () -> {
                            // Own the persisted flag so the onCompletion safety
                            // net below cannot persist the same answer twice.
                            String messageId = "";
                            if (persisted.compareAndSet(false, true) && content.length() > 0) {
                                ChatMessage saved = persistAssistantMessage(
                                        session, content.toString(), prepared.context());
                                messageId = String.valueOf(saved.getId());
                            }
                            try {
                                emitter.send(SseEmitter.event()
                                        .name("done")
                                        .data(Map.of("messageId", messageId)));
                            } catch (IOException ignored) {
                                // Client is gone; persistence already happened.
                            }
                            emitter.complete();
                        }));
    }

    private String extractToken(String data) {
        try {
            return objectMapper.readTree(data).path("t").asText("");
        } catch (JsonProcessingException e) {
            return "";
        }
    }
}
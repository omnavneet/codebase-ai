package com.codebaseai.backend.service;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import reactor.core.Disposable;
import reactor.core.scheduler.Schedulers;

import com.codebaseai.backend.config.AppProperties;
import com.codebaseai.backend.model.ChatMessage;
import com.codebaseai.backend.model.Conversation;
import com.codebaseai.backend.model.Project;
import com.codebaseai.backend.repository.ChatMessageRepository;
import com.codebaseai.backend.repository.ConversationRepository;
import com.codebaseai.backend.repository.ProjectRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Relays the AI service's streaming agent endpoints to the browser.
 *
 * <p>The agent endpoints (investigate / explain / debug / docstrings) run a
 * bounded loop that emits {@code step}/{@code status} progress events and a
 * terminal {@code done}/{@code error}. This service forwards each event
 * verbatim so the UI shows live progress instead of a spinner, and disposes
 * the upstream subscription when the client disconnects or the emitter times
 * out — so a cancelled run stops the backend work rather than only hiding the
 * UI.
 *
 * <p>Successful investigate runs are persisted to a project conversation (the
 * user question plus the final answer), so deep investigate answers survive a
 * closed tab. Errored or cancelled runs persist nothing; the existing SSE
 * contract is untouched.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentStreamService {

    private final AiServiceClient aiServiceClient;
    private final ProjectRepository projectRepository;
    private final ConversationRepository conversationRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final AppProperties properties;

    /**
     * Verify the project exists, is owned by the caller and is ready, then
     * open an SSE relay to the given AI-service agent stream endpoint.
     *
     * <p>Once the stream has started every failure becomes a terminal SSE
     * {@code error} event carrying a user-safe {@code message} and a
     * {@code code}; the full exception is logged server-side with the project
     * id and a request id. Nothing is ever allowed to escape to Spring after
     * the response is committed.
     *
     * @param aiPath   AI-service path, e.g. {@code /agent/investigate/stream}
     * @param request  JSON body to POST to the AI service
     */
    public void stream(UUID projectId, UUID userId, String aiPath, Map<String, Object> request, SseEmitter emitter,
            String question, UUID conversationId) {
        requireReadyProject(projectId, userId);

        // Correlates every log line for this stream with what the client saw.
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        AtomicReference<Disposable> subscriptionRef = new AtomicReference<>();

        emitter.onTimeout(() -> {
            log.debug("Agent stream timed out requestId={} project={} path={}",
                    requestId, projectId, aiPath);
            disposeQuietly(subscriptionRef.get());
        });
        emitter.onError(t -> {
            // Client disconnect/abort: debug level, no stack trace.
            log.debug("Agent stream aborted by client requestId={} project={} path={}: {}",
                    requestId, projectId, aiPath, t.toString());
            disposeQuietly(subscriptionRef.get());
        });

        Disposable subscription = aiServiceClient
                .streamAgent(aiPath, request)
                .publishOn(Schedulers.boundedElastic())
                .subscribe(
                        event -> relayEvent(event, emitter, subscriptionRef, requestId, projectId, aiPath,
                                question, conversationId),
                        error -> failEmitter(error, emitter, requestId, projectId, aiPath),
                        () -> completeQuietly(emitter, requestId));

        subscriptionRef.set(subscription);
        emitter.onCompletion(() -> disposeQuietly(subscriptionRef.get()));
    }

    /**
     * Plain relay without persistence, for the explain/debug streams.
     */
    public void stream(UUID projectId, UUID userId, String aiPath, Map<String, Object> request, SseEmitter emitter) {
        stream(projectId, userId, aiPath, request, emitter, null, null);
    }

    /** Forward one upstream event; a disconnect only stops the upstream run. */
    private void relayEvent(org.springframework.http.codec.ServerSentEvent<String> event,
            SseEmitter emitter, AtomicReference<Disposable> subscriptionRef,
            String requestId, UUID projectId, String aiPath,
            String question, UUID conversationId) {
        String type = event.event() == null ? "" : event.event();
        String data = event.data() == null ? "" : event.data();
        try {
            emitter.send(SseEmitter.event()
                    .name(type.isEmpty() ? "step" : type)
                    .data(data));
        } catch (IOException e) {
            // Client disconnected mid-run (AsyncRequestNotUsableException is
            // an IOException subclass): stop the upstream work so a cancelled
            // investigation really stops. Debug only, no stack trace.
            log.debug("Agent stream client disconnected requestId={} project={} path={}: {}",
                    requestId, projectId, aiPath, e.toString());
            disposeQuietly(subscriptionRef.get());
        } catch (RuntimeException e) {
            // Emitter already completed/errored - never let this reach Spring
            // after the response is committed.
            log.debug("Agent stream send failed requestId={} project={} path={}: {}",
                    requestId, projectId, aiPath, e.toString());
            disposeQuietly(subscriptionRef.get());
        }
        if ("done".equals(type) && question != null && !question.isBlank()) {
            persistInvestigation(projectId, question.trim(), data, conversationId, requestId);
        }
    }

    /**
     * Persist a successful investigate run (user question + final answer) so
     * the answer survives a closed tab. Errored or cancelled runs never reach
     * `done`, so they persist nothing by construction. Never throws into the
     * stream: a persistence failure is logged, not re-raised.
     */
    private void persistInvestigation(UUID projectId, String question, String doneData,
            UUID conversationId, String requestId) {
        try {
            Conversation conversation = conversationId != null
                    ? conversationRepository.findByIdAndProjectId(conversationId, projectId).orElse(null)
                    : null;
            if (conversation == null) {
                conversation = conversationRepository.findByProjectIdOrderByUpdatedAtDesc(projectId).stream()
                        .findFirst()
                        .orElseGet(() -> {
                            Conversation created = new Conversation();
                            created.setProjectId(projectId);
                            created.setTitle("New Chat");
                            return conversationRepository.save(created);
                        });
            }
            if (conversationId != null && !conversation.getId().equals(conversationId)) {
                log.warn("Investigate run referenced conversation {} of another project; persisted to {} instead",
                        conversationId, conversation.getId());
            }
            String answer = extractAnswer(doneData);
            if (answer.isEmpty()) {
                log.debug("Investigate run produced no answer; nothing persisted requestId={}", requestId);
                return;
            }
            ChatMessage userMessage = new ChatMessage();
            userMessage.setConversationId(conversation.getId());
            userMessage.setRole("user");
            userMessage.setContent(question);
            chatMessageRepository.save(userMessage);

            ChatMessage assistantMessage = new ChatMessage();
            assistantMessage.setConversationId(conversation.getId());
            assistantMessage.setRole("assistant");
            assistantMessage.setContent(answer);
            chatMessageRepository.save(assistantMessage);

            String currentTitle = conversation.getTitle();
            if (currentTitle == null || currentTitle.isBlank() || "New Chat".equals(currentTitle)) {
                String firstLine = question.lines().findFirst().orElse(question).trim();
                String title = firstLine.substring(0, Math.min(40, firstLine.length()));
                if (!title.isEmpty() && Character.isHighSurrogate(title.charAt(title.length() - 1))) {
                    title = title.substring(0, title.length() - 1);
                }
                conversation.setTitle(title);
            }
            conversation.setUpdatedAt(java.time.LocalDateTime.now());
            conversationRepository.save(conversation);
            log.debug("Investigate run persisted to conversation {} requestId={}", conversation.getId(), requestId);
        } catch (RuntimeException e) {
            log.error("Failed to persist investigate run requestId={}", requestId, e);
        }
    }

    /** Read the final `answer` out of a `done` payload; "" when absent. */
    private String extractAnswer(String doneData) {
        if (doneData == null || doneData.isBlank()) {
            return "";
        }
        if (!doneData.trim().startsWith("{")) {
            return doneData.trim();
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(doneData).path("answer").asText("");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "";
        }
    }

    /** Terminal SSE error event with a user-safe reason and code, then complete. */
    private void failEmitter(Throwable error, SseEmitter emitter,
            String requestId, UUID projectId, String aiPath) {
        Map<String, String> payload = AiStreamErrors.describe(error);
        log.error("Agent stream failed requestId={} project={} path={} code={}",
                requestId, projectId, aiPath, payload.get("code"), error);
        try {
            emitter.send(SseEmitter.event().name("error").data(payload));
        } catch (IOException e) {
            log.debug("Agent stream error event undeliverable requestId={}: {}",
                    requestId, e.toString());
        } catch (RuntimeException e) {
            log.debug("Agent stream error event failed requestId={}: {}",
                    requestId, e.toString());
        }
        completeQuietly(emitter, requestId);
    }

    private void completeQuietly(SseEmitter emitter, String requestId) {
        try {
            emitter.complete();
        } catch (RuntimeException e) {
            // Already completed by a timeout/error callback.
            log.debug("Agent stream already closed requestId={}: {}", requestId, e.toString());
        }
    }

    private static void disposeQuietly(Disposable subscription) {
        if (subscription != null) {
            subscription.dispose();
        }
    }

    private void requireReadyProject(UUID projectId, UUID userId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
        if (!project.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }
        if (!"ready".equals(project.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Project is not ready for investigation");
        }
    }
}

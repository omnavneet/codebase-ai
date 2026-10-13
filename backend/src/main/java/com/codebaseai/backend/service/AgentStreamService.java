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
import com.codebaseai.backend.model.Project;
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
 * <p>These streams are not persisted (unlike chat): the agent result is shown
 * inline and, for investigations, saved by the chat flow when applicable.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentStreamService {

    private final AiServiceClient aiServiceClient;
    private final ProjectRepository projectRepository;
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
    public void stream(UUID projectId, UUID userId, String aiPath, Map<String, Object> request, SseEmitter emitter) {
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
                        event -> relayEvent(event, emitter, subscriptionRef, requestId, projectId, aiPath),
                        error -> failEmitter(error, emitter, requestId, projectId, aiPath),
                        () -> completeQuietly(emitter, requestId));

        subscriptionRef.set(subscription);
        emitter.onCompletion(() -> disposeQuietly(subscriptionRef.get()));
    }

    /** Forward one upstream event; a disconnect only stops the upstream run. */
    private void relayEvent(org.springframework.http.codec.ServerSentEvent<String> event,
            SseEmitter emitter, AtomicReference<Disposable> subscriptionRef,
            String requestId, UUID projectId, String aiPath) {
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

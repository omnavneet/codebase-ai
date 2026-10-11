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
     * @param aiPath   AI-service path, e.g. {@code /agent/investigate/stream}
     * @param request  JSON body to POST to the AI service
     */
    public void stream(UUID projectId, UUID userId, String aiPath, Map<String, Object> request, SseEmitter emitter) {
        requireReadyProject(projectId, userId);

        AtomicReference<Disposable> subscriptionRef = new AtomicReference<>();

        emitter.onCompletion(subscriptionRef.get() != null ? subscriptionRef.get()::dispose : () -> {});
        emitter.onTimeout(() -> {
            Disposable sub = subscriptionRef.get();
            if (sub != null) {
                sub.dispose();
            }
        });
        emitter.onError(t -> {
            Disposable sub = subscriptionRef.get();
            if (sub != null) {
                sub.dispose();
            }
        });

        subscriptionRef.set(aiServiceClient
                .streamAgent(aiPath, request)
                .publishOn(Schedulers.boundedElastic())
                .subscribe(
                        event -> {
                            String type = event.event() == null ? "" : event.event();
                            String data = event.data() == null ? "" : event.data();
                            try {
                                emitter.send(SseEmitter.event()
                                        .name(type.isEmpty() ? "step" : type)
                                        .data(data));
                            } catch (IOException e) {
                                // Client disconnected mid-run: stop the upstream
                                // work so a cancelled investigation really stops.
                                Disposable sub = subscriptionRef.get();
                                if (sub != null) {
                                    sub.dispose();
                                }
                            }
                        },
                        error -> {
                            log.error("Agent stream failed for project {} on {}", projectId, aiPath, error);
                            try {
                                emitter.send(SseEmitter.event()
                                        .name("error")
                                        .data(Map.of("message",
                                                "The AI service could not be reached. Please try again.")));
                            } catch (IOException ignored) {
                                // Client is gone.
                            }
                            emitter.complete();
                        },
                        emitter::complete));
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

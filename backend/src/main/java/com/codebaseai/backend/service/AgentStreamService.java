package com.codebaseai.backend.service;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.codebaseai.backend.model.Project;
import com.codebaseai.backend.repository.ProjectRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;
import reactor.core.scheduler.Schedulers;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentStreamService {
    private final AiServiceClient aiServiceClient;
    private final ProjectRepository projectRepository;
    private final ChatTurnService turns;
    private final ObjectMapper objectMapper;

    public void stream(UUID projectId, UUID userId, String aiPath, Map<String, Object> request,
                       SseEmitter emitter, String question, UUID conversationId, UUID requestId,
                       UUID replyToMessageId, boolean regenerate) {
        Project project = requireReadyProject(projectId, userId);
        ChatTurnService.Turn turn = question == null ? null : turns.accept(projectId, userId, conversationId,
                requestId, replyToMessageId, "deep", question, regenerate);
        if (project.getChatInstructions() != null && !project.getChatInstructions().isBlank()) {
            request.put("instructions", project.getChatInstructions().trim());
        }
        if (turn != null && turn.completed()) {
            send(emitter, "done", turn.resultData());
            emitter.complete();
            return;
        }
        AtomicBoolean terminal = new AtomicBoolean();
        AtomicReference<Disposable> subscription = new AtomicReference<>();
        Runnable interrupt = () -> {
            if (terminal.compareAndSet(false, true) && turn != null) {
                try { turns.interrupt(turn); }
                catch (RuntimeException e) { log.warn("Unable to interrupt investigation {}", turn.requestId(), e); }
            }
            Disposable disposable = subscription.get();
            if (disposable != null) disposable.dispose();
        };
        emitter.onTimeout(interrupt);
        emitter.onError(error -> interrupt.run());
        emitter.onCompletion(interrupt);
        if (turn != null && !send(emitter, "meta", Map.of("requestId", turn.requestId(), "userMessageId", turn.userMessageId()))) {
            interrupt.run();
            emitter.complete();
            return;
        }
        Disposable disposable = aiServiceClient.streamAgent(aiPath, request)
                .publishOn(Schedulers.boundedElastic())
                .subscribe(event -> {
                    if (terminal.get()) return;
                    String type = event.event() == null ? "step" : event.event();
                    String data = event.data() == null ? "" : event.data();
                    if ("error".equals(type)) {
                        if (terminal.compareAndSet(false, true)) {
                            if (turn != null) turns.interrupt(turn);
                            send(emitter, "error", data);
                            emitter.complete();
                        }
                        return;
                    }
                    if ("done".equals(type)) {
                        if (!terminal.compareAndSet(false, true)) return;
                        try {
                            if (turn != null) {
                                var result = objectMapper.readTree(data);
                                String answer = result.path("answer").asText("");
                                if (answer.isBlank()) throw new IllegalStateException("Investigation produced no answer");
                                var citations = result.path("citations");
                                turns.complete(turn, answer, citations.isArray() ? citations.toString() : null, data, data);
                            }
                            send(emitter, "done", data);
                        } catch (Exception e) {
                            log.error("Could not save investigation", e);
                            if (turn != null) turns.interrupt(turn);
                            send(emitter, "error", Map.of("message", "The investigation could not be saved.", "code", "persistence_error"));
                        }
                        emitter.complete();
                    } else if (!send(emitter, type, data)) {
                        interrupt.run();
                    }
                }, error -> {
                    if (terminal.compareAndSet(false, true)) {
                        if (turn != null) turns.interrupt(turn);
                        send(emitter, "error", AiStreamErrors.describe(error));
                        emitter.complete();
                    }
                }, () -> {
                    if (terminal.compareAndSet(false, true)) {
                        if (turn != null) turns.interrupt(turn);
                        send(emitter, "error", Map.of("message", "The investigation ended before completing.", "code", "interrupted"));
                        emitter.complete();
                    }
                });
        subscription.set(disposable);
        if (terminal.get()) disposable.dispose();
    }

    public void stream(UUID projectId, UUID userId, String aiPath, Map<String, Object> request, SseEmitter emitter) {
        stream(projectId, userId, aiPath, request, emitter, null, null, null, null, false);
    }

    private boolean send(SseEmitter emitter, String type, Object data) {
        try {
            emitter.send(SseEmitter.event().name(type).data(data));
            return true;
        } catch (IOException | RuntimeException e) {
            log.debug("Unable to send agent event {}", type, e);
            return false;
        }
    }

    private Project requireReadyProject(UUID projectId, UUID userId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
        if (!project.getUserId().equals(userId)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        if (!"ready".equals(project.getStatus())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Project is not ready for investigation");
        return project;
    }
}

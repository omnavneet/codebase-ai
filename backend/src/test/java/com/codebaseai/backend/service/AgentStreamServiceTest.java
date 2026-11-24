package com.codebaseai.backend.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.codebaseai.backend.model.Project;
import com.codebaseai.backend.repository.ProjectRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Flux;

class AgentStreamServiceTest {
    @Test
    void savesPinnedCitationsAndReplaysWithoutCallingAiAgain() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID userMessageId = UUID.randomUUID();
        String endpoint = "/agent/investigate/stream";
        String question = "What happened?";
        String citations = "[{\"file_path\":\"app.py\",\"file_id\":\"" + UUID.randomUUID()
                + "\",\"start_line\":2,\"end_line\":2}]";
        String result = "{\"answer\":\"app.py:2\",\"citations\":" + citations + "}";
        Project project = new Project();
        project.setUserId(userId);
        project.setStatus("ready");
        var projects = mock(ProjectRepository.class);
        var ai = mock(AiServiceClient.class);
        var turns = mock(ChatTurnService.class);
        var accepted = new ChatTurnService.Turn(requestId, attemptId, conversationId, userMessageId, false, null, null);
        var completed = new ChatTurnService.Turn(requestId, attemptId, conversationId, userMessageId, false, result, UUID.randomUUID());
        when(projects.findById(projectId)).thenReturn(Optional.of(project));
        when(turns.accept(projectId, userId, conversationId, requestId, null, "deep", question, false))
                .thenReturn(accepted, completed);
        when(ai.streamAgent(eq(endpoint), any())).thenReturn(Flux.just(ServerSentEvent.builder(result).event("done").build()));
        var streams = new AgentStreamService(ai, projects, turns, new ObjectMapper());

        streams.stream(projectId, userId, endpoint, new HashMap<>(), mock(SseEmitter.class), question,
                conversationId, requestId, null, false);

        verify(turns, timeout(5000)).complete(accepted, "app.py:2", citations, result, result);

        var replay = mock(SseEmitter.class);
        streams.stream(projectId, userId, endpoint, new HashMap<>(), replay, question,
                conversationId, requestId, null, false);
        verify(replay).send(argThat((SseEmitter.SseEventBuilder event) ->
                event.build().stream().anyMatch(part -> result.equals(part.getData()))));
        verify(replay).complete();
        verify(ai).streamAgent(eq(endpoint), any());
    }
}

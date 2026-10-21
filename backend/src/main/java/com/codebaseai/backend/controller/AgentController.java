package com.codebaseai.backend.controller;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.codebaseai.backend.dto.AgentInvestigateRequest;
import com.codebaseai.backend.dto.AgentInvestigateResponse;
import com.codebaseai.backend.dto.DebugRequest;
import com.codebaseai.backend.dto.ExplainCodeRequest;
import com.codebaseai.backend.config.AppProperties;
import com.codebaseai.backend.service.AgentService;
import com.codebaseai.backend.service.AgentStreamService;
import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/projects/{projectId}/agent")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;
    private final AgentStreamService agentStreamService;
    private final AppProperties properties;

    @PostMapping("/investigate")
    public ResponseEntity<AgentInvestigateResponse> investigate(
            @PathVariable UUID projectId,
            @Valid @RequestBody AgentInvestigateRequest request) {

        UUID userId = getCurrentUserId();
        return ResponseEntity.ok(
            agentService.investigate(projectId, userId, request.getQuestion())
        );
    }

    @PostMapping("/explain-code")
    public ResponseEntity<Map<String, Object>> explainCode(
            @PathVariable UUID projectId,
            @Valid @RequestBody ExplainCodeRequest request) {

        UUID userId = getCurrentUserId();
        return ResponseEntity.ok(
            agentService.explainCode(projectId, userId, request.getFilePath(), request.getSymbol())
        );
    }

    @PostMapping("/debug")
    public ResponseEntity<Map<String, Object>> debug(
            @PathVariable UUID projectId,
            @Valid @RequestBody DebugRequest request) {

        UUID userId = getCurrentUserId();
        return ResponseEntity.ok(
            agentService.debug(projectId, userId, request.getIssueDescription(),
                    request.getStackTrace(), request.getFilePath())
        );
    }

    // ---- Streaming variants ---------------------------------------------
    //
    // Same bounded runs as the sync endpoints above, but relayed as SSE so the
    // client sees live step events. The ownership/ready check runs synchronously
    // (inside the stream service) before the emitter is returned, so auth and
    // validation failures still surface as normal HTTP error statuses.

    private SseEmitter newEmitter() {
        return new SseEmitter(properties.getAiService().getAgentStreamTimeoutMs());
    }

    @PostMapping(value = "/investigate/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter investigateStream(
            @PathVariable UUID projectId,
            @Valid @RequestBody AgentInvestigateRequest request) {

        UUID userId = getCurrentUserId();
        SseEmitter emitter = newEmitter();
        Map<String, Object> body = new HashMap<>();
        body.put("question", request.getQuestion());
        body.put("project_id", projectId.toString());
        agentStreamService.stream(projectId, userId, "/agent/investigate/stream",
                body, emitter, request.getQuestion(), request.getConversationId());
        return emitter;
    }

    @PostMapping(value = "/explain-code/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter explainCodeStream(
            @PathVariable UUID projectId,
            @Valid @RequestBody ExplainCodeRequest request) {

        UUID userId = getCurrentUserId();
        SseEmitter emitter = newEmitter();
        Map<String, Object> body = new HashMap<>();
        body.put("file_path", request.getFilePath());
        body.put("project_id", projectId.toString());
        body.put("symbol", request.getSymbol());
        agentStreamService.stream(projectId, userId, "/agent/explain-code/stream", body, emitter);
        return emitter;
    }

    @PostMapping(value = "/debug/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter debugStream(
            @PathVariable UUID projectId,
            @Valid @RequestBody DebugRequest request) {

        UUID userId = getCurrentUserId();
        SseEmitter emitter = newEmitter();
        Map<String, Object> body = new HashMap<>();
        body.put("issue_description", request.getIssueDescription());
        body.put("project_id", projectId.toString());
        body.put("stack_trace", request.getStackTrace());
        body.put("file_path", request.getFilePath());
        agentStreamService.stream(projectId, userId, "/agent/debug/stream", body, emitter);
        return emitter;
    }

    private UUID getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return UUID.fromString(authentication.getName());
    }
}
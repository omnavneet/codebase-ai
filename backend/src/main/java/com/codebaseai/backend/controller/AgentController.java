package com.codebaseai.backend.controller;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.codebaseai.backend.dto.AgentInvestigateRequest;
import com.codebaseai.backend.dto.AgentInvestigateResponse;
import com.codebaseai.backend.service.AgentService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/projects/{projectId}/agent")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;

    @PostMapping("/investigate")
    public ResponseEntity<AgentInvestigateResponse> investigate(
            @PathVariable UUID projectId,
            @RequestBody AgentInvestigateRequest request) {

        UUID userId = getCurrentUserId();
        return ResponseEntity.ok(
            agentService.investigate(projectId, userId, request.getQuestion())
        );
    }

    private UUID getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return UUID.fromString(authentication.getName());
    }
}

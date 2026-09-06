package com.codebaseai.backend.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.codebaseai.backend.dto.AgentInvestigateResponse;
import com.codebaseai.backend.model.Project;
import com.codebaseai.backend.repository.ProjectRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    private final ProjectRepository projectRepository;
    private final AiServiceClient aiServiceClient;

    public AgentInvestigateResponse investigate(UUID projectId, UUID userId, String question) {
        // Verify project ownership
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));

        if (!project.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }

        // Check project is ready
        if (!"ready".equals(project.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Project is not ready for investigation");
        }

        if (question == null || question.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Question is required");
        }

        log.info("Starting agent investigation for project {}", projectId);

        // Call AI service
        Map<String, Object> response = aiServiceClient.investigate(
            question.trim(),
            projectId.toString()
        );

        // Parse response (AI service returns snake_case keys)
        String answer = response.get("answer") != null ? response.get("answer").toString() : "";
        List<String> trace = castStringList(response.get("trace"));
        int iterations = response.get("iterations") instanceof Number number ? number.intValue() : 0;
        List<String> filesRead = castStringList(response.get("files_read"));
        List<String> searchesPerformed = castStringList(response.get("searches_performed"));
        boolean truncated = Boolean.TRUE.equals(response.get("truncated"));

        return new AgentInvestigateResponse(
            answer,
            trace,
            iterations,
            filesRead,
            searchesPerformed,
            truncated
        );
    }

    @SuppressWarnings("unchecked")
    private List<String> castStringList(Object value) {
        return value instanceof List ? (List<String>) value : List.of();
    }
}

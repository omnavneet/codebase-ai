package com.codebaseai.backend.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.codebaseai.backend.dto.CreateProjectRequest;
import com.codebaseai.backend.dto.ProjectResponse;
import com.codebaseai.backend.service.ProjectService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {
    
    private final ProjectService projectService;
    
    @PostMapping
    public ResponseEntity<ProjectResponse> createProject(
            @RequestBody CreateProjectRequest request) {
        UUID userId = getCurrentUserId();
        return ResponseEntity.ok(projectService.createProject(userId, request.getName()));
    }
    
    @GetMapping
    public ResponseEntity<List<ProjectResponse>> getUserProjects() {
        UUID userId = getCurrentUserId();
        return ResponseEntity.ok(projectService.getUserProjects(userId));
    }
    
    @GetMapping("/{projectId}")
    public ResponseEntity<ProjectResponse> getProject(@PathVariable UUID projectId) {
        UUID userId = getCurrentUserId();
        return ResponseEntity.ok(projectService.getProject(projectId, userId));
    }
    
    @DeleteMapping("/{projectId}")
    public ResponseEntity<Void> deleteProject(@PathVariable UUID projectId) {
        UUID userId = getCurrentUserId();
        projectService.deleteProject(projectId, userId);
        return ResponseEntity.ok().build();
    }
    
    private UUID getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return UUID.fromString(authentication.getName());
    }
}
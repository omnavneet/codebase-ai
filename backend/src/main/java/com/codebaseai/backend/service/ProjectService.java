package com.codebaseai.backend.service;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.codebaseai.backend.dto.ProjectResponse;
import com.codebaseai.backend.model.Project;
import com.codebaseai.backend.repository.ProjectRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ProjectService {
    
    private final ProjectRepository projectRepository;
    
    @Transactional
    public ProjectResponse createProject(UUID userId, String name) {
        Project project = new Project();
        project.setUserId(userId);
        project.setName(name);
        project.setStatus("pending");
        
        projectRepository.save(project);
        
        return mapToResponse(project);
    }
    
    public List<ProjectResponse> getUserProjects(UUID userId) {
        return projectRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }
    
    public ProjectResponse getProject(UUID projectId, UUID userId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("Project not found"));
        
        // Security check: ensure project belongs to user
        if (!project.getUserId().equals(userId)) {
            throw new RuntimeException("Access denied");
        }
        
        return mapToResponse(project);
    }
    
    @Transactional
    public void deleteProject(UUID projectId, UUID userId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("Project not found"));
        
        if (!project.getUserId().equals(userId)) {
            throw new RuntimeException("Access denied");
        }
        
        projectRepository.delete(project);
    }
    
    private ProjectResponse mapToResponse(Project project) {
        return new ProjectResponse(
            project.getId(),
            project.getName(),
            project.getStatus(),
            project.getFileCount(),
            project.getTotalSizeBytes(),
            project.getCreatedAt()
        );
    }
}
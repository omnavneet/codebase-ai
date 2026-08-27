package com.codebaseai.backend.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;
import org.springframework.web.multipart.MultipartFile;

import com.codebaseai.backend.dto.ProjectResponse;
import com.codebaseai.backend.model.Project;
import com.codebaseai.backend.model.ProjectFile;
import com.codebaseai.backend.repository.ProjectFileRepository;
import com.codebaseai.backend.repository.ProjectRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final ProjectFileRepository projectFileRepository;
    private final FileStorageService fileStorageService;
    private final ZipExtractionService zipExtractionService;
    private final CodeProcessingService codeProcessingService;


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

    @Transactional
    public void uploadZip(UUID projectId, UUID userId, MultipartFile file) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("Project not found"));

        // Security check
        if (!project.getUserId().equals(userId)) {
            throw new RuntimeException("Access denied");
        }

        // Validate file
        if (file.isEmpty()) {
            throw new RuntimeException("File is empty");
        }

        if (!file.getOriginalFilename().endsWith(".zip")) {
            throw new RuntimeException("Only ZIP files are allowed");
        }

        try {
            // Update status
            project.setStatus("processing");
            projectRepository.save(project);

            // Store ZIP
            Path zipPath = fileStorageService.storeZipFile(file, projectId);

            // Extract files
            Path projectDir = fileStorageService.getProjectDirectory(projectId);
            List<ZipExtractionService.ExtractedFile> extractedFiles
                    = zipExtractionService.extractZip(zipPath, projectDir);

            // Store file metadata
            int totalSize = 0;
            for (ZipExtractionService.ExtractedFile extractedFile : extractedFiles) {
                ProjectFile projectFile = new ProjectFile();
                projectFile.setProjectId(projectId);
                projectFile.setPath(extractedFile.getPath());
                projectFile.setSizeBytes((int) extractedFile.getSize());

                // Calculate content hash
                byte[] content = Files.readAllBytes(extractedFile.getFilePath());
                String hash = DigestUtils.md5DigestAsHex(content);
                projectFile.setContentHash(hash);

                projectFileRepository.save(projectFile);
                totalSize += extractedFile.getSize();
            }

            // Delete ZIP file (keep extracted files)
            Files.deleteIfExists(zipPath);

            codeProcessingService.processProject(projectId);

            // Update project
            project.setStatus("ready");
            project.setFileCount(extractedFiles.size());
            project.setTotalSizeBytes((long) totalSize);
            projectRepository.save(project);

        } catch (IOException e) {
            project.setStatus("error");
            project.setErrorMessage("Failed to process upload: " + e.getMessage());
            projectRepository.save(project);
            throw new RuntimeException("Failed to process upload", e);
        }
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

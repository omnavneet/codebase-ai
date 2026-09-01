package com.codebaseai.backend.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

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
        String trimmedName = name == null ? "" : name.trim();
        if (trimmedName.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Project name is required");
        }

        Project project = new Project();
        project.setUserId(userId);
        project.setName(trimmedName);
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
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));

        if (!project.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }

        return mapToResponse(project);
    }

    @Transactional
    public void deleteProject(UUID projectId, UUID userId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));

        if (!project.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }

        projectRepository.delete(project);

        try {
            fileStorageService.deleteProjectDirectory(projectId);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to delete project files", e);
        }
    }

    @Transactional
    public void uploadZip(UUID projectId, UUID userId, MultipartFile file) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));

        if (!project.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }

        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File is empty");
        }

        String originalFilename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        if (!originalFilename.toLowerCase().endsWith(".zip")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only ZIP files are allowed");
        }

        try {
            project.setStatus("processing");
            projectRepository.save(project);

            Path zipPath = fileStorageService.storeZipFile(file, projectId);
            Path projectDir = fileStorageService.getProjectDirectory(projectId);
            List<ZipExtractionService.ExtractedFile> extractedFiles = zipExtractionService.extractZip(zipPath, projectDir);

            int totalSize = 0;
            for (ZipExtractionService.ExtractedFile extractedFile : extractedFiles) {
                ProjectFile projectFile = new ProjectFile();
                projectFile.setProjectId(projectId);
                projectFile.setPath(extractedFile.getPath());
                projectFile.setSizeBytes((int) extractedFile.getSize());

                byte[] content = Files.readAllBytes(extractedFile.getFilePath());
                String hash = DigestUtils.md5DigestAsHex(content);
                projectFile.setContentHash(hash);

                projectFileRepository.save(projectFile);
                totalSize += extractedFile.getSize();
            }

            Files.deleteIfExists(zipPath);

            try {
                codeProcessingService.processProject(projectId);
            } catch (RuntimeException e) {
                project.setStatus("error");
                project.setErrorMessage("Failed to process upload: " + e.getMessage());
                projectRepository.save(project);
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Failed to process upload", e);
            }

            project.setStatus("ready");
            project.setFileCount(extractedFiles.size());
            project.setTotalSizeBytes((long) totalSize);
            project.setErrorMessage(null);
            projectRepository.save(project);

        } catch (IOException e) {
            project.setStatus("error");
            project.setErrorMessage("Failed to process upload: " + e.getMessage());
            projectRepository.save(project);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to process upload", e);
        }
    }

    public List<Map<String, Object>> getProjectFiles(UUID projectId, UUID userId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("Project not found"));

        if (!project.getUserId().equals(userId)) {
            throw new RuntimeException("Access denied");
        }

        List<ProjectFile> files = projectFileRepository.findByProjectId(projectId);

        // Build tree structure
        Map<String, Object> root = new HashMap<>();
        root.put("name", "root");
        root.put("type", "directory");
        root.put("children", new ArrayList<>());

        for (ProjectFile file : files) {
            String[] parts = file.getPath().split("/");
            Map<String, Object> current = root;

            for (int i = 0; i < parts.length; i++) {
                String part = parts[i];
                boolean isFile = (i == parts.length - 1);

                List<Map<String, Object>> children = (List<Map<String, Object>>) current.get("children");
                Map<String, Object> existing = children.stream()
                        .filter(c -> c.get("name").equals(part))
                        .findFirst()
                        .orElse(null);

                if (existing == null) {
                    existing = new HashMap<>();
                    existing.put("name", part);
                    existing.put("type", isFile ? "file" : "directory");
                    existing.put("path", String.join("/", Arrays.copyOfRange(parts, 0, i + 1)));
                    if (isFile) {
                        existing.put("fileId", file.getId().toString());
                        existing.put("size", file.getSizeBytes());
                    } else {
                        existing.put("children", new ArrayList<>());
                    }
                    children.add(existing);
                }

                current = existing;
            }
        }

        return (List<Map<String, Object>>) root.get("children");
    }

    public Map<String, String> getFileContent(UUID projectId, UUID fileId, UUID userId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("Project not found"));

        if (!project.getUserId().equals(userId)) {
            throw new RuntimeException("Access denied");
        }

        ProjectFile file = projectFileRepository.findById(fileId)
                .orElseThrow(() -> new RuntimeException("File not found"));

        try {
            Path filePath = fileStorageService.getProjectDirectory(projectId)
                    .resolve(file.getPath());
            String content = Files.readString(filePath);

            return Map.of(
                    "path", file.getPath(),
                    "content", content,
                    "size", String.valueOf(file.getSizeBytes())
            );
        } catch (IOException e) {
            throw new RuntimeException("Failed to read file", e);
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

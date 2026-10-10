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
import com.codebaseai.backend.model.CodeChunk;
import com.codebaseai.backend.model.CodeReference;
import com.codebaseai.backend.model.CodeSymbol;
import com.codebaseai.backend.model.Project;
import com.codebaseai.backend.model.ProjectFile;
import com.codebaseai.backend.model.IndexingJob;
import com.codebaseai.backend.repository.CodeChunkRepository;
import com.codebaseai.backend.repository.CodeReferenceRepository;
import com.codebaseai.backend.repository.CodeSymbolRepository;
import com.codebaseai.backend.repository.ProjectFileRepository;
import com.codebaseai.backend.repository.ProjectRepository;
import com.codebaseai.backend.service.chunking.Language;
import com.codebaseai.backend.config.AppProperties;
import com.codebaseai.backend.storage.StoragePaths;
import com.codebaseai.backend.storage.StorageService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final ProjectFileRepository projectFileRepository;
    private final CodeChunkRepository codeChunkRepository;
    private final CodeSymbolRepository codeSymbolRepository;
    private final CodeReferenceRepository codeReferenceRepository;
    private final AiServiceClient aiServiceClient;
    private final StorageService storageService;
    private final ZipExtractionService zipExtractionService;
    private final CodeProcessingService codeProcessingService;
    private final AppProperties properties;
    private final IndexingJobService indexingJobService;

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
            storageService.deleteProject(projectId);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to delete project files", e);
        }
    }

    /**
     * Creates an indexing job for a project upload. Extraction and embedding
     * processing is performed asynchronously by a worker.
     * 
     * @param projectId Project to index
     * @param userId    User making the request (for ownership check)
     * @param file      Uploaded ZIP file
     * @return IndexingJob for the upload (status will be queued)
     * @throws IOException if file processing fails
     */
    public IndexingJob uploadZip(UUID projectId, UUID userId, 
            org.springframework.web.multipart.MultipartFile file) throws IOException {
        // Delegate to IndexingJobService for job creation with idempotency
        return indexingJobService.createOrGetJob(projectId, userId, file);
    }

    public List<Map<String, Object>> getProjectFiles(UUID projectId, UUID userId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));

        if (!project.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
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
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));

        if (!project.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }

        ProjectFile file = projectFileRepository.findById(fileId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found"));

        // The file must belong to the project named in the path, otherwise a
        // caller could pair an owned project id with someone else's file id.
        if (!file.getProjectId().equals(projectId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found in this project");
        }

        try {
            String content = storageService.readText(projectId, file.getPath());

            return Map.of(
                    "path", file.getPath(),
                    "content", content,
                    "size", String.valueOf(file.getSizeBytes())
            );
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to read file", e);
        }
    }

    public List<Map<String, Object>> searchCode(UUID projectId, String query, UUID userId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));

        if (!project.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }

        // Generate embedding for query
        List<List<Double>> queryEmbeddings = aiServiceClient.generateEmbeddings(List.of(query));
        List<Double> queryEmbedding = queryEmbeddings.get(0);

        // Convert to pgvector format
        String embeddingString = Arrays.toString(queryEmbedding.toArray());

        // Search similar chunks
        List<CodeChunk> similarChunks = codeChunkRepository.findSimilarChunks(
                projectId,
                embeddingString,
                properties.getRetrieval().getSearchTopK()
        );

        // Build results with file info
        List<Map<String, Object>> results = new ArrayList<>();
        for (CodeChunk chunk : similarChunks) {
            ProjectFile file = projectFileRepository.findById(chunk.getFileId()).orElse(null);
            if (file != null) {
                Map<String, Object> result = new HashMap<>();
                result.put("chunkId", chunk.getId().toString());
                result.put("fileId", file.getId().toString());
                result.put("filePath", file.getPath());
                result.put("startLine", chunk.getStartLine());
                result.put("endLine", chunk.getEndLine());
                result.put("content", chunk.getContent().substring(0, Math.min(200, chunk.getContent().length())));
                result.put("symbol", chunk.getSymbol());
                result.put("chunkType", chunk.getChunkType());
                result.put("tokens", chunk.getTokenCount());
                result.put("similarity", calculateSimilarity(queryEmbedding, chunk.getEmbedding()));
                results.add(result);
            }
        }

        return results;
    }

    private double calculateSimilarity(List<Double> queryEmbedding, float[] chunkEmbedding) {
        // Cosine similarity
        double dotProduct = 0;
        double queryNorm = 0;
        double chunkNorm = 0;

        for (int i = 0; i < queryEmbedding.size(); i++) {
            dotProduct += queryEmbedding.get(i) * chunkEmbedding[i];
            queryNorm += queryEmbedding.get(i) * queryEmbedding.get(i);
            chunkNorm += chunkEmbedding[i] * chunkEmbedding[i];
        }

        return dotProduct / (Math.sqrt(queryNorm) * Math.sqrt(chunkNorm));
    }

    public Map<String, String> getFileContentByPath(UUID projectId, String path, UUID userId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));

        if (!project.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }

        // The storage backend owns the traversal guard, but surface a 400 here
        // (rather than a 500) so a malformed path reads as a client error.
        try {
            StoragePaths.requireRelative(path);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid path");
        }

        if (!storageService.exists(projectId, path)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found: " + path);
        }

        try {
            return Map.of(
                    "path", path,
                    "content", storageService.readText(projectId, path)
            );
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to read file", e);
        }
    }

    // ---- Generated artefacts ------------------------------------------------
    //
    // Generated content is written ONLY to <project>/__generated__ and never
    // replaces a source file. Generated files have no `files` row, so they are
    // never chunked, embedded or returned by search.

    public Map<String, String> exportGeneratedDoc(
            UUID projectId, UUID userId, String sourcePath, String symbol, String content) {
        requireOwnedProject(projectId, userId);

        String fileName = uniqueGeneratedName(projectId, generatedFileName(sourcePath, symbol));
        String relativePath = StorageService.GENERATED_DIRECTORY + "/" + fileName;
        try {
            storageService.writeText(projectId, relativePath, content);
            return Map.of("path", relativePath);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to save generated document", e);
        }
    }

    public List<Map<String, Object>> getGeneratedFiles(UUID projectId, UUID userId) {
        requireOwnedProject(projectId, userId);

        try {
            return storageService.listGeneratedFiles(projectId).stream()
                    .map(file -> Map.<String, Object>of(
                            "name", file.name(),
                            "sizeBytes", file.sizeBytes()))
                    .toList();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to list generated files", e);
        }
    }

    public Map<String, String> getGeneratedFile(UUID projectId, String name, UUID userId) {
        requireOwnedProject(projectId, userId);

        String relativePath;
        try {
            relativePath = StorageService.GENERATED_DIRECTORY + "/" + StoragePaths.requireRelative(name);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Generated file not found: " + name);
        }

        if (!storageService.exists(projectId, relativePath)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Generated file not found: " + name);
        }
        try {
            return Map.of("path", relativePath, "content", storageService.readText(projectId, relativePath));
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to read generated file", e);
        }
    }

    private void requireOwnedProject(UUID projectId, UUID userId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
        if (!project.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }
    }

    private String generatedFileName(String sourcePath, String symbol) {
        String normalized = sourcePath == null ? "" : sourcePath.replace('\\', '/');
        if (normalized.isBlank() || normalized.startsWith("/") || normalized.contains("..") || normalized.contains(":")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid file path");
        }
        int dot = normalized.lastIndexOf('.');
        String stem = dot > 0 ? normalized.substring(0, dot) : normalized;
        String suffix = symbol != null && !symbol.isBlank() ? "_" + symbol : "";
        String base = (stem + suffix).replaceAll("[^A-Za-z0-9]+", "_").replaceAll("_+", "_");
        if (base.length() > 100) {
            base = base.substring(base.length() - 100);
        }
        return base + ".docs.md";
    }

    private String uniqueGeneratedName(UUID projectId, String fileName) {
        String prefix = StorageService.GENERATED_DIRECTORY + "/";
        if (!storageService.exists(projectId, prefix + fileName)) {
            return fileName;
        }
        int dot = fileName.lastIndexOf('.');
        String stem = dot > 0 ? fileName.substring(0, dot) : fileName;
        String extension = dot > 0 ? fileName.substring(dot) : "";
        for (int attempt = 1; attempt < 1000; attempt++) {
            String candidate = stem + "-" + attempt + extension;
            if (!storageService.exists(projectId, prefix + candidate)) {
                return candidate;
            }
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Too many generated files with this name");
    }

    private ProjectResponse mapToResponse(Project project) {
        return new ProjectResponse(
                project.getId(),
                project.getName(),
                project.getStatus(),
                project.getFileCount(),
                project.getTotalSizeBytes(),
                project.getErrorMessage(),
                project.getCreatedAt()
        );
    }

    /** Removes a project's indexed rows so a new upload starts from zero. */
    private void purgeExistingIndex(UUID projectId) {
        codeReferenceRepository.deleteByProjectId(projectId);
        codeSymbolRepository.deleteByProjectId(projectId);
        codeChunkRepository.deleteByProjectId(projectId);
        projectFileRepository.deleteByProjectId(projectId);
    }
}

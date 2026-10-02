package com.codebaseai.backend.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.codebaseai.backend.config.AppProperties;
import com.codebaseai.backend.service.ZipExtractionService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Filesystem-backed {@link StorageService} used for local development and the
 * single-node Docker Compose deployment. Project data lives at
 * {@code <app.upload.directory>/<projectId>/}.
 *
 * <p>Selected unless {@code app.storage.provider=s3} is set.

 * <p>Extraction writes straight into the project directory, so
 * {@link #persistExtractedFiles} has nothing to commit - the S3 backend does.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.storage.provider", havingValue = "local", matchIfMissing = true)
public class LocalStorageService implements StorageService {

    private final AppProperties properties;

    private Path projectDirectory(UUID projectId) {
        return Paths.get(properties.getUpload().getDirectory(), projectId.toString());
    }

    private Path resolve(UUID projectId, String relativePath) {
        return StoragePaths.resolveWithin(projectDirectory(projectId), relativePath);
    }

    @Override
    public Path storeZip(MultipartFile file, UUID projectId) throws IOException {
        Path projectDir = projectDirectory(projectId);
        Files.createDirectories(projectDir);

        // A UUID rather than a timestamp: two uploads landing in the same
        // millisecond must not share a staging file.
        String filename = "upload_" + UUID.randomUUID() + ".zip";
        Path zipPath = projectDir.resolve(filename);
        file.transferTo(zipPath.toAbsolutePath());

        return zipPath;
    }

    @Override
    public Path extractionDirectory(UUID projectId) throws IOException {
        Path projectDir = projectDirectory(projectId);
        Files.createDirectories(projectDir);
        return projectDir;
    }

    @Override
    public void persistExtractedFiles(UUID projectId, Path extractionDirectory,
            List<ZipExtractionService.ExtractedFile> files) {
        // Extraction already wrote the files into the project directory.
    }

    @Override
    public void discardStagingFile(Path stagingFile) {
        if (stagingFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(stagingFile);
        } catch (IOException e) {
            log.warn("Failed to delete staged upload {}: {}", stagingFile, e.getMessage());
        }
    }

    @Override
    public boolean exists(UUID projectId, String relativePath) {
        try {
            return Files.isRegularFile(resolve(projectId, relativePath));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public byte[] readBytes(UUID projectId, String relativePath) throws IOException {
        return Files.readAllBytes(resolve(projectId, relativePath));
    }

    @Override
    public String readText(UUID projectId, String relativePath) throws IOException {
        return Files.readString(resolve(projectId, relativePath), StandardCharsets.UTF_8);
    }

    @Override
    public void writeText(UUID projectId, String relativePath, String content) throws IOException {
        Path target = resolve(projectId, relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    @Override
    public List<GeneratedFile> listGeneratedFiles(UUID projectId) throws IOException {
        Path directory = projectDirectory(projectId).resolve(GENERATED_DIRECTORY);
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                    .map(path -> new GeneratedFile(path.getFileName().toString(), sizeOf(path)))
                    .sorted(Comparator.comparing(GeneratedFile::name))
                    .toList();
        }
    }

    @Override
    public void deleteProject(UUID projectId) throws IOException {
        Path projectDir = projectDirectory(projectId);
        if (!Files.exists(projectDir)) {
            return;
        }
        // try-with-resources: an unclosed walk stream keeps a directory handle
        // open, which on Windows prevents the directory from being deleted at all.
        try (Stream<Path> paths = Files.walk(projectDir)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            log.warn("Failed to delete {}: {}", path, e.getMessage());
                        }
                    });
        }
    }

    private long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0L;
        }
    }
}
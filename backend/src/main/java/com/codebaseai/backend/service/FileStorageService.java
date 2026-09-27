package com.codebaseai.backend.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;  
import java.util.UUID;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.codebaseai.backend.config.AppProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileStorageService {

    private final AppProperties properties;

    public Path storeZipFile(MultipartFile file, UUID projectId) throws IOException {
        // Create project directory
        Path projectDir = getProjectDirectory(projectId);
        Files.createDirectories(projectDir);
        
        // Save ZIP file
        String filename = "upload_" + System.currentTimeMillis() + ".zip";
        Path zipPath = projectDir.resolve(filename);
        file.transferTo(zipPath.toAbsolutePath());
        
        return zipPath;
    }
    
    public Path getProjectDirectory(UUID projectId) {
        return Paths.get(properties.getUpload().getDirectory(), projectId.toString());
    }

    /** Absolute path of the directory holding generated artefacts (never sources). */
    public Path getGeneratedDirectory(UUID projectId) {
        return getProjectDirectory(projectId).resolve("__generated__");
    }
    
    public void deleteProjectDirectory(UUID projectId) throws IOException {
        Path projectDir = getProjectDirectory(projectId);
        if (Files.exists(projectDir)) {
            // try-with-resources: an unclosed walk stream keeps a directory
            // handle open, which on Windows prevents the directory from being
            // deleted at all.
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
    }
}
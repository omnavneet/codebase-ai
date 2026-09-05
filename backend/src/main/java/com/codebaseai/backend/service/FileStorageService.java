package com.codebaseai.backend.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;  
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class FileStorageService {
    
    @Value("${app.upload.directory}")
    private String uploadDirectory;
    
    public Path storeZipFile(MultipartFile file, UUID projectId) throws IOException {
        // Create project directory
        Path projectDir = Paths.get(uploadDirectory, projectId.toString());
        Files.createDirectories(projectDir);
        
        // Save ZIP file
        String filename = "upload_" + System.currentTimeMillis() + ".zip";
        Path zipPath = projectDir.resolve(filename);
        file.transferTo(zipPath.toAbsolutePath());
        
        return zipPath;
    }
    
    public Path getProjectDirectory(UUID projectId) {
        return Paths.get(uploadDirectory, projectId.toString());
    }
    
    public void deleteProjectDirectory(UUID projectId) throws IOException {
        Path projectDir = getProjectDirectory(projectId);
        if (Files.exists(projectDir)) {
            Files.walk(projectDir)
                .sorted(Comparator.reverseOrder())
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
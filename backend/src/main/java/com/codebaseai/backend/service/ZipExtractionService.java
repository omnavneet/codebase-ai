package com.codebaseai.backend.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class ZipExtractionService {
    
    private static final List<String> IGNORED_DIRECTORIES = List.of(
        "node_modules",
        ".git",
        ".idea",
        ".vscode",
        "target",
        "build",
        "dist",
        "__pycache__"
    );
    
    private static final List<String> IGNORED_EXTENSIONS = List.of(
        ".jpg", ".jpeg", ".png", ".gif", ".ico", ".svg",
        ".pdf", ".doc", ".docx", ".xls", ".xlsx",
        ".zip", ".tar", ".gz", ".rar",
        ".exe", ".dll", ".so", ".dylib",
        ".class", ".jar", ".war",
        ".mp3", ".mp4", ".avi", ".mov",
        ".woff", ".woff2", ".ttf", ".eot"
    );
    
    private static final long MAX_FILE_SIZE = 1_000_000; // 1MB per file
    private static final int MAX_TOTAL_FILES = 5000;
    
    public List<ExtractedFile> extractZip(Path zipPath, Path destinationDir) throws IOException {
        List<ExtractedFile> extractedFiles = new ArrayList<>();
        int fileCount = 0;
        
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipPath))) {
            ZipEntry entry;
            
            while ((entry = zis.getNextEntry()) != null) {
                // Check file count limit
                if (fileCount >= MAX_TOTAL_FILES) {
                    log.warn("Max file count reached: {}", MAX_TOTAL_FILES);
                    break;
                }
                
                // Skip directories
                if (entry.isDirectory()) {
                    continue;
                }
                
                String fileName = entry.getName();
                
                // Check if should ignore
                if (shouldIgnore(fileName)) {
                    continue;
                }
                
                // Check file size
                if (entry.getSize() > MAX_FILE_SIZE) {
                    log.warn("Skipping large file: {} ({} bytes)", fileName, entry.getSize());
                    continue;
                }
                
                // Prevent zip slip attack
                Path destinationDirNormalized = destinationDir.toAbsolutePath().normalize();
                Path targetPath = destinationDirNormalized.resolve(fileName).normalize();
                if (!targetPath.startsWith(destinationDirNormalized)) {
                    log.warn("Skipping file outside destination: {}", fileName);
                    continue;
                }
                
                // Create parent directories
                Files.createDirectories(targetPath.getParent());
                
                // Extract file
                long copiedSize = Files.copy(zis, targetPath);

                // Add to result (entry.getSize() can be -1 for streamed entries)
                extractedFiles.add(new ExtractedFile(
                    fileName,
                    targetPath,
                    copiedSize
                ));
                
                fileCount++;
            }
        }
        
        return extractedFiles;
    }
    
    private boolean shouldIgnore(String fileName) {
        // Check directories
        for (String dir : IGNORED_DIRECTORIES) {
            if (fileName.contains("/" + dir + "/") || fileName.startsWith(dir + "/")) {
                return true;
            }
        }
        
        // Check extensions
        String lowercase = fileName.toLowerCase();
        for (String ext : IGNORED_EXTENSIONS) {
            if (lowercase.endsWith(ext)) {
                return true;
            }
        }
        
        return false;
    }
    
    // Inner class to hold extracted file info
    public static class ExtractedFile {
        private final String path;
        private final Path filePath;
        private final long size;
        
        public ExtractedFile(String path, Path filePath, long size) {
            this.path = path;
            this.filePath = filePath;
            this.size = size;
        }
        
        public String getPath() { return path; }
        public Path getFilePath() { return filePath; }
        public long getSize() { return size; }
    }
}
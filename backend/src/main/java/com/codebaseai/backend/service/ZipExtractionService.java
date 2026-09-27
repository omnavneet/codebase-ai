package com.codebaseai.backend.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.springframework.stereotype.Service;

import com.codebaseai.backend.config.AppProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class ZipExtractionService {

    private final AppProperties properties;
    
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
        long totalBytes = 0;
        long maxTotalBytes = properties.getUpload().getMaxTotalBytes();
        
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipPath))) {
            ZipEntry entry;
            
            while ((entry = zis.getNextEntry()) != null) {
                // Check file count limit
                if (fileCount >= MAX_TOTAL_FILES) {
                    throw new ZipLimitExceededException(
                            "The archive contains more than " + MAX_TOTAL_FILES + " files");
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
                
                // Fast path only: a ZIP header may under-report the entry size,
                // and it is -1 for entries written as a stream. The bytes
                // actually read below are what get enforced.
                if (entry.getSize() > MAX_FILE_SIZE) {
                    throw new ZipLimitExceededException(
                        "File exceeds the per-file limit of " + (MAX_FILE_SIZE / (1024 * 1024))
                            + " MB: " + fileName);
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
                
                // Read at most MAX_FILE_SIZE + 1 bytes: bounding the real byte
                // count is the only reliable limit, given the header size may be
                // missing or wrong. The cap also bounds this buffer, so a single
                // entry can never exhaust memory or disk.
                byte[] content = zis.readNBytes((int) MAX_FILE_SIZE + 1);
                if (content.length > MAX_FILE_SIZE) {
                    throw new ZipLimitExceededException(
                        "File exceeds the per-file limit of " + (MAX_FILE_SIZE / (1024 * 1024))
                            + " MB: " + fileName);
                }
                Files.write(targetPath, content);
                totalBytes += content.length;

                // Per-file limits do not bound the archive as a whole: a small
                // ZIP can still expand to gigabytes over thousands of entries.
                if (totalBytes > maxTotalBytes) {
                    throw new ZipLimitExceededException(
                            "The archive expands to more than "
                                    + (maxTotalBytes / (1024 * 1024))
                                    + " MB, which exceeds the configured limit");
                }

                // Add to result
                extractedFiles.add(new ExtractedFile(
                    fileName,
                    targetPath,
                    content.length
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
package com.codebaseai.backend.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

import com.codebaseai.backend.config.AppProperties;

class ZipExtractionServiceTest {

    @Test
    void rejectsAnOversizedSourceFile() throws IOException {
        Path zip = Files.createTempFile("oversized", ".zip");
        Path destination = Files.createTempDirectory("extracted");
        try {
            try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zip))) {
                output.putNextEntry(new ZipEntry("src/Large.java"));
                output.write(new byte[1_000_001]);
                output.closeEntry();
            }

            ZipExtractionService service = new ZipExtractionService(new AppProperties());

                ZipLimitExceededException exception = assertThrows(
                    ZipLimitExceededException.class, () -> service.extractZip(zip, destination));
                org.junit.jupiter.api.Assertions.assertTrue(exception.getMessage().contains("per-file limit"));
        } finally {
            deleteTree(zip);
            deleteTree(destination);
        }
    }

    @Test
    void doesNotExtractZipSlipEntries() throws IOException {
        Path zip = Files.createTempFile("zip-slip", ".zip");
        Path destination = Files.createTempDirectory("extracted");
        Path outside = destination.getParent().resolve("outside.txt");
        try {
            try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zip))) {
                output.putNextEntry(new ZipEntry("../outside.txt"));
                output.write("blocked".getBytes());
                output.closeEntry();
            }

            ZipExtractionService service = new ZipExtractionService(new AppProperties());
            List<ZipExtractionService.ExtractedFile> extracted = service.extractZip(zip, destination);

            assertFalse(Files.exists(outside));
            assertFalse(extracted.stream().anyMatch(file -> file.getPath().equals("../outside.txt")));
        } finally {
            deleteTree(zip);
            deleteTree(destination);
            Files.deleteIfExists(outside);
        }
    }

    private void deleteTree(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (var paths = Files.walk(path)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(item -> {
                    try {
                        Files.deleteIfExists(item);
                    } catch (IOException exception) {
                        throw new RuntimeException(exception);
                    }
                });
            }
        } else {
            Files.deleteIfExists(path);
        }
    }
}

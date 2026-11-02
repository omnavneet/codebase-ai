package com.codebaseai.backend.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    @Test
    void skipsSecretFilesAndKeepsSourceFiles() throws IOException {
        Path zip = Files.createTempFile("secrets", ".zip");
        Path destination = Files.createTempDirectory("extracted");
        try {
            writeZip(zip,
                "src/App.java",
                ".env",
                "config/.env.production",
                "certs/server.pem",
                "keys/private.key",
                ".ssh/id_rsa",
                "infra/terraform.tfstate",
                "app/.npmrc");

            List<ZipExtractionService.ExtractedFile> extracted =
                    new ZipExtractionService(new AppProperties()).extractZip(zip, destination);

            assertEquals(List.of("src/App.java"), paths(extracted));
            assertTrue(Files.exists(destination.resolve("src/App.java")));
            assertFalse(Files.exists(destination.resolve(".env")));
            assertFalse(Files.exists(destination.resolve("config/.env.production")));
            assertFalse(Files.exists(destination.resolve("certs/server.pem")));
            assertFalse(Files.exists(destination.resolve("keys/private.key")));
            assertFalse(Files.exists(destination.resolve(".ssh/id_rsa")));
            assertFalse(Files.exists(destination.resolve("infra/terraform.tfstate")));
            assertFalse(Files.exists(destination.resolve("app/.npmrc")));
        } finally {
            deleteTree(zip);
            deleteTree(destination);
        }
    }

    @Test
    void secretFileMatchingIgnoresCaseAndPathSeparator() throws IOException {
        Path zip = Files.createTempFile("secret-case", ".zip");
        Path destination = Files.createTempDirectory("extracted");
        try {
            writeZip(zip, ".ENV", "certs/SERVER.PEM", "config\\.env", "deploy\\id_rsa");

            List<ZipExtractionService.ExtractedFile> extracted =
                    new ZipExtractionService(new AppProperties()).extractZip(zip, destination);

            assertEquals(List.of(), paths(extracted));
        } finally {
            deleteTree(zip);
            deleteTree(destination);
        }
    }

    @Test
    void keepsFilesWhoseNamesOnlyResembleSecrets() throws IOException {
        Path zip = Files.createTempFile("look-alike", ".zip");
        Path destination = Files.createTempDirectory("extracted");
        try {
            writeZip(zip,
                "src/Keyboard.java",
                "src/environment.ts",
                "src/env.config.ts",
                "src/key.ts",
                "docs/keys.md");

            List<ZipExtractionService.ExtractedFile> extracted =
                    new ZipExtractionService(new AppProperties()).extractZip(zip, destination);

            assertEquals(List.of("src/Keyboard.java", "src/environment.ts", "src/env.config.ts",
                    "src/key.ts", "docs/keys.md"), paths(extracted));
        } finally {
            deleteTree(zip);
            deleteTree(destination);
        }
    }

    private void writeZip(Path zip, String... entryNames) throws IOException {
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (String entryName : entryNames) {
                output.putNextEntry(new ZipEntry(entryName));
                output.write("content".getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
    }

    private List<String> paths(List<ZipExtractionService.ExtractedFile> extracted) {
        return extracted.stream().map(ZipExtractionService.ExtractedFile::getPath).toList();
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

package com.codebaseai.backend.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import com.codebaseai.backend.config.AppProperties;
import com.codebaseai.backend.service.ZipExtractionService;

class LocalStorageServiceTest {

    @TempDir
    Path uploadRoot;

    private final UUID projectId = UUID.randomUUID();

    private LocalStorageService storageService;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties();
        properties.getUpload().setDirectory(uploadRoot.toString());
        storageService = new LocalStorageService(properties);
    }

    @Test
    void writesAndReadsFilesUnderTheProjectDirectory() throws IOException {
        storageService.writeText(projectId, "src/main/Foo.java", "class Foo {}");

        assertEquals("class Foo {}", storageService.readText(projectId, "src/main/Foo.java"));
        assertArrayEquals("class Foo {}".getBytes(StandardCharsets.UTF_8),
                storageService.readBytes(projectId, "src/main/Foo.java"));
        assertTrue(storageService.exists(projectId, "src/main/Foo.java"));
        assertTrue(Files.isRegularFile(uploadRoot.resolve(projectId.toString()).resolve("src/main/Foo.java")));
    }

    @Test
    void createsMissingParentDirectoriesOnWrite() throws IOException {
        storageService.writeText(projectId, "a/b/c/d.txt", "deep");

        assertEquals("deep", storageService.readText(projectId, "a/b/c/d.txt"));
    }

    @Test
    void readsMissingFilesAsNoSuchFile() {
        assertThrows(NoSuchFileException.class,
                () -> storageService.readText(projectId, "src/missing.java"));
    }

    @Test
    void existsReturnsFalseInsteadOfThrowingForUnsafePaths() {
        assertFalse(storageService.exists(projectId, "../outside.txt"));
        assertFalse(storageService.exists(projectId, "/etc/passwd"));
    }

    @Test
    void stagesUploadsInsideTheProjectDirectory() throws IOException {
        MockMultipartFile upload = new MockMultipartFile(
                "file", "project.zip", "application/zip", "zip-bytes".getBytes(StandardCharsets.UTF_8));

        Path staged = storageService.storeZip(upload, projectId);

        assertTrue(Files.isRegularFile(staged));
        assertEquals(projectId.toString(), staged.getParent().getFileName().toString());
        assertArrayEquals("zip-bytes".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(staged));

        storageService.discardStagingFile(staged);
        assertFalse(Files.exists(staged));
    }

    @Test
    void discardStagingFileToleratesNullAndMissingFiles() {
        storageService.discardStagingFile(null);
        storageService.discardStagingFile(uploadRoot.resolve("never-existed.zip"));
    }

    @Test
    void extractionDirectoryIsTheProjectDirectoryAndIsCreated() throws IOException {
        Path extractionDirectory = storageService.extractionDirectory(projectId);

        assertEquals(uploadRoot.resolve(projectId.toString()).toAbsolutePath().normalize(),
                extractionDirectory.toAbsolutePath().normalize());
        assertTrue(Files.isDirectory(extractionDirectory));
    }

    @Test
    void persistExtractedFilesIsANoOpBecauseExtractionWritesInPlace() throws IOException {
        Path extracted = uploadRoot.resolve(projectId.toString()).resolve("src/main/Foo.java");
        Files.createDirectories(extracted.getParent());
        Files.writeString(extracted, "class Foo {}");
        List<ZipExtractionService.ExtractedFile> files = List.of(
                new ZipExtractionService.ExtractedFile("src/main/Foo.java", extracted, 12L));

        storageService.persistExtractedFiles(projectId, extracted.getParent(), files);

        assertEquals("class Foo {}", storageService.readText(projectId, "src/main/Foo.java"));
    }

    @Test
    void listsGeneratedFilesSortedByNameWithSizes() throws IOException {
        storageService.writeText(projectId, StorageService.GENERATED_DIRECTORY + "/b.txt", "bb");
        storageService.writeText(projectId, StorageService.GENERATED_DIRECTORY + "/a.txt", "a");

        List<StorageService.GeneratedFile> generated = storageService.listGeneratedFiles(projectId);

        assertEquals(List.of("a.txt", "b.txt"),
                generated.stream().map(StorageService.GeneratedFile::name).toList());
        assertEquals(1L, generated.get(0).sizeBytes());
        assertEquals(2L, generated.get(1).sizeBytes());
    }

    @Test
    void listsNoGeneratedFilesWhenTheDirectoryIsAbsent() throws IOException {
        assertTrue(storageService.listGeneratedFiles(projectId).isEmpty());
    }

    @Test
    void deleteProjectRemovesEveryFileAndIsIdempotent() throws IOException {
        storageService.writeText(projectId, "src/main/Foo.java", "class Foo {}");
        storageService.writeText(projectId, StorageService.GENERATED_DIRECTORY + "/report.md", "# report");

        storageService.deleteProject(projectId);

        assertFalse(Files.exists(uploadRoot.resolve(projectId.toString())));
        // A second delete (or one for a project that was never uploaded) must not fail.
        storageService.deleteProject(projectId);
        storageService.deleteProject(UUID.randomUUID());
    }

    @Test
    void projectsAreIsolatedFromEachOther() throws IOException {
        UUID otherProjectId = UUID.randomUUID();
        storageService.writeText(projectId, "src/main/Foo.java", "class Foo {}");

        assertFalse(storageService.exists(otherProjectId, "src/main/Foo.java"));
        storageService.deleteProject(otherProjectId);
        assertTrue(storageService.exists(projectId, "src/main/Foo.java"));
    }
}
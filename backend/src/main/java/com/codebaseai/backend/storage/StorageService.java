package com.codebaseai.backend.storage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.springframework.web.multipart.MultipartFile;

import com.codebaseai.backend.service.ZipExtractionService;

/**
 * Storage for a project's extracted source tree and its generated artefacts.
 *
 * <p>Every operation is scoped to a project and addressed by a project-relative
 * path ({@code "src/main/Foo.java"}), never by an absolute filesystem path: the
 * local backend maps that onto {@code <upload-dir>/<projectId>/<path>} while the
 * S3 backend maps it onto {@code s3://<bucket>/<prefix>/<projectId>/<path>}.
 * Implementations own the traversal guard ({@link StoragePaths}).
 *
 * <p>The ZIP ingest flow is a three-step handshake, because extraction is a local,
 * random-access operation in both backends:
 * <ol>
 *   <li>{@link #storeZip} stages the upload as a readable local file;</li>
 *   <li>{@link #extractionDirectory} names a local directory to extract into;</li>
 *   <li>{@link #persistExtractedFiles} commits the extracted tree to the store.</li>
 * </ol>
 */
public interface StorageService {

    /** Directory (inside a project) holding generated artefacts, never sources. */
    String GENERATED_DIRECTORY = "__generated__";

    /** Stages an uploaded ZIP for extraction and returns a readable local file. */
    Path storeZip(MultipartFile file, UUID projectId) throws IOException;

    /** Local directory that {@link #persistExtractedFiles} reads extracted files from. */
    Path extractionDirectory(UUID projectId) throws IOException;

    /**
     * Commits the files produced by {@code ZipExtractionService} to the store.
     * For local storage the files already live in their final place, so this is a
     * no-op; the S3 implementation uploads them and removes the staging tree.
     */
    void persistExtractedFiles(UUID projectId, Path extractionDirectory,
            List<ZipExtractionService.ExtractedFile> files) throws IOException;

    /** Best-effort removal of the staging file returned by {@link #storeZip}. */
    void discardStagingFile(Path stagingFile);

    boolean exists(UUID projectId, String relativePath);

    byte[] readBytes(UUID projectId, String relativePath) throws IOException;

    String readText(UUID projectId, String relativePath) throws IOException;

    /** Writes (or overwrites) a text file inside the project, e.g. a generated artefact. */
    void writeText(UUID projectId, String relativePath, String content) throws IOException;

    List<GeneratedFile> listGeneratedFiles(UUID projectId) throws IOException;

    /** Removes every object belonging to the project. */
    void deleteProject(UUID projectId) throws IOException;

    record GeneratedFile(String name, long sizeBytes) {
    }
}
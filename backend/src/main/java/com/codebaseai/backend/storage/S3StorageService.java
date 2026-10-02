package com.codebaseai.backend.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import com.codebaseai.backend.config.AppProperties;
import com.codebaseai.backend.service.ZipExtractionService;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

/**
 * S3-backed {@link StorageService} used in production. Each project owns the key
 * prefix {@code <app.s3.prefix>/<projectId>/}, which is also what the IAM policy
 * and the AI service's read-only access are scoped to.
 *
 * <p>Selected only when {@code app.storage.provider=s3}. Uploads request
 * AES-256 server-side encryption explicitly rather than relying on a bucket
 * default, so the guarantee travels with the code.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.storage.provider", havingValue = "s3")
public class S3StorageService implements StorageService {

    private final AppProperties properties;
    private final S3Client s3Client;

    @PostConstruct
    public void validateConfiguration() {
        if (!StringUtils.hasText(bucket())) {
            throw new IllegalStateException(
                    "app.s3.bucket must be set when app.storage.provider=s3");
        }
    }

    private String bucket() {
        return properties.getS3().getBucket();
    }

    /** Key prefix owned by one project; also the scope of its IAM/AI read policy. */
    private String keyPrefix(UUID projectId) {
        return properties.getS3().getPrefix() + "/" + projectId;
    }

    private String key(UUID projectId, String relativePath) {
        return keyPrefix(projectId) + "/" + StoragePaths.requireRelative(relativePath);
    }

    @Override
    public Path storeZip(MultipartFile file, UUID projectId) throws IOException {
        // The archive is transient - extraction happens locally - so there is no
        // reason to round-trip it through S3.
        Path staging = Files.createTempFile("codebase-ai-upload-", ".zip");
        file.transferTo(staging.toAbsolutePath());
        return staging;
    }

    @Override
    public Path extractionDirectory(UUID projectId) throws IOException {
        return Files.createTempDirectory("codebase-ai-extract-");
    }

    @Override
    public void persistExtractedFiles(UUID projectId, Path extractionDirectory,
            List<ZipExtractionService.ExtractedFile> files) throws IOException {
        try {
            for (ZipExtractionService.ExtractedFile file : files) {
                s3Client.putObject(
                        PutObjectRequest.builder()
                                .bucket(bucket())
                                .key(key(projectId, file.getPath()))
                                .serverSideEncryption(ServerSideEncryption.AES256)
                                .build(),
                        RequestBody.fromFile(file.getFilePath()));
            }
            log.info("Uploaded {} file(s) for project {}", files.size(), projectId);
        } finally {
            // The extracted tree is only a staging area; the objects are now in S3.
            deleteTree(extractionDirectory);
        }
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
            s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(bucket())
                    .key(key(projectId, relativePath))
                    .build());
            return true;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public byte[] readBytes(UUID projectId, String relativePath) throws IOException {
        try {
            return s3Client.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(bucket())
                    .key(key(projectId, relativePath))
                    .build()).asByteArray();
        } catch (NoSuchKeyException e) {
            throw new NoSuchFileException(relativePath);
        }
    }

    @Override
    public String readText(UUID projectId, String relativePath) throws IOException {
        try {
            return s3Client.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(bucket())
                    .key(key(projectId, relativePath))
                    .build()).asUtf8String();
        } catch (NoSuchKeyException e) {
            throw new NoSuchFileException(relativePath);
        }
    }

    @Override
    public void writeText(UUID projectId, String relativePath, String content) {
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket())
                        .key(key(projectId, relativePath))
                        .contentType("text/plain; charset=utf-8")
                        .serverSideEncryption(ServerSideEncryption.AES256)
                        .build(),
                RequestBody.fromString(content, StandardCharsets.UTF_8));
    }

    @Override
    public List<GeneratedFile> listGeneratedFiles(UUID projectId) {
        String prefix = keyPrefix(projectId) + "/" + GENERATED_DIRECTORY + "/";
        List<GeneratedFile> files = new ArrayList<>();
        for (S3Object object : listObjects(prefix)) {
            String name = object.key().substring(prefix.length());
            if (!name.isEmpty()) {
                files.add(new GeneratedFile(name, object.size()));
            }
        }
        files.sort(Comparator.comparing(GeneratedFile::name));
        return files;
    }

    @Override
    public void deleteProject(UUID projectId) {
        List<ObjectIdentifier> identifiers = listObjects(keyPrefix(projectId) + "/").stream()
                .map(object -> ObjectIdentifier.builder().key(object.key()).build())
                .toList();
        if (identifiers.isEmpty()) {
            return;
        }
        s3Client.deleteObjects(DeleteObjectsRequest.builder()
                .bucket(bucket())
                .delete(Delete.builder().objects(identifiers).build())
                .build());
        log.info("Deleted {} object(s) for project {}", identifiers.size(), projectId);
    }

    private List<S3Object> listObjects(String prefix) {
        List<S3Object> objects = new ArrayList<>();
        String continuationToken = null;
        do {
            ListObjectsV2Response response = s3Client.listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(bucket())
                    .prefix(prefix)
                    .continuationToken(continuationToken)
                    .build());
            objects.addAll(response.contents());
            continuationToken = Boolean.TRUE.equals(response.isTruncated())
                    ? response.nextContinuationToken()
                    : null;
        } while (continuationToken != null);
        return objects;
    }

    private void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    log.warn("Failed to delete staging file {}: {}", path, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("Failed to clean staging directory {}: {}", root, e.getMessage());
        }
    }
}
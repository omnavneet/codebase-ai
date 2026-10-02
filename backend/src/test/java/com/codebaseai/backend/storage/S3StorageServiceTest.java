package com.codebaseai.backend.storage;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import com.codebaseai.backend.config.AppProperties;
import com.codebaseai.backend.service.ZipExtractionService;

import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

class S3StorageServiceTest {

    private static final String BUCKET = "codebase-ai-test";
    private static final String PREFIX = "projects";

    private final UUID projectId = UUID.randomUUID();

    private final RecordingS3Client fakeS3 = new RecordingS3Client();

    private S3StorageService storageService;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties();
        properties.getS3().setBucket(BUCKET);
        properties.getS3().setPrefix(PREFIX);
        storageService = new S3StorageService(properties, fakeS3.client());
    }

    private String keyPrefix() {
        return PREFIX + "/" + projectId;
    }

    @Test
    void uploadingExtractedFilesUsesTheProjectPrefixAndEncryption() throws IOException {
        Path extraction = Files.createTempDirectory("codebase-ai-extract");
        Path source = extraction.resolve("src/main/Foo.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class Foo {}");
        List<ZipExtractionService.ExtractedFile> files = List.of(
                new ZipExtractionService.ExtractedFile("src/main/Foo.java", source, 12L));

        storageService.persistExtractedFiles(projectId, extraction, files);

        PutObjectRequest request = singleRequest("putObject", PutObjectRequest.class);
        assertEquals(BUCKET, request.bucket());
        assertEquals(keyPrefix() + "/src/main/Foo.java", request.key());
        assertEquals(ServerSideEncryption.AES256, request.serverSideEncryption());
        // The local extraction tree is staging only, and is always cleaned up.
        assertFalse(Files.exists(extraction));
    }

    @Test
    void writingTextUploadsUnderTheProjectPrefixWithEncryption() {
        storageService.writeText(projectId, StorageService.GENERATED_DIRECTORY + "/report.md", "# report");

        PutObjectRequest request = singleRequest("putObject", PutObjectRequest.class);
        assertEquals(BUCKET, request.bucket());
        assertEquals(keyPrefix() + "/" + StorageService.GENERATED_DIRECTORY + "/report.md", request.key());
        assertEquals(ServerSideEncryption.AES256, request.serverSideEncryption());
    }

    @Test
    void readsObjectsAsUtf8Text() throws IOException {
        fakeS3.script("getObjectAsBytes", ResponseBytes.fromByteArray(
                GetObjectResponse.builder().build(), "class Foo {}".getBytes(StandardCharsets.UTF_8)));

        assertEquals("class Foo {}", storageService.readText(projectId, "src/main/Foo.java"));

        GetObjectRequest request = singleRequest("getObjectAsBytes", GetObjectRequest.class);
        assertEquals(BUCKET, request.bucket());
        assertEquals(keyPrefix() + "/src/main/Foo.java", request.key());
    }

    @Test
    void mapsMissingObjectsToNoSuchFile() {
        fakeS3.script("getObjectAsBytes", NoSuchKeyException.builder().message("missing").build());

        assertThrows(NoSuchFileException.class,
                () -> storageService.readText(projectId, "src/missing.java"));
    }

    @Test
    void existsIsTrueWhenTheObjectIsPresent() {
        assertTrue(storageService.exists(projectId, "src/main/Foo.java"));

        HeadObjectRequest request = singleRequest("headObject", HeadObjectRequest.class);
        assertEquals(BUCKET, request.bucket());
        assertEquals(keyPrefix() + "/src/main/Foo.java", request.key());
    }

    @Test
    void existsIsFalseOn404() {
        fakeS3.script("headObject", S3Exception.builder().statusCode(404).message("Not Found").build());

        assertFalse(storageService.exists(projectId, "src/main/Foo.java"));
    }

    @Test
    void existsRethrowsUnexpectedS3Errors() {
        fakeS3.script("headObject", S3Exception.builder().statusCode(403).message("Forbidden").build());

        assertThrows(S3Exception.class, () -> storageService.exists(projectId, "src/main/Foo.java"));
    }

    @Test
    void rejectsTraversalPathsBeforeCallingS3() {
        assertThrows(IllegalArgumentException.class,
                () -> storageService.readBytes(projectId, "../escape.txt"));
        assertThrows(IllegalArgumentException.class,
                () -> storageService.writeText(projectId, "/etc/passwd", "nope"));

        assertTrue(fakeS3.hasNoCalls());
    }

    @Test
    void stagedUploadsNeverTouchS3() throws IOException {
        MockMultipartFile upload = new MockMultipartFile(
                "file", "project.zip", "application/zip", "zip".getBytes(StandardCharsets.UTF_8));

        Path staged = storageService.storeZip(upload, projectId);

        assertEquals("zip", Files.readString(staged, StandardCharsets.UTF_8));
        storageService.discardStagingFile(staged);
        assertFalse(Files.exists(staged));
        assertTrue(fakeS3.hasNoCalls());
    }

    @Test
    void deletesEveryObjectUnderTheProjectPrefixAcrossPages() {
        String prefix = keyPrefix() + "/";
        fakeS3.script("listObjectsV2", ListObjectsV2Response.builder()
                .contents(S3Object.builder().key(prefix + "src/main/Foo.java").size(12L).build())
                .isTruncated(true)
                .nextContinuationToken("token-1")
                .build());
        fakeS3.script("listObjectsV2", ListObjectsV2Response.builder()
                .contents(S3Object.builder()
                        .key(prefix + StorageService.GENERATED_DIRECTORY + "/report.md").size(9L).build())
                .isTruncated(false)
                .build());

        storageService.deleteProject(projectId);

        List<Object[]> listCalls = fakeS3.argumentListsTo("listObjectsV2");
        assertEquals(2, listCalls.size());
        ListObjectsV2Request firstPage = (ListObjectsV2Request) listCalls.get(0)[0];
        ListObjectsV2Request secondPage = (ListObjectsV2Request) listCalls.get(1)[0];
        assertEquals(BUCKET, firstPage.bucket());
        assertEquals(prefix, firstPage.prefix());
        assertEquals("token-1", secondPage.continuationToken());

        DeleteObjectsRequest delete = singleRequest("deleteObjects", DeleteObjectsRequest.class);
        assertEquals(BUCKET, delete.bucket());
        assertEquals(List.of(prefix + "src/main/Foo.java",
                        prefix + StorageService.GENERATED_DIRECTORY + "/report.md"),
                delete.delete().objects().stream().map(ObjectIdentifier::key).toList());
    }

    @Test
    void skipsTheBatchDeleteWhenTheProjectHasNoObjects() {
        fakeS3.script("listObjectsV2",
                ListObjectsV2Response.builder().isTruncated(false).build());

        storageService.deleteProject(projectId);

        assertTrue(fakeS3.callsTo("deleteObjects").isEmpty());
    }

    @Test
    void listsGeneratedFilesRelativeToTheGeneratedDirectory() {
        String prefix = keyPrefix() + "/" + StorageService.GENERATED_DIRECTORY + "/";
        fakeS3.script("listObjectsV2", ListObjectsV2Response.builder()
                .isTruncated(false)
                .contents(
                        S3Object.builder().key(prefix + "b.md").size(2L).build(),
                        S3Object.builder().key(prefix + "a.md").size(1L).build())
                .build());

        List<StorageService.GeneratedFile> generated = storageService.listGeneratedFiles(projectId);

        assertEquals(List.of("a.md", "b.md"),
                generated.stream().map(StorageService.GeneratedFile::name).toList());
        assertEquals(1L, generated.get(0).sizeBytes());

        ListObjectsV2Request request = singleRequest("listObjectsV2", ListObjectsV2Request.class);
        assertEquals(prefix, request.prefix());
    }

    @Test
    void listsNoGeneratedFilesWhenNothingWasGenerated() {
        fakeS3.script("listObjectsV2",
                ListObjectsV2Response.builder().isTruncated(false).build());

        assertTrue(storageService.listGeneratedFiles(projectId).isEmpty());
    }

    @Test
    void failsFastWhenTheBucketIsNotConfigured() {
        S3StorageService service = new S3StorageService(new AppProperties(), fakeS3.client());

        assertThrows(IllegalStateException.class, service::validateConfiguration);
    }

    @Test
    void acceptsAConfiguredBucket() {
        storageService.validateConfiguration();
    }

    private <T> T singleRequest(String methodName, Class<T> requestType) {
        List<Object[]> argumentLists = fakeS3.argumentListsTo(methodName);
        assertEquals(1, argumentLists.size(), "expected exactly one " + methodName + " call");
        return requestType.cast(argumentLists.get(0)[0]);
    }

    /**
     * Hand-written stand-in for {@link S3Client}. Mockito cannot mock the AWS SDK
     * client on every JDK this project builds with: the type hierarchy reaches JDK
     * interfaces such as {@link AutoCloseable}, which the inline mock maker refuses
     * to retransform on newer JDKs. A JDK dynamic proxy records the calls and
     * replays scripted outcomes instead, and unscripted methods simply return null,
     * which is all the production code needs from them.
     */
    private static final class RecordingS3Client implements InvocationHandler {

        private final List<Object[]> calls = new ArrayList<>();
        private final Map<String, Deque<Object>> scripted = new HashMap<>();

        S3Client client() {
            return (S3Client) Proxy.newProxyInstance(
                    S3Client.class.getClassLoader(), new Class<?>[] {S3Client.class}, this);
        }

        /** Queues one outcome - a return value or a {@link Throwable} - for the next call. */
        void script(String methodName, Object outcome) {
            scripted.computeIfAbsent(methodName, key -> new ArrayDeque<>()).add(outcome);
        }

        List<Object[]> callsTo(String methodName) {
            return calls.stream()
                    .filter(call -> call[0].equals(methodName))
                    .toList();
        }

        /** The argument arrays of every recorded call to one method, in call order. */
        List<Object[]> argumentListsTo(String methodName) {
            return callsTo(methodName).stream()
                    .map(call -> (Object[]) call[1])
                    .toList();
        }

        boolean hasNoCalls() {
            return calls.isEmpty();
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
            String name = method.getName();
            if (method.getDeclaringClass() == Object.class) {
                return switch (name) {
                    case "toString" -> "RecordingS3Client";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> null;
                };
            }

            calls.add(new Object[] {name, arguments});

            Deque<Object> outcomes = scripted.get(name);
            if (outcomes == null || outcomes.isEmpty()) {
                return null;
            }
            Object outcome = outcomes.poll();
            if (outcome instanceof Throwable throwable) {
                throw throwable;
            }
            return outcome;
        }
    }
}
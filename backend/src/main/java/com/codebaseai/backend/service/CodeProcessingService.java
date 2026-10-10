package com.codebaseai.backend.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.codebaseai.backend.model.CodeChunk;
import com.codebaseai.backend.model.CodeReference;
import com.codebaseai.backend.model.CodeSymbol;
import com.codebaseai.backend.model.ProjectFile;
import com.codebaseai.backend.repository.CodeChunkRepository;
import com.codebaseai.backend.repository.CodeReferenceRepository;
import com.codebaseai.backend.repository.CodeSymbolRepository;
import com.codebaseai.backend.repository.ProjectFileRepository;
import com.codebaseai.backend.service.chunking.ParsedFile;
import com.codebaseai.backend.storage.StorageService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class CodeProcessingService {

    public record ProcessingResult(List<String> failedFiles) {
        public boolean hasFailures() {
            return !failedFiles.isEmpty();
        }
    }

    /**
     * Thrown by a {@link ProgressCallback} to abort processing early, e.g. when
     * the indexing job was cancelled. Unchecked so the callback stays a clean
     * functional interface; it unwinds {@link #processProject(UUID, ProgressCallback)}
     * without being swallowed by the per-file parse/IO handlers.
     */
    public static class ProcessingCancelledException extends RuntimeException {
        public ProcessingCancelledException(String message) {
            super(message);
        }
    }

    /**
     * Per-batch progress hook. Receives the percent (0-100) of the file set
     * processed so far, checked between files so a long run can report progress
     * and stop promptly on cancellation.
     */
    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(int percentDone) throws ProcessingCancelledException;
    }

    private final ProjectFileRepository projectFileRepository;
    private final CodeChunkRepository codeChunkRepository;
    private final CodeSymbolRepository codeSymbolRepository;
    private final CodeReferenceRepository codeReferenceRepository;
    private final CodeChunkingService chunkingService;
    private final AiServiceClient aiServiceClient;
    private final StorageService storageService;

    /**
     * NOT @Transactional: the embedding calls are remote and can take minutes, so
     * holding one DB transaction (and one pooled connection) across the project is
     * not acceptable. Every save commits on its own.
     *
     * <p>Kept for callers that do not need progress or cancellation; delegates to
     * {@link #processProject(UUID, ProgressCallback)} with a no-op callback.
     */
    public ProcessingResult processProject(UUID projectId) {
        return processProject(projectId, percent -> { });
    }

    /**
     * Same as {@link #processProject(UUID)} but reports progress and honours
     * cancellation between files.
     *
     * <p>The callback is invoked once per file with the percent of the file set
     * done so far, so the worker can heartbeat its lease. If the callback throws
     * {@link ProcessingCancelledException} (e.g. the job was cancelled), the loop
     * stops at once and the exception propagates to the worker, which then purges
     * the partial index. Ordinary per-file parse/IO failures are still collected
     * into {@link ProcessingResult} and do not abort the run.
     */
    public ProcessingResult processProject(UUID projectId, ProgressCallback callback) {
        log.info("Starting processing for project: {}", projectId);

        List<ProjectFile> files = projectFileRepository.findByProjectId(projectId);
        log.info("Found {} files to process", files.size());

        int totalChunks = 0;
        List<String> failedFiles = new ArrayList<>();
        int total = files.size();

        for (int index = 0; index < total; index++) {
            ProjectFile file = files.get(index);
            try {
                // Same project-relative path the file was indexed under, so the
                // local backend and S3 resolve it identically.
                String content = storageService.readText(projectId, file.getPath());

                CodeChunkingService.IndexedFile indexed;
                try {
                    indexed = chunkingService.index(content, file.getPath());
                } catch (RuntimeException e) {
                    // One unparseable file must not fail the whole project; the
                    // rest of the codebase stays searchable.
                    log.error("Failed to parse file: {}", file.getPath(), e);
                    failedFiles.add(file.getPath());
                    continue;
                }

                List<CodeChunkingService.Chunk> chunks = indexed.chunks();
                List<List<Double>> embeddings = chunks.isEmpty()
                        ? List.of()
                        : aiServiceClient.generateEmbeddings(
                                chunks.stream().map(CodeChunkingService.Chunk::getContent).toList());

                for (int i = 0; i < chunks.size(); i++) {
                    CodeChunkingService.Chunk chunk = chunks.get(i);
                    CodeChunk codeChunk = new CodeChunk();
                    codeChunk.setFileId(file.getId());
                    codeChunk.setProjectId(projectId);
                    codeChunk.setContent(chunk.getContent());
                    codeChunk.setStartLine(chunk.getStartLine());
                    codeChunk.setEndLine(chunk.getEndLine());
                    codeChunk.setChunkType(chunk.getChunkType());
                    codeChunk.setSymbol(chunk.getSymbol());
                    codeChunk.setParentSymbol(chunk.getParentSymbol());
                    codeChunk.setTokenCount(chunk.getTokenCount());
                    codeChunk.setEmbedding(toFloatArray(embeddings.get(i)));

                    codeChunkRepository.save(codeChunk);
                    totalChunks++;
                }

                persistSymbols(projectId, file, indexed.symbols());
                persistReferences(projectId, file, indexed.references());

                log.info("Processed file: {} ({} chunks)", file.getPath(), chunks.size());

            } catch (IOException e) {
                log.error("Failed to process file: {}", file.getPath(), e);
                failedFiles.add(file.getPath());
            }

            // Between files (the "batch" boundary): report progress and let the
            // worker renew its lease / notice a cancellation. May throw
            // ProcessingCancelledException, which unwinds the loop.
            int percentDone = total == 0 ? 100 : (int) (((long) (index + 1) * 100) / total);
            callback.onProgress(percentDone);
        }

        log.info("Processing complete for project: {}. Total chunks: {}", projectId, totalChunks);
        return new ProcessingResult(List.copyOf(failedFiles));
    }

    private void persistSymbols(UUID projectId, ProjectFile file, List<ParsedFile.Symbol> symbols) {
        for (ParsedFile.Symbol symbol : symbols) {
            CodeSymbol entity = new CodeSymbol();
            entity.setProjectId(projectId);
            entity.setFileId(file.getId());
            entity.setName(symbol.name());
            entity.setKind(symbol.kind());
            entity.setParentSymbol(symbol.parentSymbol());
            entity.setStartLine(symbol.startLine());
            entity.setEndLine(symbol.endLine());
            entity.setSignature(symbol.signature());
            codeSymbolRepository.save(entity);
        }
    }

    private void persistReferences(UUID projectId, ProjectFile file, List<ParsedFile.Reference> references) {
        for (ParsedFile.Reference reference : references) {
            CodeReference entity = new CodeReference();
            entity.setProjectId(projectId);
            entity.setFileId(file.getId());
            entity.setFromSymbol(reference.fromSymbol());
            entity.setToName(reference.toName());
            entity.setKind(reference.kind());
            entity.setLine(reference.line());
            entity.setDynamic(reference.dynamic());
            codeReferenceRepository.save(entity);
        }
    }

    private float[] toFloatArray(List<Double> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i).floatValue();
        }
        return arr;
    }
}

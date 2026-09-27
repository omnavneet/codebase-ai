package com.codebaseai.backend.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class CodeProcessingService {

    private final ProjectFileRepository projectFileRepository;
    private final CodeChunkRepository codeChunkRepository;
    private final CodeSymbolRepository codeSymbolRepository;
    private final CodeReferenceRepository codeReferenceRepository;
    private final CodeChunkingService chunkingService;
    private final AiServiceClient aiServiceClient;
    private final FileStorageService fileStorageService;

    /**
     * NOT @Transactional: the embedding calls are remote and can take minutes, so
     * holding one DB transaction (and one pooled connection) across the project is
     * not acceptable. Every save commits on its own.
     */
    public void processProject(UUID projectId) {
        log.info("Starting processing for project: {}", projectId);

        List<ProjectFile> files = projectFileRepository.findByProjectId(projectId);
        log.info("Found {} files to process", files.size());

        int totalChunks = 0;

        for (ProjectFile file : files) {
            try {
                Path filePath = fileStorageService.getProjectDirectory(projectId)
                        .resolve(file.getPath());
                String content = Files.readString(filePath, StandardCharsets.UTF_8);

                CodeChunkingService.IndexedFile indexed;
                try {
                    indexed = chunkingService.index(content, file.getPath());
                } catch (RuntimeException e) {
                    // One unparseable file must not fail the whole project; the
                    // rest of the codebase stays searchable.
                    log.error("Failed to parse file: {}", file.getPath(), e);
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
            }
        }

        log.info("Processing complete for project: {}. Total chunks: {}", projectId, totalChunks);
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

package com.codebaseai.backend.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.codebaseai.backend.model.CodeChunk;
import com.codebaseai.backend.model.ProjectFile;
import com.codebaseai.backend.repository.CodeChunkRepository;
import com.codebaseai.backend.repository.ProjectFileRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class CodeProcessingService {
    
    private final ProjectFileRepository projectFileRepository;
    private final CodeChunkRepository codeChunkRepository;
    private final CodeChunkingService chunkingService;
    private final AiServiceClient aiServiceClient;
    private final FileStorageService fileStorageService;
    
    @Transactional
    public void processProject(UUID projectId) {
        log.info("Starting processing for project: {}", projectId);
        
        // Get all files for project
        List<ProjectFile> files = projectFileRepository.findByProjectId(projectId);
        log.info("Found {} files to process", files.size());
        
        int totalChunks = 0;
        
        for (ProjectFile file : files) {
            try {
                // Read file content
                Path filePath = fileStorageService.getProjectDirectory(projectId)
                        .resolve(file.getPath());
                String content = Files.readString(filePath, StandardCharsets.UTF_8);
                
                // Chunk the code
                List<CodeChunkingService.Chunk> chunks = chunkingService.chunkCode(
                    content, 
                    file.getPath()
                );
                
                // Generate embeddings in batches
                List<String> chunkTexts = chunks.stream()
                        .map(CodeChunkingService.Chunk::getContent)
                        .collect(Collectors.toList());
                
                List<List<Double>> embeddings = aiServiceClient.generateEmbeddings(chunkTexts);
                
                // Store chunks with embeddings
                for (int i = 0; i < chunks.size(); i++) {
                    CodeChunkingService.Chunk chunk = chunks.get(i);
                    List<Double> embedding = embeddings.get(i);
                    
                    CodeChunk codeChunk = new CodeChunk();
                    codeChunk.setFileId(file.getId());
                    codeChunk.setProjectId(projectId);
                    codeChunk.setContent(chunk.getContent());
                    codeChunk.setStartLine(chunk.getStartLine());
                    codeChunk.setEndLine(chunk.getEndLine());
                    codeChunk.setTokenCount(estimateTokens(chunk.getContent()));
                    codeChunk.setEmbedding(toFloatArray(embedding));
                    
                    codeChunkRepository.save(codeChunk);
                    totalChunks++;
                }
                
                log.info("Processed file: {} ({} chunks)", file.getPath(), chunks.size());
                
            } catch (IOException e) {
                log.error("Failed to process file: {}", file.getPath(), e);
            }
        }
        
        log.info("Processing complete for project: {}. Total chunks: {}", projectId, totalChunks);
    }
    
    private int estimateTokens(String text) {
        // Rough estimate: 4 characters per token
        return text.length() / 4;
    }
    
    private float[] toFloatArray(List<Double> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i).floatValue();
        }
        return arr;
    }
}
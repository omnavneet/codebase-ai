package com.codebaseai.backend.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class CodeChunkingService {
    
    private static final int MAX_CHUNK_SIZE = 500; // characters
    private static final int OVERLAP = 50; // characters overlap
    
    public List<Chunk> chunkCode(String code, String filePath) {
        List<Chunk> chunks = new ArrayList<>();
        
        // If file is small enough, keep as single chunk
        if (code.length() <= MAX_CHUNK_SIZE) {
            chunks.add(new Chunk(code, 1, getTotalLines(code)));
            return chunks;
        }
        
        // Split by lines first
        String[] lines = code.split("\n");
        StringBuilder currentChunk = new StringBuilder();
        int startLine = 1;
        int currentLine = 1;
        
        for (String line : lines) {
            // If adding this line exceeds max size, create new chunk
            if (currentChunk.length() + line.length() > MAX_CHUNK_SIZE) {
                if (currentChunk.length() > 0) {
                    chunks.add(new Chunk(
                        currentChunk.toString(),
                        startLine,
                        currentLine - 1
                    ));
                    
                    // Start new chunk with overlap
                    currentChunk = new StringBuilder();
                    startLine = currentLine;
                }
            }
            
            currentChunk.append(line).append("\n");
            currentLine++;
        }
        
        // Add last chunk
        if (currentChunk.length() > 0) {
            chunks.add(new Chunk(
                currentChunk.toString(),
                startLine,
                currentLine - 1
            ));
        }
        
        return chunks;
    }
    
    private int getTotalLines(String code) {
        return code.split("\n").length;
    }
    
    // Inner class for chunk data
    public static class Chunk {
        private final String content;
        private final int startLine;
        private final int endLine;
        
        public Chunk(String content, int startLine, int endLine) {
            this.content = content;
            this.startLine = startLine;
            this.endLine = endLine;
        }
        
        public String getContent() { return content; }
        public int getStartLine() { return startLine; }
        public int getEndLine() { return endLine; }
    }
}
package com.codebaseai.backend.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.codebaseai.backend.config.AppProperties;
import com.codebaseai.backend.service.chunking.BraceScanner;
import com.codebaseai.backend.service.chunking.CodeChunker;
import com.codebaseai.backend.service.chunking.FallbackScanner;
import com.codebaseai.backend.service.chunking.Language;
import com.codebaseai.backend.service.chunking.LanguageScanner;
import com.codebaseai.backend.service.chunking.ParsedFile;
import com.codebaseai.backend.service.chunking.PythonScanner;
import com.codebaseai.backend.service.chunking.SourceLines;

import lombok.RequiredArgsConstructor;

/**
 * Turns one source file into the chunks that get embedded, plus the symbols and
 * call/import references that power symbol lookups and call-graph queries.
 *
 * <p>The parser is chosen from the detected language. Languages without a
 * symbol-aware parser are still chunked structurally and reported as such — they
 * never produce guessed symbols or call edges.
 */
@Service
@RequiredArgsConstructor
public class CodeChunkingService {

    private final AppProperties properties;

    /** A chunk ready to be embedded, with the metadata stored alongside it. */
    public static class Chunk {
        private final String content;
        private final int startLine;
        private final int endLine;
        private final String chunkType;
        private final String symbol;
        private final String parentSymbol;
        private final int tokenCount;

        Chunk(String content, int startLine, int endLine, String chunkType,
              String symbol, String parentSymbol, int tokenCount) {
            this.content = content;
            this.startLine = startLine;
            this.endLine = endLine;
            this.chunkType = chunkType;
            this.symbol = symbol;
            this.parentSymbol = parentSymbol;
            this.tokenCount = tokenCount;
        }

        public String getContent() { return content; }
        public int getStartLine() { return startLine; }
        public int getEndLine() { return endLine; }
        public String getChunkType() { return chunkType; }
        public String getSymbol() { return symbol; }
        public String getParentSymbol() { return parentSymbol; }
        public int getTokenCount() { return tokenCount; }
    }

    /** Detects a file's language (stored on the file row for honest reporting). */
    public Language detectLanguage(String filePath) {
        return Language.fromPath(filePath);
    }

    /** Everything the indexing pipeline needs for one file, from a single parse. */
    public record IndexedFile(
            List<Chunk> chunks,
            List<ParsedFile.Symbol> symbols,
            List<ParsedFile.Reference> references,
            Language language) {
    }

    /**
     * Chunks a file semantically and collects its symbols and references in one
     * pass, so ingest never parses the same source twice.
     */
    public IndexedFile index(String code, String filePath) {
        String safeCode = code == null ? "" : code;
        Language language = Language.fromPath(filePath);
        List<String> lines = SourceLines.split(safeCode);
        if (lines.isEmpty()) {
            return new IndexedFile(List.of(), List.of(), List.of(), language);
        }

        ParsedFile parsed = scannerFor(language).parse(safeCode, filePath);
        CodeChunker chunker = new CodeChunker(
                properties.getChunking().getMaxChars(),
                properties.getChunking().getMinChars(),
                properties.getChunking().getOverlapLines());

        List<Chunk> chunks = new ArrayList<>();
        for (CodeChunker.Chunk chunk : chunker.chunk(lines, parsed.blocks())) {
            if (chunk.content().isBlank()) {
                continue;
            }
            chunks.add(new Chunk(chunk.content(), chunk.startLine(), chunk.endLine(), chunk.chunkType(),
                    chunk.symbol(), chunk.parentSymbol(), chunk.tokenCount()));
        }
        return new IndexedFile(chunks, parsed.symbols(), parsed.references(), parsed.language());
    }

    private LanguageScanner scannerFor(Language language) {
        return switch (language) {
            case JAVA, JAVASCRIPT, TYPESCRIPT, TSX, GO -> new BraceScanner(language);
            case PYTHON -> new PythonScanner();
            default -> new FallbackScanner(language);
        };
    }
}

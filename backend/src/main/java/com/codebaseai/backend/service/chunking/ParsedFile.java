package com.codebaseai.backend.service.chunking;

import java.util.List;

/**
 * Result of parsing one source file: the structural blocks that become chunks,
 * the symbols declared in it, and the raw call/import references found.
 */
public record ParsedFile(
        Language language,
        List<Block> blocks,
        List<Symbol> symbols,
        List<Reference> references) {

    public static final String KIND_CALL = "call";
    public static final String KIND_IMPORT = "import";

    /** Block kinds written to {@code chunks.chunk_type}. */
    public static final String BLOCK_FUNCTION = "function";
    public static final String BLOCK_METHOD = "method";
    public static final String BLOCK_CLASS = "class";
    public static final String BLOCK_INTERFACE = "interface";
    public static final String BLOCK_ENUM = "enum";
    public static final String BLOCK_IMPORTS = "imports";
    public static final String BLOCK_MODULE = "module";
    public static final String BLOCK_PART = "part";

    public static ParsedFile empty(Language language) {
        return new ParsedFile(language, List.of(), List.of(), List.of());
    }

    /**
     * A structural region of the file. {@code name}/{@code parentSymbol} are null
     * for regions that do not declare anything (import runs, loose statements).
     */
    public record Block(String kind, String name, String parentSymbol, int startLine, int endLine) {
    }

    /** A declared name with the exact line range of its body. */
    public record Symbol(
            String name, String kind, String parentSymbol, int startLine, int endLine, String signature) {
    }

    /** A call site or import, exactly as written in the source. */
    public record Reference(int line, String toName, String kind, boolean dynamic, String fromSymbol) {
    }
}

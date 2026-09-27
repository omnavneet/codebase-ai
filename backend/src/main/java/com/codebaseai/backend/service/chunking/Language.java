package com.codebaseai.backend.service.chunking;

import java.util.Locale;

/**
 * Source languages we can identify, plus an honest statement of what the
 * scanners can extract for each. Languages with {@code supportsSymbols=false}
 * are still chunked (by paragraphs) and stored with their real language id, but
 * they are reported as "no symbol support" instead of producing guessed data.
 */
public enum Language {

    JAVA("java", true),
    JAVASCRIPT("javascript", true),
    TYPESCRIPT("typescript", true),
    TSX("tsx", true),
    PYTHON("python", true),
    GO("go", true),
    // Static analysis for these dialects is not implemented: they are chunked by
    // structure but must not report symbols or call edges we cannot verify.
    CSHARP("csharp", false),
    KOTLIN("kotlin", false),
    RUBY("ruby", false),
    RUST("rust", false),
    PHP("php", false),
    C("c", false),
    CPP("cpp", false),
    HTML("html", false),
    CSS("css", false),
    SCSS("scss", false),
    SQL("sql", false),
    SHELL("shell", false),
    YAML("yaml", false),
    JSON("json", false),
    MARKDOWN("markdown", false),
    UNKNOWN("unknown", false);

    private final String id;
    private final boolean supportsSymbols;

    Language(String id, boolean supportsSymbols) {
        this.id = id;
        this.supportsSymbols = supportsSymbols;
    }

    public String getId() {
        return id;
    }

    public boolean supportsSymbols() {
        return supportsSymbols;
    }

    /** Detects the language from a file path, never guessing for unknown types. */
    public static Language fromPath(String filePath) {
        if (filePath == null) {
            return UNKNOWN;
        }
        int dot = filePath.lastIndexOf('.');
        if (dot < 0 || dot == filePath.length() - 1) {
            return UNKNOWN;
        }
        String extension = filePath.substring(dot + 1).toLowerCase(Locale.ROOT);
        return switch (extension) {
            case "java" -> JAVA;
            case "js", "mjs", "cjs" -> JAVASCRIPT;
            case "ts", "mts", "cts" -> TYPESCRIPT;
            case "jsx", "tsx" -> TSX;
            case "py", "pyi" -> PYTHON;
            case "go" -> GO;
            case "cs" -> CSHARP;
            case "kt", "kts" -> KOTLIN;
            case "rb" -> RUBY;
            case "rs" -> RUST;
            case "php" -> PHP;
            case "c", "h" -> C;
            case "cpp", "cc", "cxx", "hpp" -> CPP;
            case "html", "htm" -> HTML;
            case "css" -> CSS;
            case "scss", "sass" -> SCSS;
            case "sql" -> SQL;
            case "sh", "bash", "zsh" -> SHELL;
            case "yml", "yaml" -> YAML;
            case "json" -> JSON;
            case "md", "markdown" -> MARKDOWN;
            default -> UNKNOWN;
        };
    }
}

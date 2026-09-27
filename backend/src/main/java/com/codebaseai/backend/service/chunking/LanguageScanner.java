package com.codebaseai.backend.service.chunking;

/** Turns one source file into blocks/symbols/references. Implementations must never invent data. */
public interface LanguageScanner {

    ParsedFile parse(String fileContent, String filePath);
}

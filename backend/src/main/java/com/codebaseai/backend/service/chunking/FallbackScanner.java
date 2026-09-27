package com.codebaseai.backend.service.chunking;

import java.util.ArrayList;
import java.util.List;

/**
 * Last-resort scanner for languages without a symbol-aware parser: splits on
 * blank lines so chunks still follow the author's structure, and reports no
 * symbols rather than guessing at declarations.
 */
public class FallbackScanner implements LanguageScanner {

    private static final int MAX_LINES_PER_BLOCK = 40;

    private final Language language;

    public FallbackScanner(Language language) {
        this.language = language;
    }

    @Override
    public ParsedFile parse(String fileContent, String filePath) {
        List<String> lines = SourceLines.split(fileContent);
        List<ParsedFile.Block> blocks = new ArrayList<>();
        int start = 1;

        for (int line = 1; line <= lines.size(); line++) {
            boolean blank = lines.get(line - 1).isBlank();
            boolean last = line == lines.size();
            boolean tooLong = line - start + 1 >= MAX_LINES_PER_BLOCK;
            if (last || ((blank || tooLong) && line > start)) {
                blocks.add(new ParsedFile.Block(ParsedFile.BLOCK_MODULE, null, null, start, line));
                start = line + 1;
            }
        }

        return new ParsedFile(language, blocks, List.of(), List.of());
    }
}

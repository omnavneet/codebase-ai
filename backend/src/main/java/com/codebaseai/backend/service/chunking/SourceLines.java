package com.codebaseai.backend.service.chunking;

import java.util.ArrayList;
import java.util.List;

/**
 * Line handling shared by every scanner.
 *
 * <p>Chunk text is always produced from the original lines (joined with '\n'),
 * so a chunk's content is exactly the region it claims to cover and line
 * numbers in citations stay meaningful.
 */
public final class SourceLines {

    private SourceLines() {
    }

    public static List<String> split(String content) {
        List<String> lines = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return lines;
        }
        int start = 0;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                int end = i;
                if (end > start && content.charAt(end - 1) == '\r') {
                    end--;
                }
                lines.add(content.substring(start, end));
                start = i + 1;
            }
        }
        if (start < content.length()) {
            lines.add(content.substring(start));
        }
        return lines;
    }

    /** Content of lines {@code startLine..endLine} (1-based, inclusive) as originally written. */
    public static String contentOf(List<String> lines, int startLine, int endLine) {
        StringBuilder builder = new StringBuilder();
        for (int line = startLine; line <= endLine && line <= lines.size(); line++) {
            builder.append(lines.get(line - 1)).append('\n');
        }
        return builder.toString();
    }

    /** Number of leading spaces/tabs of a line. */
    public static int indentOf(String line) {
        int count = 0;
        while (count < line.length() && (line.charAt(count) == ' ' || line.charAt(count) == '\t')) {
            count++;
        }
        return count;
    }

    public static String trimSignature(String line) {
        String trimmed = line.trim();
        return trimmed.length() > 500 ? trimmed.substring(0, 500) : trimmed;
    }
}

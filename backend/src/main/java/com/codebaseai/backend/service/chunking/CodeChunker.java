package com.codebaseai.backend.service.chunking;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns parsed blocks into embedding-sized chunks.
 *
 * <p>Rules, in order: adjacent shell/gap blocks (and blocks that are each below
 * {@code minChars}) are merged while the result stays under {@code maxChars}, so
 * an import run or a class shell does not become a dozen useless embeddings;
 * oversized blocks are split at line boundaries with {@code overlapLines} lines
 * of shared context so a long function still reads coherently. Chunk text is
 * always the exact original lines of its range — nothing is reflowed or
 * prefixed, so citations and re-reads stay accurate.
 */
public class CodeChunker {

    private final int maxChars;
    private final int minChars;
    private final int overlapLines;

    public CodeChunker(int maxChars, int minChars, int overlapLines) {
        this.maxChars = Math.max(200, maxChars);
        this.minChars = Math.max(1, Math.min(minChars, this.maxChars));
        this.overlapLines = Math.max(0, overlapLines);
    }

    public record Chunk(
            String content,
            int startLine,
            int endLine,
            String chunkType,
            String symbol,
            String parentSymbol,
            int tokenCount) {
    }

    public List<Chunk> chunk(List<String> lines, List<ParsedFile.Block> blocks) {
        List<Chunk> chunks = new ArrayList<>();
        for (List<ParsedFile.Block> group : groupBlocks(lines, blocks)) {
            ParsedFile.Block first = group.get(0);
            ParsedFile.Block last = group.get(group.size() - 1);
            String type = mergedKind(group);
            String symbol = firstNamed(group, true);
            String parent = firstNamed(group, false);
            int length = SourceLines.contentOf(lines, first.startLine(), last.endLine()).length();

            if (length <= maxChars) {
                chunks.add(build(lines, first.startLine(), last.endLine(), type, symbol, parent));
            } else {
                chunks.addAll(split(lines, first.startLine(), last.endLine(), type, symbol, parent));
            }
        }
        return chunks;
    }

    private List<List<ParsedFile.Block>> groupBlocks(List<String> lines, List<ParsedFile.Block> blocks) {
        List<List<ParsedFile.Block>> groups = new ArrayList<>();
        List<ParsedFile.Block> current = new ArrayList<>();
        int currentLength = 0;

        for (ParsedFile.Block block : blocks) {
            if (current.isEmpty()) {
                current.add(block);
                currentLength = length(lines, block);
                continue;
            }
            ParsedFile.Block previous = current.get(current.size() - 1);
            int gapLines = Math.max(0, block.startLine() - previous.endLine() - 1);
            int combined = currentLength + length(lines, block) + gapLines;
            boolean bothShells = block.name() == null && previous.name() == null;
            boolean bothTiny = length(lines, block) < minChars && currentLength < minChars;
            if (combined <= maxChars && (bothShells || bothTiny)) {
                current.add(block);
                currentLength = combined;
            } else {
                groups.add(current);
                current = new ArrayList<>();
                current.add(block);
                currentLength = length(lines, block);
            }
        }
        if (!current.isEmpty()) {
            groups.add(current);
        }
        return groups;
    }

    private List<Chunk> split(
            List<String> lines, int from, int to, String type, String symbol, String parent) {
        List<Chunk> chunks = new ArrayList<>();
        int pieceStart = from;
        int cursor = from;

        while (cursor <= to) {
            int pieceEnd = cursor;
            int length = 0;
            while (pieceEnd <= to) {
                int lineLength = lines.get(pieceEnd - 1).length() + 1;
                if (length + lineLength > maxChars) {
                    break;
                }
                length += lineLength;
                pieceEnd++;
            }
            int lastLine = pieceEnd - 1;
            if (lastLine < pieceStart) {
                // A single line longer than the budget cannot be split at a line
                // boundary: it becomes its own chunk rather than a mangled slice.
                lastLine = pieceStart;
            }

            chunks.add(build(lines, pieceStart, lastLine, ParsedFile.BLOCK_PART, symbol, parent));
            if (lastLine >= to) {
                break;
            }
            pieceStart = Math.max(from, Math.max(pieceStart + 1, lastLine + 1 - overlapLines));
            cursor = pieceStart;
        }
        return chunks;
    }

    private Chunk build(
            List<String> lines, int startLine, int endLine, String type, String symbol, String parent) {
        String content = SourceLines.contentOf(lines, startLine, endLine);
        return new Chunk(content, startLine, endLine, type, symbol, parent, estimateTokens(content));
    }

    private static int length(List<String> lines, ParsedFile.Block block) {
        return SourceLines.contentOf(lines, block.startLine(), block.endLine()).length();
    }

    private static String mergedKind(List<ParsedFile.Block> group) {
        Set<String> kinds = new LinkedHashSet<>();
        for (ParsedFile.Block block : group) {
            kinds.add(block.kind());
        }
        return kinds.size() == 1 ? kinds.iterator().next() : ParsedFile.BLOCK_MODULE;
    }

    private static String firstNamed(List<ParsedFile.Block> group, boolean wantName) {
        for (ParsedFile.Block block : group) {
            if (block.name() != null) {
                return wantName ? block.name() : block.parentSymbol();
            }
        }
        return null;
    }

    /** Rough token estimate (4 characters per token), recorded per chunk. */
    public static int estimateTokens(String text) {
        return text.length() / 4;
    }
}

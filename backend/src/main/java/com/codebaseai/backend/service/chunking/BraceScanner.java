package com.codebaseai.backend.service.chunking;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scanner for brace-delimited languages (Java, JavaScript/TypeScript, Go).
 *
 * <p>How it stays honest: declarations come from real declaration syntax at a
 * known brace depth (never from guessing), block ranges come from matching
 * braces, and call sites are recorded exactly as written. No type inference
 * happens here — unresolved and dynamic references are labelled by the resolver
 * instead of being dropped or invented.
 */
public class BraceScanner implements LanguageScanner {

    private static final Pattern JAVA_TYPE = Pattern.compile(
            "^(?:@\\w+(?:\\([^)]*\\))?\\s*)*(?:(?:public|protected|private|static|final|abstract|sealed|non-sealed|strictfp)\\s+)*"
                    + "(class|interface|enum|record)\\s+(\\w+)");
    private static final Pattern JAVA_METHOD = Pattern.compile(
            "^(?:@\\w+(?:\\([^)]*\\))?\\s*)*(?:(?:public|protected|private|static|final|abstract|synchronized|native|default|strictfp)\\s+)*"
                    + "[\\w<>\\[\\],.?]+\\s+(\\w+)\\s*\\([^;{]*\\)\\s*(?:throws\\s+[\\w\\s,.<>]+)?\\{?\\s*$");

    private static final Pattern JS_FUNCTION = Pattern.compile(
            "^(?:export\\s+)?(?:default\\s+)?(?:async\\s+)?function\\s+(\\w+)");
    private static final Pattern JS_CLASS = Pattern.compile(
            "^(?:export\\s+)?(?:default\\s+)?(?:abstract\\s+)?class\\s+(\\w+)");
    private static final Pattern JS_INTERFACE = Pattern.compile(
            "^(?:export\\s+)?(?:declare\\s+)?interface\\s+(\\w+)");
    private static final Pattern JS_ARROW = Pattern.compile(
            "^(?:export\\s+)?(?:const|let|var)\\s+(\\w+)\\s*=\\s*(?:async\\s*)?(?:function\\b|\\([^)]*\\)\\s*=>|[A-Za-z_$][\\w$]*\\s*=>)");
    private static final Pattern JS_OBJECT_ARROW = Pattern.compile(
            "^\\s*(\\w+)\\s*:\\s*(?:async\\s*)?(?:\\([^)]*\\)|[A-Za-z_$][\\w$]*)\\s*=>");
    private static final Pattern JS_METHOD = Pattern.compile(
            "^\\s*(?:(?:public|private|protected|static|async|readonly|override|declare)\\s+)*"
                    + "(\\w+)\\s*\\([^)]*\\)\\s*(?::\\s*[\\w<>\\[\\]|,.\\s]+)?\\{\\s*$");

    private static final Pattern GO_FUNC = Pattern.compile("^func\\s+(?:\\([^)]*\\)\\s*)?(\\w+)\\s*\\(");

    private static final Pattern JAVA_IMPORT = Pattern.compile("^\\s*import\\s+(?:static\\s+)?([\\w.$*]+)\\s*;");
    private static final Pattern JS_IMPORT_FROM = Pattern.compile("^\\s*import\\s+.*?from\\s+['\"]([^'\"]+)['\"]");
    private static final Pattern JS_IMPORT_BARE = Pattern.compile("^\\s*import\\s+['\"]([^'\"]+)['\"]");
    private static final Pattern JS_REQUIRE = Pattern.compile("\\brequire\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)");
    private static final Pattern GO_IMPORT = Pattern.compile("^\\s*import\\s+(?:\\w+\\s+)?\"([^\"]+)\"\\s*$");

    private static final Pattern CALL = Pattern.compile(
            "\\b([A-Za-z_$][\\w$]*)\\s*(?:\\.\\s*([A-Za-z_$][\\w$]*))?\\s*\\(");

    private static final Pattern DYNAMIC_CALL = Pattern.compile(
            "\\beval\\s*\\(|\\.apply\\s*\\(|\\.call\\s*\\(|\\bFunction\\s*\\(|"
                    + "\\bClass\\.forName\\s*\\(|\\.invoke\\s*\\(|\\.getMethod\\s*\\(|\\.getDeclaredMethod\\s*\\(|"
                    + "\\?\\s*\\.\\s*\\(|\\]\\s*\\(|\\bnewInstance\\s*\\(|\\bmethod_missing\\b");

    /** Prefixes that can never start a declaration (control flow, imports, statements). */
    private static final List<String> NON_DECLARATION_PREFIXES = List.of(
            "if", "else", "for", "while", "do", "switch", "case", "catch", "try", "finally", "return", "throw",
            "assert", "yield", "await", "new", "typeof", "instanceof", "delete", "package", "import", "using",
            "namespace", "module", "require");

    /** Call targets that are language constructs rather than project symbols. */
    private static final Set<String> CALL_NOISE = Set.of(
            "if", "else", "for", "while", "do", "switch", "catch", "return", "throw", "new", "assert", "typeof",
            "instanceof", "delete", "await", "yield", "super", "this", "function", "require", "import", "print",
            "println", "printf", "sizeof");

    private record Declaration(String name, String kind) {
    }

    private final Language language;

    public BraceScanner(Language language) {
        this.language = language;
    }

    @Override
    public ParsedFile parse(String fileContent, String filePath) {
        List<String> lines = SourceLines.split(fileContent);
        List<String> code = sanitize(lines);
        List<Integer> depthBefore = new ArrayList<>(lines.size());
        List<Integer> depthAfter = new ArrayList<>(lines.size());

        int depth = 0;
        for (String line : code) {
            depthBefore.add(depth);
            depth += bracesDelta(line);
            depthAfter.add(depth);
        }

        Scan scan = new Scan(lines, code, depthBefore, depthAfter);
        scanRange(scan, 1, lines.size(), 0, null, null);
        extractReferences(scan);
        return new ParsedFile(language, scan.blocks, scan.symbols, scan.references);
    }

    /** State holder for one file scan (keeps the recursion signature readable). */
    private static final class Scan {
        final List<String> lines;
        final List<String> code;
        final List<Integer> depthBefore;
        final List<Integer> depthAfter;
        final List<ParsedFile.Block> blocks = new ArrayList<>();
        final List<ParsedFile.Symbol> symbols = new ArrayList<>();
        final List<ParsedFile.Reference> references = new ArrayList<>();
        String[] ownerByLine;

        Scan(List<String> lines, List<String> code, List<Integer> depthBefore, List<Integer> depthAfter) {
            this.lines = lines;
            this.code = code;
            this.depthBefore = depthBefore;
            this.depthAfter = depthAfter;
        }

        int depthAt(int line) {
            return depthBefore.get(line - 1);
        }

        String codeAt(int line) {
            return code.get(line - 1);
        }
    }

    /**
     * Blanks out comments and string literals so braces inside them are ignored
     * and call matching never fires on text inside a literal.
     */
    private List<String> sanitize(List<String> lines) {
        List<String> result = new ArrayList<>(lines.size());
        boolean inBlockComment = false;
        for (String line : lines) {
            StringBuilder out = new StringBuilder();
            char quote = 0;
            for (int i = 0; i < line.length(); i++) {
                char current = line.charAt(i);
                char next = i + 1 < line.length() ? line.charAt(i + 1) : '\0';
                if (inBlockComment) {
                    if (current == '*' && next == '/') {
                        inBlockComment = false;
                        i++;
                    }
                    continue;
                }
                if (quote != 0) {
                    if (current == '\\') {
                        i++;
                    } else if (current == quote) {
                        quote = 0;
                    }
                    continue;
                }
                if (current == '/' && next == '*') {
                    inBlockComment = true;
                    i++;
                    continue;
                }
                if (current == '/' && next == '/') {
                    break;
                }
                if (current == '"' || current == '\'' || current == '`') {
                    quote = current;
                    continue;
                }
                out.append(current);
            }
            result.add(out.toString());
        }
        return result;
    }

    private static int bracesDelta(String code) {
        int delta = 0;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') {
                delta++;
            } else if (c == '}') {
                delta--;
            }
        }
        return delta;
    }

    /**
     * Walks a line range at {@code memberDepth}, emitting a block per declaration
     * and a block for every gap, so no meaningful line is ever dropped. Container
     * bodies are scanned recursively: each method becomes its own chunk while the
     * class shell (header, fields, annotations) stays a block of its own.
     */
    private void scanRange(Scan scan, int from, int to, int memberDepth, String ownerName, String ownerKind) {
        int cursor = from;
        int line = from;
        while (line <= to) {
            Declaration declaration = matchDeclaration(scan.codeAt(line));
            if (declaration == null || scan.depthAt(line) != memberDepth) {
                line++;
                continue;
            }
            int end = findEnd(scan, line, to);
            if (end < line) {
                line++;
                continue;
            }

            emitGap(scan, cursor, line - 1, ownerName, ownerKind);

            String kind = ownerName != null && declaration.name().equals(ownerName)
                    ? "constructor"
                    : declaration.kind();
            scan.symbols.add(new ParsedFile.Symbol(declaration.name(), kind, ownerName, line, end,
                    SourceLines.trimSignature(scan.lines.get(line - 1))));

            if (isContainer(kind)) {
                int firstChild = firstMemberLine(scan, line + 1, end - 1, scan.depthAt(line) + 1);
                if (firstChild < 0) {
                    scan.blocks.add(new ParsedFile.Block(kind, declaration.name(), ownerName, line, end));
                } else {
                    scan.blocks.add(new ParsedFile.Block(kind, declaration.name(), ownerName, line, firstChild - 1));
                    scanRange(scan, firstChild, end - 1, scan.depthAt(line) + 1, declaration.name(), kind);
                }
            } else {
                scan.blocks.add(new ParsedFile.Block(kind, declaration.name(), ownerName, line, end));
            }

            line = end + 1;
            cursor = line;
        }
        emitGap(scan, cursor, to, ownerName, ownerKind);
    }

    /** Last line of a declaration, derived from the braces it actually opens. */
    private int findEnd(Scan scan, int start, int to) {
        int baseDepth = scan.depthAt(start);
        int signatureLimit = Math.min(to, start + 30);
        int bodyStart = -1;
        for (int line = start; line <= signatureLimit; line++) {
            if (scan.depthAt(line) != baseDepth) {
                break;
            }
            if (scan.depthAfter.get(line - 1) > baseDepth) {
                bodyStart = line;
                break;
            }
            if (scan.codeAt(line).contains(";")) {
                return line; // bodyless declaration (abstract/interface member, one-line arrow)
            }
        }
        if (bodyStart < 0) {
            return start;
        }
        for (int line = bodyStart; line <= to; line++) {
            if (scan.depthAfter.get(line - 1) == baseDepth) {
                return line;
            }
        }
        return to;
    }

    private int firstMemberLine(Scan scan, int from, int to, int memberDepth) {
        for (int line = from; line <= to; line++) {
            if (scan.depthAt(line) == memberDepth && matchDeclaration(scan.codeAt(line)) != null) {
                return line;
            }
        }
        return -1;
    }

    private void emitGap(Scan scan, int from, int to, String ownerName, String ownerKind) {
        if (to < from || !hasMeaningfulContent(scan, from, to)) {
            return;
        }
        if (ownerName != null) {
            scan.blocks.add(new ParsedFile.Block(ownerKind, ownerName, null, from, to));
        } else {
            scan.blocks.add(new ParsedFile.Block(gapKind(scan, from, to), null, null, from, to));
        }
    }

    /**
     * Regions that contain nothing but closing braces carry no retrievable
     * meaning, so they are skipped instead of becoming noise chunks.
     */
    private boolean hasMeaningfulContent(Scan scan, int from, int to) {
        for (int line = from; line <= to; line++) {
            String trimmed = scan.codeAt(line).trim();
            if (!trimmed.isEmpty() && !trimmed.replaceAll("[{};,]+", "").isBlank()) {
                return true;
            }
        }
        return false;
    }

    private String gapKind(Scan scan, int from, int to) {
        for (int line = from; line <= to; line++) {
            String raw = scan.lines.get(line - 1);
            if (raw.isBlank() || isImportLine(raw)) {
                continue;
            }
            return ParsedFile.BLOCK_MODULE;
        }
        return ParsedFile.BLOCK_IMPORTS;
    }

    /** Import/package/header lines, checked against the raw text (strings intact). */
    private boolean isImportLine(String rawLine) {
        String trimmed = rawLine.trim();
        if (trimmed.startsWith("package ") || trimmed.startsWith("using ") || trimmed.startsWith("#include")
                || trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
            return true;
        }
        return JAVA_IMPORT.matcher(rawLine).find()
                || JS_IMPORT_FROM.matcher(rawLine).find()
                || JS_IMPORT_BARE.matcher(rawLine).find()
                || GO_IMPORT.matcher(rawLine).find();
    }

    private Declaration matchDeclaration(String code) {
        String trimmed = code.trim();
        if (trimmed.isEmpty() || startsWithKeyword(trimmed, NON_DECLARATION_PREFIXES)) {
            return null;
        }
        return switch (language) {
            case JAVA -> matchJava(trimmed);
            case JAVASCRIPT, TYPESCRIPT, TSX -> matchJavaScript(trimmed);
            case GO -> matchGo(trimmed);
            default -> null;
        };
    }

    private static boolean startsWithKeyword(String trimmed, List<String> keywords) {
        for (String keyword : keywords) {
            if (trimmed.length() > keyword.length() && trimmed.startsWith(keyword)
                    && !Character.isLetterOrDigit(trimmed.charAt(keyword.length()))
                    && trimmed.charAt(keyword.length()) != '_' && trimmed.charAt(keyword.length()) != '.') {
                return true;
            }
        }
        return false;
    }

    private Declaration matchJava(String code) {
        Matcher type = JAVA_TYPE.matcher(code);
        if (type.find()) {
            return new Declaration(type.group(2), type.group(1));
        }
        Matcher method = JAVA_METHOD.matcher(code);
        if (method.find()) {
            return new Declaration(method.group(1), ParsedFile.BLOCK_METHOD);
        }
        return null;
    }

    private Declaration matchJavaScript(String code) {
        Matcher type = JS_CLASS.matcher(code);
        if (type.find()) {
            return new Declaration(type.group(1), ParsedFile.BLOCK_CLASS);
        }
        Matcher iface = JS_INTERFACE.matcher(code);
        if (iface.find()) {
            return new Declaration(iface.group(1), ParsedFile.BLOCK_INTERFACE);
        }
        Matcher function = JS_FUNCTION.matcher(code);
        if (function.find()) {
            return new Declaration(function.group(1), ParsedFile.BLOCK_FUNCTION);
        }
        Matcher arrow = JS_ARROW.matcher(code);
        if (arrow.find()) {
            return new Declaration(arrow.group(1), ParsedFile.BLOCK_FUNCTION);
        }
        Matcher objectArrow = JS_OBJECT_ARROW.matcher(code);
        if (objectArrow.find()) {
            return new Declaration(objectArrow.group(1), ParsedFile.BLOCK_FUNCTION);
        }
        Matcher method = JS_METHOD.matcher(code);
        if (method.find()) {
            return new Declaration(method.group(1), ParsedFile.BLOCK_METHOD);
        }
        return null;
    }

    private Declaration matchGo(String code) {
        Matcher function = GO_FUNC.matcher(code);
        if (function.find()) {
            return new Declaration(function.group(1), ParsedFile.BLOCK_FUNCTION);
        }
        return null;
    }

    private static boolean isContainer(String kind) {
        return ParsedFile.BLOCK_CLASS.equals(kind)
                || ParsedFile.BLOCK_INTERFACE.equals(kind)
                || ParsedFile.BLOCK_ENUM.equals(kind);
    }

    /**
     * Records imports and call sites for every line, tagging each reference with
     * the innermost symbol it sits in. Imports are read from raw text (their
     * targets are string literals); calls from sanitized text, so a call written
     * inside a string is never treated as a call.
     */
    private void extractReferences(Scan scan) {
        buildOwnerIndex(scan);
        Set<Integer> declarationLines = new HashSet<>();
        for (ParsedFile.Symbol symbol : scan.symbols) {
            declarationLines.add(symbol.startLine());
        }

        for (int line = 1; line <= scan.lines.size(); line++) {
            String owner = scan.ownerByLine[line - 1];
            for (String target : importTargets(scan.lines.get(line - 1))) {
                scan.references.add(new ParsedFile.Reference(line, target, ParsedFile.KIND_IMPORT, false, owner));
            }
            if (declarationLines.contains(line)) {
                continue; // a declaration header is not a call site
            }
            String code = scan.codeAt(line);
            boolean dynamicLine = DYNAMIC_CALL.matcher(code).find();
            Matcher matcher = CALL.matcher(code);
            while (matcher.find()) {
                String first = matcher.group(1);
                String member = matcher.group(2);
                String simple = member != null ? member : first;
                if (CALL_NOISE.contains(simple.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                String callee = member != null ? first + "." + member : first;
                scan.references.add(new ParsedFile.Reference(line, callee, ParsedFile.KIND_CALL, dynamicLine, owner));
            }
        }
    }

    /** Maps every line to the innermost symbol declaring it (inner wins). */
    private void buildOwnerIndex(Scan scan) {
        scan.ownerByLine = new String[scan.lines.size()];
        List<ParsedFile.Symbol> sorted = new ArrayList<>(scan.symbols);
        sorted.sort(Comparator.comparingInt(ParsedFile.Symbol::startLine));
        for (ParsedFile.Symbol symbol : sorted) {
            int from = Math.max(1, symbol.startLine());
            int to = Math.min(scan.lines.size(), symbol.endLine());
            for (int line = from; line <= to; line++) {
                scan.ownerByLine[line - 1] = symbol.name();
            }
        }
    }

    private List<String> importTargets(String rawLine) {
        List<String> targets = new ArrayList<>();
        switch (language) {
            case JAVA -> addGroup(targets, JAVA_IMPORT, rawLine, 1);
            case JAVASCRIPT, TYPESCRIPT, TSX -> {
                addGroup(targets, JS_IMPORT_FROM, rawLine, 1);
                addGroup(targets, JS_IMPORT_BARE, rawLine, 1);
                addGroup(targets, JS_REQUIRE, rawLine, 1);
            }
            case GO -> addGroup(targets, GO_IMPORT, rawLine, 1);
            default -> {
            }
        }
        return targets;
    }

    private void addGroup(List<String> targets, Pattern pattern, String line, int group) {
        Matcher matcher = pattern.matcher(line);
        while (matcher.find()) {
            String value = matcher.group(group);
            if (value != null && !value.isBlank()) {
                targets.add(value.trim());
            }
        }
    }
}

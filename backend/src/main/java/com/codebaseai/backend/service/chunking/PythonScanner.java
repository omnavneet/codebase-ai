package com.codebaseai.backend.service.chunking;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scanner for Python: indentation defines blocks, so {@code def}/{@code class}
 * bodies are found by indent level rather than braces. Decorators stay attached
 * to the declaration they decorate, and imports/calls are recorded exactly as
 * written (dynamic forms such as {@code getattr(...)} are flagged, not guessed).
 */
public class PythonScanner implements LanguageScanner {

    private static final Pattern CLASS = Pattern.compile("^(\\s*)class\\s+(\\w+)");
    private static final Pattern DEF = Pattern.compile("^(\\s*)(?:async\\s+)?def\\s+(\\w+)\\s*\\(");
    private static final Pattern DECORATOR = Pattern.compile("^\\s*@[\\w.]+");
    private static final Pattern FROM_IMPORT = Pattern.compile("^\\s*from\\s+([.\\w]+)\\s+import\\b");
    private static final Pattern PLAIN_IMPORT = Pattern.compile("^\\s*import\\s+([\\w.]+)");
    private static final Pattern CALL = Pattern.compile(
            "\\b([A-Za-z_]\\w*)\\s*(?:\\.\\s*([A-Za-z_]\\w*))?\\s*\\(");
    private static final Pattern DYNAMIC_CALL = Pattern.compile(
            "\\bgetattr\\s*\\(|\\bsetattr\\s*\\(|\\beval\\s*\\(|\\bexec\\s*\\(|\\bglobals\\s*\\(|"
                    + "\\blocals\\s*\\(|\\b__getattr__\\b|\\bapply\\s*\\(|\\bimport_module\\s*\\(");
    private static final List<String> NON_DECLARATION_PREFIXES = List.of(
            "if", "elif", "else", "for", "while", "with", "try", "except", "finally", "return", "yield", "raise",
            "assert", "del", "print", "import", "from", "await", "lambda", "match", "case", "pass", "break",
            "continue");
    private static final List<String> CALL_NOISE = List.of(
            "if", "elif", "else", "for", "while", "with", "return", "yield", "raise", "assert", "print", "len",
            "isinstance", "range", "super", "int", "str", "float", "bool", "list", "dict", "set", "tuple",
            "open", "enumerate", "zip");

    private record Declaration(String name, String kind) {
    }

    @Override
    public ParsedFile parse(String fileContent, String filePath) {
        List<String> lines = SourceLines.split(fileContent);
        List<String> code = stripComments(lines);
        Scan scan = new Scan(lines, code);
        scanRange(scan, 1, lines.size(), 0, null, null);
        extractReferences(scan);
        return new ParsedFile(Language.PYTHON, scan.blocks, scan.symbols, scan.references);
    }

    private static final class Scan {
        final List<String> lines;
        final List<String> code;
        final List<ParsedFile.Block> blocks = new ArrayList<>();
        final List<ParsedFile.Symbol> symbols = new ArrayList<>();
        final List<ParsedFile.Reference> references = new ArrayList<>();
        String[] ownerByLine;

        Scan(List<String> lines, List<String> code) {
            this.lines = lines;
            this.code = code;
        }

        String codeAt(int line) {
            return code.get(line - 1);
        }
    }

    /** Removes '#' comments outside string literals so they cannot fake declarations. */
    private List<String> stripComments(List<String> lines) {
        List<String> result = new ArrayList<>(lines.size());
        for (String line : lines) {
            StringBuilder out = new StringBuilder();
            char quote = 0;
            int i = 0;
            while (i < line.length()) {
                char current = line.charAt(i);
                if (quote != 0) {
                    if (current == '\\') {
                        i += 2;
                        continue;
                    }
                    if (current == quote) {
                        quote = 0;
                    }
                    out.append(current);
                } else if (current == '#' && i > 0 && (i < 2 || line.charAt(i - 1) != '#')) {
                    break;
                } else {
                    if (current == '"' || current == '\'') {
                        quote = current;
                    }
                    out.append(current);
                }
                i++;
            }
            result.add(out.toString());
        }
        return result;
    }

    private void scanRange(Scan scan, int from, int to, int indent, String ownerName, String ownerKind) {
        int cursor = from;
        int line = from;
        while (line <= to) {
            String code = scan.codeAt(line);
            if (code.isBlank() || SourceLines.indentOf(code) != indent) {
                if (!code.isBlank() && SourceLines.indentOf(code) < indent) {
                    break;
                }
                line++;
                continue;
            }
            Declaration declaration = matchDeclaration(code);
            if (declaration == null) {
                line++;
                continue;
            }

            int start = includeDecorators(scan, line, from);
            int end = findBlockEnd(scan, line, to);
            emitGap(scan, cursor, start - 1, ownerName, ownerKind);
            scan.symbols.add(new ParsedFile.Symbol(declaration.name(), declaration.kind(), ownerName, start, end,
                    SourceLines.trimSignature(scan.lines.get(start - 1))));

            if (ParsedFile.BLOCK_CLASS.equals(declaration.kind())) {
                int childIndent = firstBodyIndent(scan, line + 1, end);
                int firstChild = childIndent < 0 ? -1 : firstMemberLine(scan, line + 1, end, childIndent);
                if (firstChild < 0) {
                    scan.blocks.add(new ParsedFile.Block(declaration.kind(), declaration.name(), ownerName, start, end));
                } else {
                    scan.blocks.add(new ParsedFile.Block(
                            declaration.kind(), declaration.name(), ownerName, start, firstChild - 1));
                    scanRange(scan, firstChild, end, childIndent, declaration.name(), declaration.kind());
                }
            } else {
                scan.blocks.add(new ParsedFile.Block(declaration.kind(), declaration.name(), ownerName, start, end));
            }

            line = end + 1;
            cursor = line;
        }
        emitGap(scan, cursor, to, ownerName, ownerKind);
    }

    /** Last line of a body: the last line indented deeper than the declaration. */
    private int findBlockEnd(Scan scan, int declarationLine, int to) {
        int baseIndent = SourceLines.indentOf(scan.codeAt(declarationLine));
        String declarationText = scan.codeAt(declarationLine);
        int colon = declarationText.lastIndexOf(':');
        if (colon >= 0 && !declarationText.substring(colon + 1).trim().isEmpty()) {
            return declarationLine; // one-liner body: "def f(): return 1"
        }
        int end = declarationLine;
        for (int line = declarationLine + 1; line <= to; line++) {
            String code = scan.codeAt(line);
            if (code.isBlank()) {
                continue;
            }
            if (SourceLines.indentOf(code) <= baseIndent) {
                break;
            }
            end = line;
        }
        return end;
    }

    private int includeDecorators(Scan scan, int line, int from) {
        int start = line;
        while (start - 1 >= from && DECORATOR.matcher(scan.codeAt(start - 1)).find()) {
            start--;
        }
        return start;
    }

    private int firstBodyIndent(Scan scan, int from, int to) {
        for (int line = from; line <= to; line++) {
            String code = scan.codeAt(line);
            if (!code.isBlank()) {
                return SourceLines.indentOf(code);
            }
        }
        return -1;
    }

    private int firstMemberLine(Scan scan, int from, int to, int indent) {
        for (int line = from; line <= to; line++) {
            String code = scan.codeAt(line);
            if (!code.isBlank() && SourceLines.indentOf(code) == indent && matchDeclaration(code) != null) {
                return line;
            }
        }
        return -1;
    }

    private Declaration matchDeclaration(String code) {
        String trimmed = code.trim();
        if (trimmed.isEmpty() || startsWithKeyword(trimmed)) {
            return null;
        }
        Matcher classMatcher = CLASS.matcher(code);
        if (classMatcher.find()) {
            return new Declaration(classMatcher.group(2), ParsedFile.BLOCK_CLASS);
        }
        Matcher defMatcher = DEF.matcher(code);
        if (defMatcher.find()) {
            return new Declaration(defMatcher.group(2), ParsedFile.BLOCK_FUNCTION);
        }
        return null;
    }

    private static boolean startsWithKeyword(String trimmed) {
        for (String keyword : NON_DECLARATION_PREFIXES) {
            if (trimmed.length() > keyword.length() && trimmed.startsWith(keyword)
                    && !Character.isLetterOrDigit(trimmed.charAt(keyword.length()))
                    && trimmed.charAt(keyword.length()) != '_') {
                return true;
            }
        }
        return false;
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

    private boolean hasMeaningfulContent(Scan scan, int from, int to) {
        for (int line = from; line <= to; line++) {
            if (!scan.codeAt(line).trim().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private String gapKind(Scan scan, int from, int to) {
        for (int line = from; line <= to; line++) {
            String code = scan.codeAt(line);
            if (code.isBlank() || FROM_IMPORT.matcher(code).find() || PLAIN_IMPORT.matcher(code).find()
                    || code.trim().startsWith("#")) {
                continue;
            }
            return ParsedFile.BLOCK_MODULE;
        }
        return ParsedFile.BLOCK_IMPORTS;
    }

    private void extractReferences(Scan scan) {
        buildOwnerIndex(scan);
        boolean[] inDocstring = docstringMask(scan);

        for (int line = 1; line <= scan.lines.size(); line++) {
            String owner = scan.ownerByLine[line - 1];
            String code = scan.codeAt(line);
            Matcher fromMatcher = FROM_IMPORT.matcher(code);
            if (fromMatcher.find()) {
                scan.references.add(new ParsedFile.Reference(
                        line, fromMatcher.group(1), ParsedFile.KIND_IMPORT, false, owner));
            }
            Matcher importMatcher = PLAIN_IMPORT.matcher(code);
            while (importMatcher.find()) {
                scan.references.add(new ParsedFile.Reference(
                        line, importMatcher.group(1), ParsedFile.KIND_IMPORT, false, owner));
            }
            if (inDocstring[line - 1]) {
                continue; // text inside a docstring is not code
            }
            boolean dynamicLine = DYNAMIC_CALL.matcher(code).find();
            Matcher callMatcher = CALL.matcher(code);
            while (callMatcher.find()) {
                String first = callMatcher.group(1);
                String member = callMatcher.group(2);
                String simple = member != null ? member : first;
                if (CALL_NOISE.contains(simple)) {
                    continue;
                }
                String callee = member != null ? first + "." + member : first;
                scan.references.add(new ParsedFile.Reference(line, callee, ParsedFile.KIND_CALL, dynamicLine, owner));
            }
        }
    }

    /** Lines inside a triple-quoted string, so docstring text is never read as code. */
    private boolean[] docstringMask(Scan scan) {
        boolean[] mask = new boolean[scan.lines.size()];
        char activeQuote = 0;
        for (int line = 1; line <= scan.lines.size(); line++) {
            String raw = scan.lines.get(line - 1);
            boolean inside = activeQuote != 0;
            int index = 0;
            while (index < raw.length()) {
                if (activeQuote == 0) {
                    if (raw.startsWith("\"\"\"", index) || raw.startsWith("'''", index)) {
                        activeQuote = raw.charAt(index);
                        index += 3;
                        continue;
                    }
                } else if (raw.charAt(index) == activeQuote && raw.startsWith(String.valueOf(activeQuote).repeat(3), index)) {
                    activeQuote = 0;
                    index += 3;
                    continue;
                }
                index++;
            }
            mask[line - 1] = inside || activeQuote != 0;
        }
        return mask;
    }

    private void buildOwnerIndex(Scan scan) {
        scan.ownerByLine = new String[scan.lines.size()];
        List<ParsedFile.Symbol> sorted = new ArrayList<>(scan.symbols);
        sorted.sort(java.util.Comparator.comparingInt(ParsedFile.Symbol::startLine));
        for (ParsedFile.Symbol symbol : sorted) {
            int from = Math.max(1, symbol.startLine());
            int to = Math.min(scan.lines.size(), symbol.endLine());
            for (int line = from; line <= to; line++) {
                scan.ownerByLine[line - 1] = symbol.name();
            }
        }
    }
}

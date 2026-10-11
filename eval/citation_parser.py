"""
Parses file/line citations from agent answers.

A citation looks like one of:
  - `backend/src/main/java/com/codebaseai/backend/service/IndexingJobService.java:120`
  - `backend/src/main/java/com/codebaseai/backend/service/IndexingJobService.java (line 45)`
  - `IndexingJobService.java:120`
  - `` `IndexingJobService.java` ``  (backtick-wrapped path)

We extract (file_path, line_number_or_range) pairs.
"""
import re
from dataclasses import dataclass
from typing import List, Tuple, Optional


@dataclass
class Citation:
    file_path: str
    start_line: Optional[int]
    end_line: Optional[int]


# A regex that finds file paths with optional line numbers.
# Supported forms:
#   path/to/file.py:123           (single line)
#   path/to/file.py:100-200       (range)
#   path/to/file.py (line 123)    (parenthetical)
#   path/to/file.py (lines 100-200)
_FILE_EXT = r"\.(?:java|py|ts|tsx|js|jsx|go|rs|cpp|c|h|cs|rb|php|kt|scala|sh|yml|yaml)"
_PATH = r"[\w./-]+" + _FILE_EXT
_LINE_SINGLE = r"(?::(\d+)(?:-(\d+))?)"
_LINE_RANGE_PAREN = r"(?:\s*\(line[s]?\s+(\d+)(?:\s*-\s*(\d+))?\))?"


def parse_citations(text: str) -> List[Citation]:
    """Extract citations from a block of text."""
    citations: List[Citation] = []

    # Pattern 1: path:123 or path:100-200
    pattern_colon = re.compile(rf"(?:`|\")?({_PATH}){_LINE_SINGLE}(?:`|\")?")

    for m in pattern_colon.finditer(text):
        path = m.group(1)
        start = int(m.group(2)) if m.group(2) else None
        end = int(m.group(3)) if m.group(3) else None
        citations.append(Citation(path, start, end))

    # Pattern 2: path (line N) or path (lines N-M)
    pattern_paren = re.compile(
        rf"(?:`|\")?({_PATH}){_LINE_RANGE_PAREN}(?:`|\")?"
    )

    for m in pattern_paren.finditer(text):
        path = m.group(1)
        start = int(m.group(2)) if m.group(2) else None
        end = int(m.group(3)) if m.group(3) else None
        # Avoid duplicates from pattern 1
        existing = [c for c in citations if c.file_path == path]
        if not existing:
            citations.append(Citation(path, start, end))

    # Pattern 3: bare backtick paths like `IndexingJobService.java`
    pattern_backtick = re.compile(rf"`({_PATH})`")
    for m in pattern_backtick.finditer(text):
        path = m.group(1)
        existing = [c for c in citations if c.file_path == path]
        if not existing:
            citations.append(Citation(path, None, None))

    return citations


def normalize_citation_path(citation_path: str) -> str:
    """Normalize a citation path to match file paths in the repo."""
    # Strip leading ./
    normalized = citation_path.replace("\\", "/").lstrip("./")
    # Take the basename if it's just a filename
    return normalized


def citation_matches_expected(citation_path: str, expected_files: List[str]) -> bool:
    """
    Check if a cited file matches any expected file.

    A citation matches if:
    - Its full relative path exactly matches an expected file
    - Or its basename matches the basename of an expected file
      (lenient: a short citation like "IndexingJobService.java" still counts
      towards the expected file if that file is "backend/.../IndexingJobService.java")
    """
    norm = normalize_citation_path(citation_path)
    for expected in expected_files:
        expected_norm = normalize_citation_path(expected)
        if norm == expected_norm:
            return True
        # Lenient basename match
        if norm == expected.split("/")[-1] or norm.split("/")[-1] == expected.split("/")[-1]:
            return True
    return False


if __name__ == "__main__":
    # Quick self-test
    text = "IndexingJobService.java:120 and ProjectService.java (line 45) and `IndexingJobService.java`"
    results = parse_citations(text)
    for r in results:
        print(f"  {r.file_path}:{r.start_line}-{r.end_line}")

"""Unit tests for the agent answer citation parser.

One happy path (colon + parenthetical + backtick forms) plus the key safety
property: basename-only citations still match the expected full path, so a
short `Router.java` cite counts for `router.go`-style expected files.
"""
import os
import sys

# The eval package lives at the repo root, one level above this directory.
# CI runs pytest from ai-service/, so add the root to sys.path first.
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from eval.citation_parser import citation_matches_expected, parse_citations


def test_parse_citations_happy_path():
    text = (
        "See backend/src/main/java/com/codebaseai/backend/service/IndexingJobService.java:120 "
        "and router.go (line 45) and `tree.go`"
    )
    cites = parse_citations(text)
    by_file = {c.file_path: c for c in cites}
    assert by_file[
        "backend/src/main/java/com/codebaseai/backend/service/IndexingJobService.java"
    ].start_line == 120
    assert by_file["router.go"].start_line == 45
    assert "tree.go" in by_file


def test_citation_matches_expected_basename_lenient():
    expected = ["backend/src/main/java/com/codebaseai/backend/service/IndexingJobService.java"]
    assert citation_matches_expected("IndexingJobService.java", expected)
    assert citation_matches_expected(
        "backend/src/main/java/com/codebaseai/backend/service/IndexingJobService.java",
        expected,
    )


def test_citation_matches_expected_rejects_other_file():
    expected = ["router.go"]
    assert not citation_matches_expected("tree.go", expected)
    assert not citation_matches_expected("other/router.go.bak", expected)

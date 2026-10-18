"""Fixed Debug pipeline (no agent loop, no MCP).

Predictable task: extract clues from the report (no model call), search the
code for the distinctive signals, then analyze the gathered evidence in ONE
model call. The in-process ``agent_tools`` do the reading.

Required answer sections: Most likely cause, Where (file:line), Why,
Suggested fix, How to confirm, and at most two ranked alternative causes. The
model may only cite files present in the evidence; when the evidence is thin
it says what to provide instead of guessing.

Emits real ``status`` step events, enforces a hard wall-clock timeout
(``PIPELINE_TIMEOUT_SECONDS``), and on timeout/cancel returns the partial text
with the existing "stopped early" signal.
"""
import logging
import os
import re
import time
import uuid
from typing import Any, Dict, List, Optional

from explain_service import streaming_model_call
from stack_trace_parser import parse_stack_trace, project_frames

logger = logging.getLogger(__name__)

# Clue extraction (no model call).
QUOTED_STRING = re.compile(r"[\"']([^\"'\n]{3,120})[\"']")
EXCEPTION_NAME = re.compile(r"\b([A-Z][\w.$]*(?:Exception|Error))\b")
FILE_LINE = re.compile(
    r"([\w./\\~-]+\.(?:py|js|jsx|ts|tsx|java|go|rb|rs|c|cc|cpp|h|hpp|cs)):(\d+)")
BARE_FILE = re.compile(
    r"([\w./\\~-]+\.(?:py|js|jsx|ts|tsx|java|go|rb|rs|c|cc|cpp|h|hpp|cs))")


def _dedupe(items: List[Any], limit: int) -> List[Any]:
    seen = set()
    out: List[Any] = []
    for item in items:
        if item in seen:
            continue
        seen.add(item)
        out.append(item)
        if len(out) >= limit:
            break
    return out


class DebugService:
    MAX_EVIDENCE_FILES = int(os.getenv("DEBUG_MAX_EVIDENCE_FILES", "5"))
    MAX_CONTEXT_CHARS = int(os.getenv("DEBUG_MAX_CONTEXT_CHARS", "4000"))
    MAX_GREP_MATCHES = int(os.getenv("DEBUG_MAX_GREP_MATCHES", "20"))
    MAX_GREP_LINE_CHARS = int(os.getenv("DEBUG_MAX_GREP_LINE_CHARS", "400"))
    READ_WINDOW = int(os.getenv("DEBUG_READ_WINDOW", "25"))
    MAX_TOKENS = int(os.getenv("DEBUG_MAX_TOKENS", "1500"))

    def __init__(self, tools, groq_client):
        self.tools = tools
        self.client = groq_client
        self.model = os.getenv("LLM_MODEL")
        self.timeout_seconds = float(os.getenv("PIPELINE_TIMEOUT_SECONDS", "30"))

    def debug(self, issue_description, project_id, stack_trace=None,
              file_path=None, on_event=None, cancel_event=None):
        request_id = uuid.uuid4().hex[:12]
        deadline = time.monotonic() + self.timeout_seconds

        def emit(event: Dict[str, Any]) -> None:
            if on_event is not None:
                try:
                    on_event(event)
                except Exception:
                    pass

        # -- Step 1: extract clues (no model call) ---------------------------
        emit({"type": "status", "message": "Extracting clues"})
        t0 = time.monotonic()
        parsed = parse_stack_trace(stack_trace or "")
        project_files = self.tools.list_files(project_id)
        located = project_frames(parsed, project_files)
        clues = self._extract_clues(issue_description, parsed)
        logger.info("debug clues request_id=%s ms=%.0f strings=%d excs=%d files=%d",
                    request_id, (time.monotonic() - t0) * 1000,
                    len(clues["error_strings"]), len(clues["exc_names"]),
                    len(clues["file_lines"]) + len(clues["bare_files"]))

        # -- Step 2: search code ---------------------------------------------
        emit({"type": "status", "message": "Searching code"})
        t0 = time.monotonic()
        evidence = self._gather_evidence(
            issue_description, project_id, clues, located, file_path, project_files)
        logger.info("debug search request_id=%s ms=%.0f files=%d searches=%d",
                    request_id, (time.monotonic() - t0) * 1000,
                    len(evidence["files_read"]), len(evidence["searches"]))

        # -- Step 3: one streamed model call ---------------------------------
        emit({"type": "status", "message": "Analyzing"})
        prompt = self._build_prompt(issue_description, parsed, located, evidence)
        t0 = time.monotonic()
        answer, stopped_early, stop_reason = streaming_model_call(
            self.client, self.model,
            [
                {"role": "system",
                 "content": "You are a senior engineer debugging from real code evidence. "
                            "Never invent file paths or line numbers."},
                {"role": "user", "content": prompt},
            ],
            temperature=0.2, max_tokens=self.MAX_TOKENS,
            deadline=deadline, cancel_event=cancel_event,
        )
        logger.info("debug model request_id=%s ms=%.0f stopped_early=%s reason=%s",
                    request_id, (time.monotonic() - t0) * 1000, stopped_early, stop_reason)

        trace = [
            f"Parsed report ({len(located)} project frame(s))",
            f"Searched for {len(evidence['searches'])} signal(s)",
            f"Read {len(evidence['files_read'])} relevant region(s)",
            "Wrote root-cause analysis",
        ]
        return {
            "answer": answer,
            "findings": [],
            "trace": trace,
            "iterations": 1,
            "tool_calls": 0,
            "files_read": evidence["files_read"],
            "searches_performed": evidence["searches"],
            "truncated": stopped_early,
            "stopped_early": stopped_early,
            "stop_reason": stop_reason,
            "frames": parsed.get("frames", []),
            "project_frames": located,
            "stack_trace_language": parsed.get("language"),
            "stack_trace_parsed": parsed.get("parsed", False),
            "stack_trace_notes": parsed.get("notes", []),
        }

    # -- helpers ---------------------------------------------------------

    def _extract_clues(self, description: str, parsed: Dict[str, Any]) -> Dict[str, Any]:
        text = description or ""
        error_strings = _dedupe(
            [s.strip() for s in QUOTED_STRING.findall(text) if 3 <= len(s.strip()) <= 120], 8)
        exc_names = set()
        if parsed.get("error_type"):
            exc_names.add(parsed["error_type"])
        exc_names.update(EXCEPTION_NAME.findall(text))
        file_lines = _dedupe(FILE_LINE.findall(text), 8)  # [(file, line)]
        bare_files = _dedupe(BARE_FILE.findall(text), 8)
        symbols = {f["symbol"].split(".")[-1] for f in parsed.get("frames", []) if f.get("symbol")}
        return {
            "error_strings": error_strings,
            "exc_names": sorted(exc_names)[:6],
            "file_lines": file_lines,
            "bare_files": bare_files,
            "symbols": sorted(symbols)[:8],
        }

    def _match_project_file(self, name: str, project_files: List[str]) -> Optional[str]:
        target = str(name).replace("\\", "/").lstrip("./")
        tail = target.split("/")[-1]
        for path in project_files:
            normalised = path.replace("\\", "/").lstrip("./")
            if normalised == target or normalised.endswith("/" + target) or normalised.endswith(tail):
                return path
        return None

    def _gather_evidence(self, description, project_id, clues, located, file_path, project_files):
        searches: List[str] = []
        candidates: Dict[str, Optional[int]] = {}

        # File:line clues from the description.
        for fname, line in clues["file_lines"]:
            resolved = self._match_project_file(fname, project_files)
            if resolved:
                candidates.setdefault(resolved, int(line))
        # Frames that already resolve into the project.
        for frame in located[:5]:
            candidates.setdefault(frame["project_path"], frame.get("line"))
        if file_path:
            candidates.setdefault(file_path, None)
        # A suspected file that only appears as a bare name.
        for fname in clues["bare_files"]:
            if len(candidates) >= self.MAX_EVIDENCE_FILES:
                break
            resolved = self._match_project_file(fname, project_files)
            if resolved:
                candidates.setdefault(resolved, None)

        # Grep the distinctive strings / exception names / symbols (capped).
        grep_terms = clues["error_strings"][:3] + clues["exc_names"][:2] + clues["symbols"][:3]
        for term in grep_terms[:6]:
            if len(candidates) >= self.MAX_EVIDENCE_FILES:
                break
            res = self.tools.grep(term, project_id, max_matches=self.MAX_GREP_MATCHES)
            searches.append(f"grep:{term}")
            for match in res.get("matches", []):
                candidates.setdefault(match.get("file_path"), match.get("line"))

        # One semantic search on the description.
        try:
            for result in self.tools.semantic_search(description, project_id, limit=3):
                candidates.setdefault(result.get("file_path"), result.get("start_line"))
            searches.append("semantic_search:description")
        except Exception:
            pass

        kept = list(candidates.items())[: self.MAX_EVIDENCE_FILES]
        blocks: List[str] = []
        files_read: List[str] = []
        total = 0
        for path, line in kept:
            if path is None or total >= self.MAX_CONTEXT_CHARS:
                continue
            centre = line if isinstance(line, int) and line > 0 else 1
            snippet = self.tools.read_file(
                path, project_id, max(1, centre - self.READ_WINDOW), centre + self.READ_WINDOW)
            if "error" in snippet:
                continue
            content = snippet.get("content") or ""
            remaining = self.MAX_CONTEXT_CHARS - total
            if len(content) > remaining:
                content = content[:remaining] + "\n... (truncated)"
            blocks.append(f"{path} (around L{centre}):\n{content}")
            files_read.append(path)
            total += len(content)
        return {"blocks": blocks, "files_read": files_read, "searches": searches}

    def _build_prompt(self, issue_description, parsed, located, evidence) -> str:
        located_line = ""
        if located:
            located_line = "Frames that exist in this project: " + ", ".join(
                f"{frame['project_path']}:{frame.get('line')}" for frame in located[:5])
        if evidence["blocks"]:
            evidence_block = "\n\n".join(evidence["blocks"])
        else:
            evidence_block = "(no relevant code was found in the indexed project)"

        return f"""A developer reported a problem. Diagnose its root cause using ONLY the
evidence below. Do not invent files, line numbers or call paths you cannot see.

Reported problem:
{issue_description}

Stack-trace frames located in this project:
{located_line or "(none)"}

Relevant code (verified evidence):
{evidence_block}

Respond with EXACTLY these markdown sections, in order, concise and concrete:

## Most likely cause
(1-2 sentences)

## Where
(file:line, only from the evidence above)

## Why
(why the evidence supports this)

## Suggested fix
(a small diff or concrete steps)

## How to confirm
(one or two checks that would verify this)

## Other possible causes
(at most two, ranked, each one line; or "none")

Rules:
- Only cite files that appear in the evidence.
- If the evidence is not enough, say so and state what the user should provide
  (for example the full stack trace or the failing input) instead of guessing."""

"""Debugging pipeline: parse the trace, locate the suspect code, let the agent
investigate, then turn the investigation into structured findings with a code
diff — the same shape the code-review pipeline produces.
"""
import os
from typing import Any, Dict, List, Optional

from finding_utils import extract_json_object, sanitize_findings
from stack_trace_parser import parse_stack_trace, project_frames

MAX_CONTEXT_CHARS = 4000


class DebugService:
    """Deterministic preparation + agent investigation + structured findings."""

    def __init__(self, agent, tools, groq_client):
        self.agent = agent
        self.tools = tools
        self.client = groq_client
        self.model = os.getenv("LLM_MODEL")

    def debug(
        self,
        issue_description: str,
        project_id: str,
        stack_trace: Optional[str] = None,
        file_path: Optional[str] = None,
    ) -> Dict[str, Any]:
        parsed = parse_stack_trace(stack_trace) if stack_trace else parse_stack_trace("")
        project_files = self.tools.list_files(project_id)
        located = project_frames(parsed, project_files)

        context_blocks: List[str] = []
        for frame in located[:2]:
            snippet = self._read_around(frame["project_path"], project_id, frame.get("line"))
            if snippet:
                context_blocks.append(
                    "Stack-trace location {}:{} ({}):\n{}".format(
                        frame["project_path"], frame.get("line"),
                        frame.get("symbol") or "unknown", snippet))
        if file_path:
            snippet = self._read_around(file_path, project_id, None)
            if snippet:
                context_blocks.append("Suspected file {}:\n{}".format(file_path, snippet))

        question = self._build_question(issue_description, parsed, located, context_blocks, file_path)
        investigation = self.agent.investigate(question, project_id)
        answer = investigation.get("answer", "")

        return {
            "answer": answer,
            "findings": self._extract_findings(answer),
            "trace": investigation.get("trace", []),
            "iterations": investigation.get("iterations", 0),
            "files_read": investigation.get("files_read", []),
            "searches_performed": investigation.get("searches_performed", []),
            "truncated": investigation.get("truncated", False),
            "frames": parsed.get("frames", []),
            "project_frames": located,
            "stack_trace_language": parsed.get("language"),
            "stack_trace_parsed": parsed.get("parsed", False),
            "stack_trace_notes": parsed.get("notes", []),
        }

    # -- helpers ---------------------------------------------------------

    def _read_around(self, path: str, project_id: str, line: Optional[int]) -> Optional[str]:
        centre = line if isinstance(line, int) and line > 0 else 1
        result = self.tools.read_file(path, project_id, max(1, centre - 20), centre + 20)
        content = result.get("content")
        if not content or "error" in result:
            return None
        return self._clip(content)

    def _clip(self, text: str) -> str:
        if len(text) <= MAX_CONTEXT_CHARS:
            return text
        return text[:MAX_CONTEXT_CHARS] + "\n... (truncated)"

    def _build_question(self, issue_description, parsed, located, context_blocks, file_path) -> str:
        parts = ["Investigate this issue and find its root cause and a concrete fix.",
                 "", "Issue description:", issue_description, ""]

        if parsed.get("parsed"):
            parts.append("Parsed stack trace (top frames):")
            for frame in parsed["frames"][:5]:
                parts.append("- {} at {}:{}".format(frame.get("symbol") or "?", frame.get("file"), frame.get("line")))
            if parsed.get("error_type"):
                parts.append("Error type: {}".format(parsed["error_type"]))
        for note in parsed.get("notes", []):
            parts.append("Note: {}".format(note))

        if located:
            parts.append("")
            parts.append("Frames that exist in this project: " + ", ".join(
                "{}:{}".format(frame["project_path"], frame.get("line")) for frame in located[:3]))
        if file_path:
            parts.append("Suspected file: {}".format(file_path))

        for block in context_blocks:
            parts.append("")
            parts.append(block)

        parts.append("")
        parts.append(
            "Search and read the relevant code to verify the root cause before answering. "
            "Do not claim a call path you cannot see in the code; say what is unresolved."
        )
        return "\n".join(parts)

    def _extract_findings(self, answer: str) -> List[Dict[str, Any]]:
        prompt = (
            "Convert this debugging investigation into structured findings.\n\n"
            "Investigation:\n" + answer[:8000] + "\n\n"
            'Respond with ONLY a JSON object: {"summary": "<one paragraph>", "findings": '
            '[{"severity": "high|medium|low", "category": "bug|performance|security|readability", '
            '"title": "<short>", "lines": "<e.g. 42-48>", "description": "<root cause>", '
            '"suggestion": "<the fix>", "code_before": "<current code or null>", '
            '"code_after": "<fixed code or null>"}]}\n'
            "Use the exact code from the investigation for code_before/code_after."
        )
        try:
            response = self.client.chat.completions.create(
                model=self.model,
                messages=[
                    {"role": "system", "content": "You are a senior engineer. Respond with only valid JSON."},
                    {"role": "user", "content": prompt},
                ],
                temperature=0.2,
                max_tokens=2000,
            )
            parsed = extract_json_object(response.choices[0].message.content or "")
        except Exception:
            parsed = None

        if not parsed:
            # Degrade gracefully: the investigation itself is still the answer.
            return [{
                "severity": "medium",
                "category": "bug",
                "title": "Root cause analysis",
                "lines": "unknown",
                "description": answer[:4000],
                "suggestion": "",
                "code_before": None,
                "code_after": None,
            }]
        return sanitize_findings(parsed.get("findings"))
import json
import os
from typing import Any, Dict, List, Optional


def _optional_str(value: Any) -> Optional[str]:
    return str(value) if value else None


def _extract_json_object(raw: str) -> Optional[Dict[str, Any]]:
    """Extract the first JSON object from LLM output, tolerating markdown
    fences and surrounding commentary."""
    start, end = raw.find("{"), raw.rfind("}")
    if start == -1 or end <= start:
        return None
    try:
        parsed = json.loads(raw[start : end + 1])
    except json.JSONDecodeError:
        return None
    return parsed if isinstance(parsed, dict) else None


class ReviewService:
    """Reviews a single project file for bugs, performance, security and
    readability issues, returning structured findings.

    This is a deterministic pipeline (read file -> find dependencies ->
    find usages -> one LLM call), NOT the investigation agent loop: the task
    is bounded to a known file, so a fixed pipeline is faster, cheaper and
    reliably produces structured output.
    """

    MAX_REVIEW_LINES = 400
    MAX_FINDINGS = 5
    MAX_RELATED_SNIPPETS = 3
    MAX_SNIPPET_CHARS = 1200
    MAX_TOKENS = 2000

    VALID_SEVERITIES = {"high", "medium", "low"}
    VALID_CATEGORIES = {"bug", "performance", "security", "readability"}

    def __init__(self, tools, groq_client):
        self.tools = tools
        self.client = groq_client
        self.model = os.getenv("LLM_MODEL")

    def review_file(self, file_path: str, project_id: str) -> Dict[str, Any]:
        """Review one file and return {"file_path", "summary", "findings"}."""
        file_content = self.tools.read_file(file_path, project_id)
        if "error" in file_content:
            raise FileNotFoundError(file_content["error"])

        deps = self.tools.find_dependencies(file_path, project_id)
        related = self.tools.semantic_search(
            file_path, project_id, limit=self.MAX_RELATED_SNIPPETS
        )

        lines = file_content["content"].splitlines()
        truncated = len(lines) > self.MAX_REVIEW_LINES
        if truncated:
            lines = lines[: self.MAX_REVIEW_LINES]

        prompt = self._build_prompt(
            file_path=file_path,
            code="\n".join(lines),
            line_count=len(lines),
            truncated=truncated,
            imports=deps.get("imports", []),
            referenced_by=deps.get("referenced_by", []),
            related=related,
        )

        response = self.client.chat.completions.create(
            model=self.model,
            messages=[
                {
                    "role": "system",
                    "content": "You are a senior code reviewer. Be precise and pragmatic. Respond with only valid JSON.",
                },
                {"role": "user", "content": prompt},
            ],
            temperature=0.2,
            max_tokens=self.MAX_TOKENS,
        )

        raw = response.choices[0].message.content or ""
        parsed = self._parse_review(raw)
        return {"file_path": file_path, **parsed}


    def _build_prompt(
        self,
        file_path: str,
        code: str,
        line_count: int,
        truncated: bool,
        imports: List[str],
        referenced_by: List[str],
        related: List[Dict],
    ) -> str:
        related_block = "\n\n".join(
            f"{r['file_path']} (L{r['start_line']}-{r['end_line']}):\n"
            f"{r['content'][: self.MAX_SNIPPET_CHARS]}"
            for r in related
        ) or "(none found)"
        imports_block = "\n".join(imports[:20]) or "(none)"
        referenced_block = "\n".join(referenced_by[:20]) or "(none)"
        note = (
            f"\nNOTE: this file is long; only the first {line_count} lines are shown.\n"
            if truncated
            else ""
        )

        return f"""Review this file from a larger codebase for real issues.

File: {file_path}{note}

Imports in this file:
{imports_block}

Other project files that reference it:
{referenced_block}

How it is used elsewhere (retrieved via semantic search):
{related_block}

Code (lines 1-{line_count}):
```
{code}
```

Find up to {self.MAX_FINDINGS} issues, most severe first. Only report concrete,
verifiable issues grounded in this code — no generic advice, no style nitpicks.
Categories: bug, performance, security, readability.

Respond with ONLY a JSON object in exactly this shape (no markdown fences,
no commentary):
{{"summary": "<one paragraph overview of the file's health>",
  "findings": [{{"severity": "high|medium|low",
                 "category": "bug|performance|security|readability",
                 "title": "<short issue title>",
                 "lines": "<e.g. 42-48>",
                 "description": "<what is wrong and why it matters>",
                 "suggestion": "<how to fix it>",
                 "code_before": "<current code or null>",
                 "code_after": "<fixed code or null>"}}]}}

If the file has no real issues, return an empty findings array."""

    def _parse_review(self, raw: str) -> Dict[str, Any]:
        parsed = _extract_json_object(raw)
        if parsed is None:
            # Degrade gracefully: surface the raw review instead of failing
            return {
                "summary": "The review could not be parsed into structured findings.",
                "findings": [
                    {
                        "severity": "medium",
                        "category": "readability",
                        "title": "Unstructured review output",
                        "lines": "unknown",
                        "description": raw[:4000],
                        "suggestion": "",
                    }
                ],
            }
        return {
            "summary": str(parsed.get("summary", "")),
            "findings": self._sanitize_findings(parsed.get("findings")),
        }

    def _sanitize_findings(self, raw_findings: Any) -> List[Dict[str, Any]]:
        """Keep only well-formed findings and pin fields to allowed values."""
        if not isinstance(raw_findings, list):
            return []

        findings = []
        for item in raw_findings:
            if not isinstance(item, dict) or not item.get("title"):
                continue

            severity = str(item.get("severity", "medium")).lower()
            category = str(item.get("category", "readability")).lower()
            findings.append(
                {
                    "severity": severity if severity in self.VALID_SEVERITIES else "medium",
                    "category": category if category in self.VALID_CATEGORIES else "readability",
                    "title": str(item["title"])[:200],
                    "lines": str(item.get("lines", "unknown")),
                    "description": str(item.get("description", "")),
                    "suggestion": str(item.get("suggestion", "")),
                    "code_before": _optional_str(item.get("code_before")),
                    "code_after": _optional_str(item.get("code_after")),
                }
            )
            if len(findings) >= self.MAX_FINDINGS:
                break

        return findings

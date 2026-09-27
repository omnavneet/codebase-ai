"""Shared helpers for turning LLM output into structured findings.

Used by the code-review pipeline and the debugging pipeline so both produce the
same finding shape and never trust unvalidated model output.
"""
import json
from typing import Any, Dict, List, Optional

MAX_FINDINGS = 5
VALID_SEVERITIES = {"high", "medium", "low"}
VALID_CATEGORIES = {"bug", "performance", "security", "readability"}


def optional_str(value: Any) -> Optional[str]:
    return str(value) if value else None


def extract_json_object(raw: str) -> Optional[Dict[str, Any]]:
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


def sanitize_findings(raw_findings: Any, max_findings: int = MAX_FINDINGS) -> List[Dict[str, Any]]:
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
                "severity": severity if severity in VALID_SEVERITIES else "medium",
                "category": category if category in VALID_CATEGORIES else "readability",
                "title": str(item["title"])[:200],
                "lines": str(item.get("lines", "unknown")),
                "description": str(item.get("description", "")),
                "suggestion": str(item.get("suggestion", "")),
                "code_before": optional_str(item.get("code_before")),
                "code_after": optional_str(item.get("code_after")),
            }
        )
        if len(findings) >= max_findings:
            break

    return findings
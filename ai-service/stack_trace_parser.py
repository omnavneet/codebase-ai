"""Parses stack traces into structured frames.

Only formats with a well-defined grammar are parsed (Java, Python, JavaScript,
Go). Anything else is reported as unparsed so callers fall back to passing the
raw trace through — locations are never invented.
"""
import re
from typing import Any, Dict, List

JAVA_FRAME = re.compile(r"at\s+([\w$.]+)\.([\w$<>]+)\(([\w$./]+?\.java)(?::(\d+))?\)")
PYTHON_FRAME = re.compile(r'File\s+"([^"]+)",\s+line\s+(\d+)(?:,\s+in\s+([\w.<>$ ]+))?')
JS_FRAME = re.compile(
    r"at\s+(?:(.+?)\s+\()?(?:.*?)([\w./\\ ~-]+\.(?:js|jsx|ts|tsx|mjs|cjs)):(\d+):(\d+)")
GO_FRAME = re.compile(r"^\s*([\w./\\-]+\.(?:go)):(\d+)(?:\s|$)")

ERROR_TYPE = re.compile(r"\b([\w.$]+(?:Exception|Error))\b")
CAUSED_BY = re.compile(r"^\s*Caused by:")


def parse_stack_trace(text: str) -> Dict[str, Any]:
    result: Dict[str, Any] = {
        "language": None,
        "error_type": None,
        "frames": [],
        "parsed": False,
        "notes": [],
    }
    if not text or not text.strip():
        result["notes"].append("No stack trace provided")
        return result

    counts = {"java": 0, "python": 0, "javascript": 0, "go": 0}
    frames: List[Dict[str, Any]] = []
    error_type = None

    for raw_line in text.splitlines():
        line = raw_line.strip()
        if not line:
            continue

        if error_type is None:
            match = ERROR_TYPE.search(line)
            if match and not line.lower().startswith("at "):
                error_type = match.group(1)

        java = JAVA_FRAME.search(line)
        if java:
            counts["java"] += 1
            frames.append({
                "file": java.group(3),
                "line": int(java.group(4)) if java.group(4) else None,
                "symbol": f"{java.group(1)}.{java.group(2)}",
                "language": "java",
                "raw": line,
            })
            continue

        python = PYTHON_FRAME.search(line)
        if python:
            counts["python"] += 1
            frames.append({
                "file": python.group(1),
                "line": int(python.group(2)),
                "symbol": python.group(3),
                "language": "python",
                "raw": line,
            })
            continue

        javascript = JS_FRAME.search(line)
        if javascript:
            counts["javascript"] += 1
            frames.append({
                "file": javascript.group(2).strip(),
                "line": int(javascript.group(3)),
                "symbol": javascript.group(1) or None,
                "language": "javascript",
                "raw": line,
            })
            continue

        go = GO_FRAME.match(line)
        if go:
            counts["go"] += 1
            frames.append({
                "file": go.group(1),
                "line": int(go.group(2)),
                "symbol": None,
                "language": "go",
                "raw": line,
            })

    detected = max(counts, key=lambda key: counts[key])
    if counts[detected] > 0:
        result["language"] = detected
        result["frames"] = [f for f in frames if f["language"] == detected]
        result["parsed"] = True
        skipped = len(frames) - len(result["frames"])
        if skipped:
            result["notes"].append(f"{skipped} frame(s) in other languages were ignored")
    else:
        result["notes"].append("Stack trace format not recognised; passing it through unmodified")

    result["error_type"] = error_type
    return result


def resolve_project_file(name: str, project_files: List[str]) -> tuple:
    """Resolve an exact path or a unique longest matching component suffix."""
    def components(path):
        return tuple(part for part in str(path).replace("\\", "/").split("/")
                     if part and part != ".")

    target = components(name)
    if not target:
        return None, "missing"
    paths = [(components(path), path) for path in project_files]
    exact = [original for parts, original in paths if parts == target]
    if len(exact) == 1:
        return exact[0], None
    if len(exact) > 1:
        return None, "ambiguous"

    best_length = 0
    best_paths = []
    for parts, original in paths:
        length = 0
        for source, candidate in zip(reversed(target), reversed(parts)):
            if source != candidate:
                break
            length += 1
        if length > best_length:
            best_length = length
            best_paths = [original]
        elif length and length == best_length:
            best_paths.append(original)
    if not best_paths:
        return None, "missing"
    if len(best_paths) > 1:
        return None, "ambiguous"
    return best_paths[0], None


def project_frames(parsed: Dict[str, Any], project_files: List[str]) -> List[Dict[str, Any]]:
    """Return frames with an unambiguous project path."""
    if not parsed.get("parsed"):
        return []
    matches = []
    for frame in parsed["frames"]:
        path, _ = resolve_project_file(frame.get("file") or "", project_files)
        if path:
            matches.append({**frame, "project_path": path})
    return matches

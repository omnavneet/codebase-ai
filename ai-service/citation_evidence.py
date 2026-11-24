import json
import re

from storage import normalize_relative_path


_REFERENCE = re.compile(r"([\w./-]+\.[\w-]+)(?::(\d+)(?:-(\d+))?| \(line (\d+)\))")


def tool_sources(messages):
    for message in messages:
        if not isinstance(message, dict) or message.get("role") != "tool":
            continue
        try:
            result = json.loads(message["content"])
        except (TypeError, ValueError):
            continue
        if not isinstance(result, dict) or "error" in result:
            continue
        yield result
        yield from result.get("results", [])
        if result.get("selected_source"):
            yield result["selected_source"]
        for match in result.get("matches", []):
            yield {**match, "content": match.get("text"), "start_line": match.get("line")}


def verified_citations(answer, sources, resolve_ids):
    if not answer or resolve_ids is None:
        return []
    observed = {}
    for source in sources:
        path = source.get("file_path")
        content = source.get("content")
        start = source.get("start_line")
        if not path or not content or (start is not None and start < 1):
            continue
        try:
            path = normalize_relative_path(path)
        except ValueError:
            continue
        # Indexing and file reads count LF boundaries, including CRLF files.
        lines = content.split("\n")
        if not lines[-1]:
            lines.pop()
        observed.setdefault(path, []).append((start or 1, lines))

    citations = {}
    for match in _REFERENCE.finditer(answer):
        reference_path, start, end, single = match.groups()
        path = reference_path
        start = int(start or single)
        end = int(end or start)
        if not 1 <= start <= end:
            continue
        if path not in observed:
            matches = [candidate for candidate in observed if candidate.endswith("/" + path)]
            if len(matches) != 1:
                continue
            path = matches[0]
        for first, lines in observed[path]:
            if first <= start <= end < first + len(lines):
                citation = citations.setdefault((path, start, end), {
                    "file_path": path, "start_line": start, "end_line": end,
                    "content": "\n".join(lines[start - first:end - first + 1]) + "\n",
                    "reference_paths": [],
                })
                if reference_path not in citation["reference_paths"]:
                    citation["reference_paths"].append(reference_path)
                break
    if not citations:
        return []
    try:
        ids = resolve_ids(list(dict.fromkeys(path for path, _, _ in citations)))
    except TimeoutError:
        return []
    return [{**citation, "file_id": ids[path]} for (path, _, _), citation in citations.items() if path in ids]

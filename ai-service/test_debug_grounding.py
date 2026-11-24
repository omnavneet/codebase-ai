from debug_service import DebugService
from stack_trace_parser import parse_stack_trace, project_frames, resolve_project_file


def test_frame_uses_longest_component_suffix_regardless_of_file_order():
    parsed = parse_stack_trace('File "/srv/api/routes.py", line 20, in handler')
    frames = project_frames(parsed, ["admin/routes.py", "api/routes.py"])
    assert frames[0]["project_path"] == "api/routes.py"


def test_ambiguous_and_partial_names_are_not_resolved():
    files = ["admin/routes.py", "api/routes.py", "api/old_routes.py"]
    assert resolve_project_file("routes.py", files) == (None, "ambiguous")
    assert resolve_project_file("missing.py", files) == (None, "missing")
    assert resolve_project_file("/srv/api/routes.py", ["api/old_routes.py"]) == (None, "missing")
    assert resolve_project_file("api/routes.py", files) == ("api/routes.py", None)


class FakeTools:
    def __init__(self, files=None):
        self.files = files or {}
        self.reads = []

    def list_files(self, project_id):
        return list(self.files)

    def grep(self, term, project_id, max_matches):
        return {"matches": []}

    def semantic_search(self, description, project_id, limit):
        return []

    def read_file(self, path, project_id, start, end):
        self.reads.append((path, start, end))
        if path not in self.files:
            return {"error": "missing"}
        lines = self.files[path].splitlines(keepends=True)
        return {"content": "".join(lines[start - 1:end]), "start_line": start,
                "end_line": end, "total_lines": len(lines)}


def run_debug(monkeypatch, tools, trace, description="Request fails"):
    seen = {}

    def call(client, model, messages, **kwargs):
        seen["prompt"] = messages[-1]["content"]
        return "diagnosis", False, None

    monkeypatch.setattr("debug_service.streaming_model_call", call)
    result = DebugService(tools, object()).debug(description, "project", stack_trace=trace)
    return result, seen["prompt"]


def test_prompt_preserves_unrecognized_stack_trace_and_reports_missing_evidence(monkeypatch):
    trace = "Unsupported VM frame and important failure message"
    result, prompt = run_debug(monkeypatch, FakeTools(), trace)
    assert not result["stack_trace_parsed"]
    assert trace in prompt
    assert "format not recognised" in prompt
    assert "(no relevant code was found in the indexed project)" in prompt


def test_ambiguous_frame_is_reported_and_does_not_read_either_file(monkeypatch):
    tools = FakeTools({"admin/routes.py": "admin\n", "api/routes.py": "api\n"})
    trace = 'File "routes.py", line 20, in handler'
    result, prompt = run_debug(monkeypatch, tools, trace)
    assert result["project_frames"] == []
    assert any("ambiguous" in note for note in result["stack_trace_notes"])
    assert "ambiguous" in prompt
    assert tools.reads == []


def test_code_evidence_reports_actual_displayed_line_range(monkeypatch):
    tools = FakeTools({"api/routes.py": "first\nsecond\nthird\n"})
    _, prompt = run_debug(
        monkeypatch, tools, 'File "/srv/api/routes.py", line 2, in handler')
    assert "api/routes.py (lines 1-3):\nfirst\nsecond\nthird" in prompt
    assert "frame line numbers are unverified" in prompt


def test_out_of_range_frame_does_not_claim_empty_code_as_evidence(monkeypatch):
    tools = FakeTools({"api/routes.py": "first\nsecond\n"})
    result, prompt = run_debug(
        monkeypatch, tools, 'File "/srv/api/routes.py", line 999, in handler')
    assert result["files_read"] == []
    assert "(no relevant code was found in the indexed project)" in prompt


def test_stack_trace_is_bounded_in_prompt(monkeypatch):
    trace = "x" * (DebugService.MAX_TRACE_CHARS + 100)
    _, prompt = run_debug(monkeypatch, FakeTools(), trace)
    assert "x" * (DebugService.MAX_TRACE_CHARS + 1) not in prompt
    assert "(stack trace truncated)" in prompt


def test_debug_citations_only_link_to_code_in_the_prompt(monkeypatch):
    tools = FakeTools({"api/routes.py": "first\nsecond\n"})
    tools.source_file_ids = lambda project, paths, *, timeout: {"api/routes.py": "pinned-file"}
    monkeypatch.setattr("debug_service.streaming_model_call", lambda *args, **kwargs: (
        "`api/routes.py:2` and `api/routes.py:3`", False, None))

    result = DebugService(tools, object()).debug(
        "Request fails", "project", stack_trace='File "api/routes.py", line 2, in handler')

    assert result["citations"] == [{"file_path": "api/routes.py", "file_id": "pinned-file",
                                    "start_line": 2, "end_line": 2, "content": "second\n",
                                    "reference_paths": ["api/routes.py"]}]

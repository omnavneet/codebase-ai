"""Repository snapshots remain consistent during investigation runs."""

import json
import types

import pytest

from agent import CodebaseAgent
from agent_tools import AgentTools
from debug_service import DebugService
from explain_service import ExplainService
from storage import LocalStorage, S3Storage
from test_agent import FakeMcpClient, FakeCompletions
from tool_registry import REGISTRY, ToolInvocationError


PROJECT = "62775e3c-2dc3-4394-b73f-a80b7ad8a064"
OLD = "d8adbf54-808f-40b6-8ba3-714ac6019be1"
NEW = "e096e41d-b851-4e24-912e-e81ece112bd3"
STAGED = "ad10dd26-b895-45f3-a76a-1d24700c143f"


class Cursor:
    def __init__(self, state):
        self.state = state
        self.sql = ""
        self.params = ()

    def execute(self, sql, params):
        self.sql, self.params = sql, params
        self.state["queries"].append((sql, params))

    def fetchone(self):
        if "active_generation_id" in self.sql:
            return (self.state["active"],)
        generation = self.params[1]
        return (generation is None or generation in self.state["published"],)

    def fetchall(self):
        if "SELECT path FROM files" in self.sql:
            return [(path,) for path in self.state["files"].get(self.params[1], [])]
        return []

    def close(self):
        pass


class Connection:
    def __init__(self, state):
        self.state = state

    def cursor(self, **kwargs):
        return Cursor(self.state)

    def close(self):
        pass


@pytest.fixture
def indexed_tools(tmp_path):
    state = {
        "active": OLD,
        "published": {OLD, NEW},
        "files": {OLD: ["src/app.py"], NEW: ["src/app.py"], None: ["legacy.py"]},
        "queries": [],
    }
    for generation, contents in ((OLD, "old version\n"), (NEW, "new version\n"),
                                 (STAGED, "unpublished\n")):
        path = tmp_path / ".generations" / PROJECT / generation / "src" / "app.py"
        path.parent.mkdir(parents=True)
        path.write_text(contents, encoding="utf-8")
    legacy = tmp_path / PROJECT / "legacy.py"
    legacy.parent.mkdir()
    legacy.write_text("legacy version\n", encoding="utf-8")
    tools = AgentTools({}, None, str(tmp_path))
    tools._get_db_connection = lambda: Connection(state)
    return tools, state


def test_fixed_run_keeps_files_and_queries_on_original_generation(indexed_tools):
    tools, state = indexed_tools
    with tools.generation_scope(PROJECT):
        assert tools.read_file("src/app.py", PROJECT)["content"] == "old version\n"
        state["active"] = NEW
        assert tools.list_files(PROJECT) == ["src/app.py"]
        assert tools.grep("version", PROJECT)["matches"][0]["text"] == "old version"
        assert tools.read_file("src/app.py", PROJECT)["content"] == "old version\n"
    assert tools.read_file("src/app.py", PROJECT)["content"] == "new version\n"
    files_query = next(params for sql, params in state["queries"] if "SELECT path FROM files" in sql)
    assert files_query == (PROJECT, OLD)


def test_mcp_generation_selection_rejects_unpublished_and_preserves_null(indexed_tools):
    tools, state = indexed_tools
    read = REGISTRY.get("read_file")
    state["active"] = NEW
    result = read.invoke(tools, {"project_id": PROJECT, "generation_id": OLD, "file_path": "src/app.py"})
    assert result.content == "old version\n"
    legacy = read.invoke(tools, {"project_id": PROJECT, "generation_id": None, "file_path": "legacy.py"})
    assert legacy.content == "legacy version\n"
    with pytest.raises(ToolInvocationError, match="not published"):
        read.invoke(tools, {"project_id": PROJECT, "generation_id": STAGED, "file_path": "src/app.py"})


@pytest.mark.parametrize("service_class,method,delegate", [
    (ExplainService, "explain", "_explain"),
    (DebugService, "debug", "_debug"),
])
def test_analyze_pipelines_pin_generation_for_entire_run(indexed_tools, service_class, method, delegate):
    tools, state = indexed_tools
    service = service_class(tools, object())

    def pipeline(*args):
        state["active"] = NEW
        return tools.read_file("src/app.py", PROJECT)["content"]

    setattr(service, delegate, pipeline)
    assert getattr(service, method)("request", PROJECT) == "old version\n"


def test_agent_injects_pinned_generation_and_hides_it_from_model():
    mcp = FakeMcpClient()
    call = types.SimpleNamespace(
        id="call-1",
        function=types.SimpleNamespace(
            name="search_code",
            arguments=json.dumps({"query": "login", "generation_id": STAGED}),
        ),
    )
    agent = CodebaseAgent(object(), mcp, generation_resolver=lambda project, *, timeout: OLD)
    agent.client = types.SimpleNamespace(chat=types.SimpleNamespace(completions=FakeCompletions([
        types.SimpleNamespace(content=None, tool_calls=[call]),
        types.SimpleNamespace(content="Found it", tool_calls=None),
    ])))
    agent.investigate("where is login?", PROJECT)
    assert mcp.calls[0][1]["generation_id"] == OLD
    definitions = agent.client.chat.completions.requests[0]["tools"]
    assert all("generation_id" not in item["function"]["parameters"]["properties"]
               for item in definitions)


def test_generation_paths_are_separate_for_local_and_s3(tmp_path):
    local = LocalStorage(str(tmp_path))
    assert local.project_root(PROJECT, OLD) == tmp_path / ".generations" / PROJECT / OLD
    storage = S3Storage("bucket", prefix="projects")
    assert storage.key_for(PROJECT, "src/app.py", OLD) == (
        f"projects/.generations/{PROJECT}/{OLD}/src/app.py"
    )
    assert storage.key_for(PROJECT, "src/app.py") == f"projects/{PROJECT}/src/app.py"

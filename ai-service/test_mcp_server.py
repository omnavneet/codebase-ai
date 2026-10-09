"""Contract and security tests for the MCP tool server.

Three layers are pinned here:

* the contract, parametrized over every registered tool: a valid call must
  return structured output that validates against the tool's output model,
  and an invalid call must come back as a clean tool error (never a crash);
* project isolation and path traversal, exercised against the real
  ``AgentTools`` over local storage;
* the HTTP surface: ``/health`` is public, everything else demands the shared
  bearer token, and a real MCP round-trip works with it.

Run with the rest of the suite: ``python -m pytest``
"""

import asyncio
import json
import sys
import threading
import time
import types
import urllib.error
import urllib.request

import pytest

# agent_tools imports psycopg2 at module scope. Stub it so this module runs
# with a bare interpreter, exactly as test_core_safety and test_storage do.
psycopg2_stub = types.ModuleType("psycopg2")
psycopg2_extras_stub = types.ModuleType("psycopg2.extras")
psycopg2_extras_stub.RealDictCursor = object
psycopg2_stub.extras = psycopg2_extras_stub
sys.modules.setdefault("psycopg2", psycopg2_stub)
sys.modules.setdefault("psycopg2.extras", psycopg2_extras_stub)

from mcp import Client

from agent_tools import AgentTools
from mcp_client import McpToolClient
from mcp_server import build_mcp_server, create_app
from tool_registry import REGISTRY, ToolRegistry, ToolSpec

TOKEN = "test-token"
PROJECT = "project-1"


class FakeTools:
    """Duck-typed AgentTools returning schema-shaped canned data.

    Keeps the contract tests hermetic (no Postgres, no embedding model); the
    real tools get their own isolation tests further down.
    """

    def semantic_search(self, query, project_id, limit=None):
        return [{
            "chunk_id": "c1",
            "file_path": "src/app.py",
            "file_id": "f1",
            "content": "def login():\n    pass\n",
            "start_line": 1,
            "end_line": 2,
            "similarity": 0.91,
        }]

    def read_file(self, file_path, project_id, start_line=None, end_line=None):
        if file_path == "missing.py":
            return {"error": f"File not found: {file_path}"}
        return {
            "file_path": file_path,
            "content": "def login():\n    pass\n",
            "total_lines": 2,
            "partial": False,
        }

    def list_files(self, project_id):
        return ["README.md", "src/app.py"]

    def grep(self, pattern, project_id, file_path="", max_matches=None):
        result = {
            "pattern": pattern,
            "matches": [{"file_path": "src/app.py", "line": 1, "text": "def login():"}],
            "files_scanned": 1,
        }
        if file_path:
            result["file_path"] = file_path
        return result

    def find_dependencies(self, file_path, project_id):
        return {
            "imports": [{"target": "os", "line": 1, "resolved_path": None, "external": True}],
            "referenced_by": [],
            "language": "python",
        }

    def analyze_symbol(self, name, project_id, file_path=None):
        return {
            "name": name,
            "found": True,
            "candidates": [{
                "name": name,
                "kind": "function",
                "parent_symbol": None,
                "file_path": "src/app.py",
                "start_line": 1,
                "end_line": 2,
                "signature": f"def {name}()",
            }],
            "selected_source": None,
            "callers": [],
            "callees": [],
            "notes": [],
        }


VALID_ARGUMENTS = {
    "search_code": {"project_id": PROJECT, "query": "where is login handled", "limit": 3},
    "read_file": {"project_id": PROJECT, "file_path": "src/app.py"},
    "list_files": {"project_id": PROJECT},
    "grep": {"project_id": PROJECT, "pattern": "login", "file_path": "src/app.py"},
    "find_dependencies": {"project_id": PROJECT, "file_path": "src/app.py"},
    "analyze_symbol": {"project_id": PROJECT, "name": "login"},
}

INVALID_ARGUMENTS = [
    pytest.param({}, "Field required", id="missing-required-fields"),
    pytest.param({"project_id": 123}, "valid string", id="wrong-type"),
]


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _run(coro):
    return asyncio.run(coro)


def call(server, name, arguments):
    """Call a tool through an in-process MCP client (full protocol, no HTTP)."""
    async def _call():
        async with Client(server) as client:
            return await client.call_tool(name, arguments)

    return _run(_call())


def discover(server):
    """List tools through an in-process MCP client."""
    async def _discover():
        async with Client(server) as client:
            page = await client.list_tools()
            return page.tools

    return _run(_discover())


def error_text(result) -> str:
    return "\n".join(block.text for block in result.content or [])


@pytest.fixture()
def mcp_server():
    return build_mcp_server(FakeTools())


# ---------------------------------------------------------------------------
# Contract: one parametrized test over every tool, plus the discovery contract
# ---------------------------------------------------------------------------

@pytest.mark.parametrize("spec", REGISTRY.list(), ids=REGISTRY.names())
def test_contract_valid_input_returns_valid_output(mcp_server, spec):
    result = call(mcp_server, spec.name, VALID_ARGUMENTS[spec.name])

    assert not result.is_error, error_text(result)
    assert result.structured_content is not None, error_text(result)
    # The structured output validates against the registry's output model.
    output = spec.output_model.model_validate(result.structured_content)
    assert output.model_dump(exclude_none=True)


@pytest.mark.parametrize("arguments,needle", INVALID_ARGUMENTS)
@pytest.mark.parametrize("spec", REGISTRY.list(), ids=REGISTRY.names())
def test_contract_invalid_input_is_a_clean_error(mcp_server, spec, arguments, needle):
    result = call(mcp_server, spec.name, arguments)

    assert result.is_error, "invalid input must come back as a tool error"
    text = error_text(result)
    assert "validation error" in text
    assert needle in text
    assert "Traceback" not in text


def test_server_exposes_exactly_the_registry_tools(mcp_server):
    tools = discover(mcp_server)

    assert [tool.name for tool in tools] == REGISTRY.names()
    for tool, spec in zip(tools, REGISTRY.list()):
        assert tool.description == spec.description
        # Read-only hint is how the agent decides what it may call.
        assert tool.annotations is not None
        assert tool.annotations.read_only_hint is True

        # The served input schema is the registry's Pydantic input model.
        served = tool.input_schema
        model_schema = spec.input_model.model_json_schema()
        assert served["type"] == "object"
        assert set(served["properties"]) == set(model_schema["properties"])
        assert set(served.get("required", [])) == set(model_schema.get("required", []))
        for name, prop in model_schema["properties"].items():
            assert served["properties"][name].get("description") == prop.get("description")

        # And the output schema is the registry's output model.
        assert tool.output_schema is not None
        assert set(tool.output_schema["properties"]) == set(
            spec.output_model.model_json_schema()["properties"]
        )


def test_agent_groq_schemas_match_the_registry(mcp_server):
    """What the agent builds from MCP discovery equals the local registry."""
    from tool_registry import groq_function_from_mcp

    discovered = [
        groq_function_from_mcp(tool.name, tool.description, tool.input_schema)
        for tool in discover(mcp_server)
    ]
    assert discovered == REGISTRY.groq_definitions()


def test_non_read_tools_are_never_exposed():
    """A write-level tool in the registry must not reach the MCP surface."""
    write_spec = ToolSpec(
        name="write_thing",
        description="Mutates the project",
        input_model=REGISTRY.get("list_files").input_model,
        output_model=REGISTRY.get("list_files").output_model,
        permission="write",
        handler=lambda tools, args: REGISTRY.get("list_files").output_model(files=[]),
    )
    server = build_mcp_server(FakeTools(), registry=ToolRegistry(REGISTRY.list() + [write_spec]))

    names = [tool.name for tool in discover(server)]
    assert "write_thing" not in names
    assert names == REGISTRY.names()


# ---------------------------------------------------------------------------
# Project isolation and path traversal (real AgentTools over local storage)
# ---------------------------------------------------------------------------

class _EmptyCursor:
    def execute(self, *args, **kwargs):
        return None

    def fetchall(self):
        return []

    def close(self):
        return None


class _EmptyConnection:
    """Stands in for a database connection that has no indexed files."""

    def cursor(self, *args, **kwargs):
        return _EmptyCursor()

    def close(self):
        return None


@pytest.fixture()
def real_tools(tmp_path):
    """Two projects on local storage; project-1 is the one under attack."""
    project = tmp_path / PROJECT
    (project / "src").mkdir(parents=True)
    (project / "README.md").write_text("# project one\n", encoding="utf-8")
    (project / "src" / "app.py").write_text("def login():\n    pass\n", encoding="utf-8")

    other = tmp_path / "project-2"
    other.mkdir()
    (other / "secret.txt").write_text("nope\n", encoding="utf-8")

    tools = AgentTools({}, None, str(tmp_path))
    # Nothing is indexed in this fixture, so list_files falls back to storage
    # (same trick as test_storage; no Postgres in the unit-test environment).
    tools._get_db_connection = lambda: _EmptyConnection()
    return tools


def test_cross_project_access_is_denied(real_tools):
    server = build_mcp_server(real_tools)

    # project-2's file does not exist inside project-1's tree.
    missing = call(server, "read_file", {"project_id": PROJECT, "file_path": "secret.txt"})
    assert missing.is_error
    assert "File not found" in error_text(missing)

    # The listing only ever shows this project's files.
    listing = call(server, "list_files", {"project_id": PROJECT})
    assert set(listing.structured_content["files"]) == {"README.md", "src/app.py"}

    # A content search over project-1 cannot see project-2's contents.
    hits = call(server, "grep", {"project_id": PROJECT, "pattern": "nope"})
    assert hits.structured_content["matches"] == []


def test_path_traversal_is_blocked(real_tools):
    server = build_mcp_server(real_tools)
    cases = [
        ("read_file", {"project_id": PROJECT, "file_path": "../project-2/secret.txt"}, "traversal"),
        ("read_file", {"project_id": PROJECT, "file_path": "/etc/passwd"}, "Absolute paths"),
        ("read_file", {"project_id": "../project-2", "file_path": "secret.txt"}, "Invalid project id"),
        ("grep", {"project_id": PROJECT, "pattern": "x", "file_path": "../project-2/secret.txt"}, "traversal"),
    ]

    for name, arguments, needle in cases:
        result = call(server, name, arguments)
        assert result.is_error, (name, arguments)
        text = error_text(result)
        assert needle in text
        assert "Traceback" not in text


# ---------------------------------------------------------------------------
# HTTP surface: health, bearer auth, and a real round-trip
# ---------------------------------------------------------------------------

def _http(url, token=None, method="GET", body=None):
    request = urllib.request.Request(url, data=body, method=method)
    if body is not None:
        request.add_header("Content-Type", "application/json")
    if token:
        request.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(request) as response:
            payload = response.read()
            status = response.status
    except urllib.error.HTTPError as exc:  # HTTPError is also the response
        payload = exc.read()
        status = exc.code
    try:
        return status, json.loads(payload)
    except ValueError:
        return status, payload.decode("utf-8", "replace")


@pytest.fixture(scope="module")
def live_server():
    """The real app (auth + transport security) served by uvicorn on a thread."""
    import uvicorn

    app = create_app(
        tools=FakeTools(),
        token=TOKEN,
        allowed_hosts=["127.0.0.1:*", "localhost:*"],
    )
    config = uvicorn.Config(app, host="127.0.0.1", port=0, log_level="warning")
    server = uvicorn.Server(config)
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()

    deadline = time.time() + 15
    while not server.started:
        if time.time() > deadline:
            raise RuntimeError("the MCP test server did not start in time")
        time.sleep(0.05)
    port = server.servers[0].sockets[0].getsockname()[1]

    yield f"http://127.0.0.1:{port}"

    server.should_exit = True
    thread.join(timeout=10)


def test_health_needs_no_token(live_server):
    status, payload = _http(f"{live_server}/health")
    assert status == 200
    assert payload["status"] == "UP"


def test_mcp_endpoint_rejects_missing_and_invalid_tokens(live_server):
    body = b'{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}'

    status, payload = _http(f"{live_server}/mcp", method="POST", body=body)
    assert status == 401, payload
    assert payload["detail"] == "Unauthorized"

    status, payload = _http(f"{live_server}/mcp", method="POST", body=body, token="wrong-token")
    assert status == 403, payload
    assert payload["detail"] == "Forbidden"

    # Every MCP method is gated, not just POST.
    status, _ = _http(f"{live_server}/mcp")
    assert status == 401


def test_valid_token_completes_a_real_mcp_round_trip(live_server):
    """The agent's client path end-to-end: auth + Streamable HTTP + tools."""
    client = McpToolClient(url=f"{live_server}/mcp", token=TOKEN)

    tools = client.list_tools()
    assert {tool["name"] for tool in tools} == set(REGISTRY.names())
    assert all(tool["read_only"] for tool in tools)

    result = client.call_tool("list_files", {"project_id": PROJECT})
    assert result["files"] == ["README.md", "src/app.py"]

    failed = client.call_tool("read_file", {"project_id": PROJECT, "file_path": "missing.py"})
    assert "File not found" in failed["error"]


def test_client_without_a_token_cannot_connect(live_server):
    client = McpToolClient(url=f"{live_server}/mcp", token="wrong-token")
    with pytest.raises(Exception):
        client.list_tools()




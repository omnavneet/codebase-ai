"""Tests for the agent's MCP-driven tool discovery and execution.

The agent must build its Groq tool schemas from what the MCP server
advertises (no hardcoded list), drop anything that is not read-level, and
execute calls with the investigation's project_id overwriting whatever the
model may have sent.
"""

import json
import threading
import types

from agent import CodebaseAgent
from agent_tools import AgentTools
from tool_registry import REGISTRY


def discovery_payload():
    """What the MCP server advertises, shaped as McpToolClient returns it."""
    return [
        {
            "name": spec.name,
            "description": spec.description,
            "input_schema": spec.input_model.model_json_schema(),
            "read_only": spec.permission == "read",
        }
        for spec in REGISTRY.list()
    ]


class FakeMcpClient:
    def __init__(self, tools=None):
        self.tools = discovery_payload() if tools is None else tools
        self.calls = []

    def list_tools(self):
        return self.tools

    def call_tool(self, name, arguments):
        self.calls.append((name, arguments))
        return {"ok": True, "tool": name}


class FakeCompletions:
    """Scripted Groq responses; records every create() call."""

    def __init__(self, responses):
        self._responses = list(responses)
        self.requests = []

    def create(self, **kwargs):
        self.requests.append(kwargs)
        message = self._responses.pop(0)
        return types.SimpleNamespace(choices=[types.SimpleNamespace(message=message)])


def make_agent(mcp=None, responses=()):
    agent = CodebaseAgent(groq_client=object(), mcp_client=mcp or FakeMcpClient())
    agent.client = types.SimpleNamespace(
        chat=types.SimpleNamespace(completions=FakeCompletions(list(responses)))
    )
    return agent


def test_tool_definitions_are_discovered_not_hardcoded():
    agent = make_agent()
    assert agent.tool_definitions is None  # nothing is baked in

    definitions = agent.discover_tools()
    assert {definition["function"]["name"] for definition in definitions} == set(
        REGISTRY.names()
    )
    for definition in definitions:
        assert definition["type"] == "function"
        assert definition["function"]["description"]
        # project_id is the agent's to supply, never the model's to choose.
        assert "project_id" not in definition["function"]["parameters"]["properties"]


def test_discovered_definitions_match_the_registry():
    agent = make_agent()
    assert agent.discover_tools() == REGISTRY.groq_definitions()


def test_non_read_only_tools_are_dropped():
    tools = discovery_payload() + [
        {
            "name": "write_back",
            "description": "Mutates the project",
            "input_schema": {"type": "object", "properties": {}},
            "read_only": False,
        }
    ]
    agent = make_agent(mcp=FakeMcpClient(tools=tools))

    names = {definition["function"]["name"] for definition in agent.discover_tools()}
    assert "write_back" not in names
    assert names == set(REGISTRY.names())


def test_execute_injects_the_investigations_project_id():
    mcp = FakeMcpClient()
    agent = make_agent(mcp)
    agent.tool_definitions = agent.discover_tools()

    # Whatever the model claims as project_id is overwritten by the real one.
    result = agent._execute_tool(
        "grep", {"pattern": "login", "project_id": "attacker-project"}, "project-9"
    )

    assert result["ok"]
    name, arguments = mcp.calls[-1]
    assert name == "grep"
    assert arguments == {"pattern": "login", "project_id": "project-9"}


def test_execute_refuses_unknown_tools():
    agent = make_agent()
    agent.tool_definitions = agent.discover_tools()

    result = agent._execute_tool("rm_rf", {}, "project-1")
    assert result["error"].startswith("Unknown tool")


def test_execute_reports_mcp_failures_as_tool_errors():
    class ExplodingClient(FakeMcpClient):
        def call_tool(self, name, arguments):
            raise ConnectionError("mcp server is down")

    agent = make_agent(mcp=ExplodingClient())
    agent.tool_definitions = agent.discover_tools()

    result = agent._execute_tool("list_files", {}, "project-1")
    assert "Tool execution failed" in result["error"]


def test_investigate_runs_the_scripted_loop_through_mcp():
    mcp = FakeMcpClient()
    tool_call = types.SimpleNamespace(
        id="call-1",
        function=types.SimpleNamespace(
            name="search_code", arguments=json.dumps({"query": "login"})
        ),
    )
    agent = make_agent(
        mcp,
        responses=[
            types.SimpleNamespace(content=None, tool_calls=[tool_call]),
            types.SimpleNamespace(content="login lives in src/app.py", tool_calls=None),
        ],
    )

    result = agent.investigate("where is login?", "project-7")

    assert result["answer"] == "login lives in src/app.py"
    assert result["iterations"] == 2
    assert result["tool_calls"] == 1
    assert result["trace"] == ["Searched: login"]
    assert "truncated" not in result

    # Groq was handed the schemas discovered from the MCP server...
    completions = agent.client.chat.completions
    assert completions.requests[0]["tools"] == REGISTRY.groq_definitions()
    # ...and the call went through the MCP client, scoped to this project.
    assert mcp.calls == [("search_code", {"query": "login", "project_id": "project-7", "generation_id": "active"})]


def test_investigate_deduplicates_repeated_searches():
    mcp = FakeMcpClient()
    tool_call = types.SimpleNamespace(
        id="call-1",
        function=types.SimpleNamespace(
            name="search_code", arguments=json.dumps({"query": "login"})
        ),
    )
    agent = make_agent(
        mcp,
        responses=[
            types.SimpleNamespace(content=None, tool_calls=[tool_call]),
            types.SimpleNamespace(content=None, tool_calls=[tool_call]),
            types.SimpleNamespace(content="done", tool_calls=None),
        ],
    )

    result = agent.investigate("where is login?", "project-7")

    assert result["answer"] == "done"
    assert result["tool_calls"] == 2
    # The repeated query was answered from the dedup cache, not the server.
    assert len(mcp.calls) == 1
    assert result["searches_performed"] == ["login"]


def test_investigate_reads_distinct_ranges_and_retries_failed_reads(tmp_path, monkeypatch):
    project = tmp_path / "project-7"
    (project / "src").mkdir(parents=True)
    (project / "src" / "app.py").write_bytes(b"one\ntwo\nthree\nfour\n")
    tools = AgentTools({}, None, str(tmp_path))
    monkeypatch.setattr(tools, "_generation", lambda _: None)
    monkeypatch.setattr(tools, "READ_MAX_LINES", 2)
    late_read_attempts = 0

    class FileMcpClient(FakeMcpClient):
        def call_tool(self, name, arguments):
            nonlocal late_read_attempts
            super().call_tool(name, arguments)
            if arguments.get("start_line") == 4:
                late_read_attempts += 1
                if late_read_attempts == 1:
                    return {"error": "File temporarily unavailable"}
            return tools.read_file(arguments["file_path"], arguments["project_id"],
                                   arguments.get("start_line"), arguments.get("end_line"))

    def read(call_id, path="src/app.py", line=None):
        arguments = {"file_path": path}
        if line is not None:
            arguments.update(start_line=line, end_line=line)
        return types.SimpleNamespace(
            id=call_id, function=types.SimpleNamespace(name="read_file", arguments=json.dumps(arguments)),
        )

    mcp = FileMcpClient()
    agent = make_agent(mcp, responses=[
        types.SimpleNamespace(content=None, tool_calls=[read("clipped")]),
        types.SimpleNamespace(content=None, tool_calls=[
            read("three", line=3), read("four-failed", line=4),
            read("three-in-batch", "src\\app.py", line=3), read("missing", "missing.py"),
        ]),
        types.SimpleNamespace(content=None, tool_calls=[read("four-retry", line=4), read("three-repeat", line=3)]),
        types.SimpleNamespace(content="done", tool_calls=None),
    ])

    result = agent.investigate("read the file", "project-7")

    messages = agent.client.chat.completions.requests[-1]["messages"]
    observations = [(message["tool_call_id"], json.loads(message["content"]))
                    for message in messages if isinstance(message, dict) and message["role"] == "tool"]
    assert [call_id for call_id, _ in observations] == [
        "clipped", "three", "four-failed", "three-in-batch", "missing", "four-retry", "three-repeat",
    ]
    observed = dict(observations)
    assert observed["clipped"]["content"] == "one\ntwo\n"
    assert observed["three"]["content"] == "three\n"
    assert observed["three-in-batch"]["content"] == "three\n"
    assert observed["four-failed"] == {"error": "File temporarily unavailable"}
    assert "error" in observed["missing"]
    assert observed["four-retry"]["content"] == "four\n"
    assert "note" in observed["three-repeat"]
    assert [(args["file_path"], args.get("start_line")) for _, args in mcp.calls] == [
        ("src/app.py", None), ("src/app.py", 3), ("src/app.py", 4), ("missing.py", None), ("src/app.py", 4),
    ]
    assert result["files_read"] == ["src/app.py"]


def test_investigation_returns_pinned_citations_only_for_observed_lines():
    class EvidenceMcp(FakeMcpClient):
        def call_tool(self, name, arguments):
            return {"results": [{"file_path": "src/app.py", "start_line": 5,
                                 "end_line": 9, "content": "visible\n"},
                                {"file_path": "other/app.py", "start_line": 5,
                                 "end_line": 5, "content": "different\n"}]}

    call = types.SimpleNamespace(id="search", function=types.SimpleNamespace(
        name="search_code", arguments=json.dumps({"query": "visible"})))
    agent = make_agent(EvidenceMcp(), [
        types.SimpleNamespace(content=None, tool_calls=[call]),
        types.SimpleNamespace(content="`src/app.py:5`, `src/app.py:6`, and `app.py:5`", tool_calls=None),
    ])
    agent.generation_resolver = lambda project, **kwargs: "old-generation"
    def resolve_ids(project, paths, generation, *, timeout):
        assert 0 < timeout <= agent.run_timeout_seconds
        assert (project, paths, generation) == ("project-7", ["src/app.py"], "old-generation")
        return {"src/app.py": "old-file"}
    agent.resolve_source_ids = resolve_ids

    result = agent.investigate("where is the visible code?", "project-7")

    assert result["citations"] == [{"file_path": "src/app.py", "file_id": "old-file",
                                    "start_line": 5, "end_line": 5, "content": "visible\n",
                                    "reference_paths": ["src/app.py"]}]


def test_concurrent_investigations_keep_independent_context_retries():
    barrier = threading.Barrier(2)
    calls = threading.local()

    class ContextTooLong(Exception):
        status_code = 413

    class InterleavedCompletions:
        def create(self, **kwargs):
            calls.count = getattr(calls, "count", 0) + 1
            if calls.count == 1:
                barrier.wait(timeout=5)
                raise ContextTooLong()
            assert 0 < kwargs["timeout"] <= 60
            message = types.SimpleNamespace(content="complete", tool_calls=None)
            return types.SimpleNamespace(choices=[types.SimpleNamespace(message=message)])

    agent = make_agent()
    original_budget = agent.context_budget_chars
    agent.tool_definitions = agent.discover_tools()
    import time
    agent.tool_definitions_at = time.monotonic()
    agent.client.chat.completions = InterleavedCompletions()
    answers, errors = [], []

    def run():
        try:
            answers.append(agent.investigate("question", "project")["answer"])
        except Exception as exc:
            errors.append(exc)

    threads = [threading.Thread(target=run) for _ in range(2)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join(timeout=10)
    assert not errors
    assert answers == ["complete", "complete"]
    assert agent.context_budget_chars == original_budget


def test_precancelled_run_does_not_discover_or_call_model():
    agent = make_agent()
    cancel = threading.Event()
    cancel.set()
    result = agent.investigate("question", "project", cancel_event=cancel)
    assert result["stop_reason"] == "cancelled"
    assert agent.tool_definitions is None
    assert agent.client.chat.completions.requests == []


def test_cancellation_after_model_response_starts_no_tool_or_final_call():
    cancel = threading.Event()
    tool_call = types.SimpleNamespace(
        id="call-1",
        function=types.SimpleNamespace(name="search_code", arguments='{"query":"login"}'))
    agent = make_agent(responses=[types.SimpleNamespace(content=None, tool_calls=[tool_call])])
    original_create = agent.client.chat.completions.create

    def cancel_after_create(**kwargs):
        result = original_create(**kwargs)
        cancel.set()
        return result

    agent.client.chat.completions.create = cancel_after_create
    result = agent.investigate("question", "project", cancel_event=cancel)
    assert result["stop_reason"] == "cancelled"
    assert len(agent.client.chat.completions.requests) == 1
    assert agent.mcp.calls == []


def test_expired_deadline_does_not_start_model_call():
    agent = make_agent()
    agent.run_timeout_seconds = 0
    result = agent.investigate("question", "project")
    assert result["stop_reason"] == "timeout"
    assert agent.client.chat.completions.requests == []


def test_cancellation_interrupts_provider_retry_wait():
    cancel = threading.Event()
    class RateLimited(Exception):
        status_code = 429
        headers = {"Retry-After": "1"}

    class Completion:
        def __init__(self):
            self.calls = 0

        def create(self, **kwargs):
            self.calls += 1
            cancel.set()
            raise RateLimited()

    agent = make_agent()
    completion = Completion()
    agent.client.chat.completions = completion
    result = agent.investigate("question", "project", cancel_event=cancel)
    assert result["stop_reason"] == "cancelled"
    assert completion.calls == 1


def test_mcp_discovery_and_batch_obey_requested_timeout():
    import asyncio
    import pytest
    from mcp_client import McpToolClient, McpUnavailableError

    class SlowMcp(McpToolClient):
        async def _list_tools(self):
            await asyncio.sleep(1)
            return []

        async def _call_tools(self, calls):
            await asyncio.sleep(1)
            return []

    client = SlowMcp()
    with pytest.raises(McpUnavailableError):
        client.list_tools(timeout=0.01)
    with pytest.raises(McpUnavailableError):
        client.call_tools([("search_code", {})], timeout=0.01)


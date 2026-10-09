"""Tests for the agent's MCP-driven tool discovery and execution.

The agent must build its Groq tool schemas from what the MCP server
advertises (no hardcoded list), drop anything that is not read-level, and
execute calls with the investigation's project_id overwriting whatever the
model may have sent.
"""

import json
import types

from agent import CodebaseAgent
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
    assert mcp.calls == [("search_code", {"query": "login", "project_id": "project-7"})]


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


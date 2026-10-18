"""Synchronous bridge from the agent loop to the MCP tool server.

The MCP SDK's client is async, while the agent runs inside FastAPI's sync
endpoints, so operations run their own short event loops. Two call shapes are
offered: ``call_tool`` (one call, one connection) and ``call_tools`` — the
batch used by one agent iteration, which runs all of its calls inside ONE
connection and ONE event loop via ``asyncio.gather``. The per-connection cost
is a few localhost round-trips, and the benefit is no background thread, no
shared session state, and automatic recovery when the MCP server restarts.
"""

import asyncio
import os
from contextlib import asynccontextmanager
from typing import Any, Dict, List, Optional, Sequence, Tuple

import httpx2
from mcp import Client
from mcp.client.streamable_http import streamable_http_client

DEFAULT_MCP_URL = "http://127.0.0.1:8001/mcp"

# Fail fast when the tool server is down or wedged, instead of letting a call
# hang until the backend's 300s relay timeout. Connect and per-call budgets are
# short on purpose: the MCP server is a localhost service, so a healthy call is
# milliseconds; anything slow is a problem worth surfacing immediately.
MCP_CONNECT_TIMEOUT_S = float(os.getenv("MCP_CONNECT_TIMEOUT_SECONDS", "5"))
MCP_CALL_TIMEOUT_S = float(os.getenv("MCP_CALL_TIMEOUT_SECONDS", "30"))


class McpUnavailableError(RuntimeError):
    """The MCP tool server could not be reached or timed out."""


class McpToolClient:
    """Sync wrapper around one MCP server (list tools / call a tool)."""

    def __init__(self, url: Optional[str] = None, token: Optional[str] = None):
        self.url = url or os.getenv("MCP_SERVER_URL", DEFAULT_MCP_URL)
        self.token = token if token is not None else os.getenv("AI_SERVICE_INTERNAL_TOKEN", "")

    # -- public API (synchronous) ------------------------------------------

    def list_tools(self) -> List[Dict[str, Any]]:
        """Discover the server's tools as plain dicts (name/desc/schema/flags)."""
        try:
            return asyncio.run(asyncio.wait_for(self._list_tools(), MCP_CALL_TIMEOUT_S))
        except (asyncio.TimeoutError, OSError, httpx2.HTTPError) as e:
            raise McpUnavailableError(
                "The code-search tool server is not reachable. "
                "Start it (ai-service MCP server on port 8001) and try again."
            ) from e

    def call_tool(self, name: str, arguments: Dict[str, Any]) -> Any:
        """Call a tool; always returns a JSON-serialisable result.

        Transport failures come back as ``{"error": ...}`` so the agent loop
        can feed them to the model exactly like any other tool result. But a
        connection-level failure (server down / refused) raises
        ``McpUnavailableError`` so the caller can fail fast with a clear
        message instead of silently degrading the answer.
        """
        try:
            return asyncio.run(asyncio.wait_for(self._call_tool(name, arguments), MCP_CALL_TIMEOUT_S))
        except (asyncio.TimeoutError, OSError, httpx2.HTTPError) as e:
            raise McpUnavailableError(
                "The code-search tool server stopped responding mid-run. Please try again."
            ) from e

    def call_tools(self, calls: Sequence[Tuple[str, Dict[str, Any]]]) -> List[Any]:
        """Call several tools inside ONE connection and ONE event loop.

        The calls run concurrently (``asyncio.gather``) and the results come
        back in the same order as ``calls``. A per-call failure is captured
        as that call's ``{"error": ...}`` result so one bad call does not
        sink the rest of the iteration; connection-level failures (server
        down, whole-batch timeout) still raise ``McpUnavailableError`` so the
        run fails fast with the right reason.
        """
        if not calls:
            return []
        try:
            return asyncio.run(
                asyncio.wait_for(
                    self._call_tools(calls),
                    # One connection plus one round of parallel calls.
                    MCP_CONNECT_TIMEOUT_S + 2 * MCP_CALL_TIMEOUT_S,
                )
            )
        except (asyncio.TimeoutError, OSError, httpx2.HTTPError) as e:
            raise McpUnavailableError(
                "The code-search tool server stopped responding mid-run. Please try again."
            ) from e

    # -- async internals ----------------------------------------------------

    @asynccontextmanager
    async def _session(self):
        headers = {}
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        # The HTTP client carries the Authorization header on every request
        # (and closes cleanly with the session-termination DELETE). A short
        # connect timeout means a down server is reported in seconds, not
        # after the backend's whole agent budget is spent retrying.
        http_client = httpx2.AsyncClient(
            headers=headers,
            timeout=httpx2.Timeout(MCP_CALL_TIMEOUT_S, connect=MCP_CONNECT_TIMEOUT_S),
        )
        try:
            async with Client(
                streamable_http_client(self.url, http_client=http_client)
            ) as client:
                yield client
        finally:
            await http_client.aclose()

    async def _list_tools(self) -> List[Dict[str, Any]]:
        async with self._session() as client:
            page = await client.list_tools()
            return [
                {
                    "name": tool.name,
                    "description": tool.description or "",
                    "input_schema": tool.input_schema,
                    "read_only": bool(
                        tool.annotations and tool.annotations.read_only_hint
                    ),
                }
                for tool in page.tools
            ]

    async def _call_tool(self, name: str, arguments: Dict[str, Any]) -> Any:
        async with self._session() as client:
            result = await client.call_tool(name, arguments)
        return _result_payload(result)

    async def _call_tools(self, calls: Sequence[Tuple[str, Dict[str, Any]]]) -> List[Any]:
        async with self._session() as client:
            return list(
                await asyncio.gather(
                    *(
                        self._call_one(client, name, arguments)
                        for name, arguments in calls
                    )
                )
            )

    async def _call_one(self, client, name: str, arguments: Dict[str, Any]) -> Any:
        """One call inside a shared session; failures become that call's result."""
        try:
            result = await asyncio.wait_for(
                client.call_tool(name, arguments), MCP_CALL_TIMEOUT_S)
        except asyncio.TimeoutError:
            return {"error": "Tool timed out"}
        except Exception as e:  # captured so the rest of the batch survives
            return {"error": f"Tool execution failed: {e}"}
        return _result_payload(result)


def _result_payload(result) -> Any:
    """Shape one MCP call result as a JSON-serialisable payload."""
    if result.is_error:
        return {"error": _text_of(result) or "tool failed"}
    if result.structured_content is not None:
        return result.structured_content
    text = _text_of(result)
    return {} if text is None else text


def _text_of(result) -> Optional[str]:
    parts = [
        block.text
        for block in result.content or []
        if getattr(block, "text", None) is not None
    ]
    if not parts:
        return None
    return "\n".join(parts)


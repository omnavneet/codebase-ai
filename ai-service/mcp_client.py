"""Synchronous bridge from the agent loop to the MCP tool server.

The MCP SDK's client is async, while the agent runs inside FastAPI's sync
endpoints, so each operation runs its own short event loop: connect over
Streamable HTTP with the shared bearer token, do one call, disconnect. The
per-connection cost is a few localhost round-trips, and the benefit is no
background thread, no shared session state, and automatic recovery when the
MCP server restarts.
"""

import asyncio
import os
from contextlib import asynccontextmanager
from typing import Any, Dict, List, Optional

import httpx2
from mcp import Client
from mcp.client.streamable_http import streamable_http_client

DEFAULT_MCP_URL = "http://127.0.0.1:8001/mcp"


class McpToolClient:
    """Sync wrapper around one MCP server (list tools / call a tool)."""

    def __init__(self, url: Optional[str] = None, token: Optional[str] = None):
        self.url = url or os.getenv("MCP_SERVER_URL", DEFAULT_MCP_URL)
        self.token = token if token is not None else os.getenv("AI_SERVICE_INTERNAL_TOKEN", "")

    # -- public API (synchronous) ------------------------------------------

    def list_tools(self) -> List[Dict[str, Any]]:
        """Discover the server's tools as plain dicts (name/desc/schema/flags)."""
        return asyncio.run(self._list_tools())

    def call_tool(self, name: str, arguments: Dict[str, Any]) -> Any:
        """Call a tool; always returns a JSON-serialisable result.

        Failures come back as ``{"error": ...}`` so the agent loop can feed
        them to the model exactly like any other tool result.
        """
        return asyncio.run(self._call_tool(name, arguments))

    # -- async internals ----------------------------------------------------

    @asynccontextmanager
    async def _session(self):
        headers = {}
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        # The HTTP client carries the Authorization header on every request
        # (and closes cleanly with the session-termination DELETE).
        http_client = httpx2.AsyncClient(headers=headers)
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


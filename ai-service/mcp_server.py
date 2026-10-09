"""MCP server exposing the registry's read-only tools over Streamable HTTP.

Deployed as its own service (see docker-compose), the server hosts exactly the
tools declared in ``tool_registry`` and nothing else. Every request except
``GET /health`` must carry ``Authorization: Bearer <AI_SERVICE_INTERNAL_TOKEN>``
- the same shared service-to-service token the backend already uses for the
ai-service, so no new secret is introduced.

Run it with:

    uvicorn mcp_server:create_app --factory --host 0.0.0.0 --port 8001
"""

import inspect
import json
import logging
import os
import secrets
from typing import Any, Callable, Dict, Iterable, List, Optional

from mcp.server import MCPServer
from mcp.server.mcpserver.exceptions import ToolError
from mcp.server.transport_security import TransportSecuritySettings
from mcp.types import ToolAnnotations
from starlette.responses import JSONResponse

from tool_registry import REGISTRY, ToolInvocationError, ToolRegistry, ToolSpec, _annotation_for

logger = logging.getLogger(__name__)

HEALTH_PATH = "/health"

# Hosts the Streamable HTTP transport answers (DNS-rebinding protection is on
# by default and rejects everything that is not allowlisted with a 421).
# "mcp:*" is this container's name on the compose network.
DEFAULT_ALLOWED_HOSTS = "127.0.0.1:*,localhost:*,[::1]:*,mcp:*"


def build_tool_fn(spec: ToolSpec, tools) -> Callable[..., Any]:
    """Wrap a registry spec as an MCP tool function with a flat input schema.

    The SDK generates the tool's JSON schema from the function signature, so
    the signature is rebuilt field-for-field from the spec's Pydantic input
    model and the return annotation carries the output model (which becomes
    the tool's structured-output schema).
    """

    def handler(**kwargs: Any) -> Any:
        try:
            return spec.invoke(tools, kwargs)
        except ToolInvocationError as exc:
            # Clean, user-facing failure (file not found, traversal blocked):
            # surface it as a tool error the model can read and react to.
            raise ToolError(str(exc)) from exc

    handler.__name__ = spec.name
    handler.__doc__ = spec.description
    parameters = [
        inspect.Parameter(
            name,
            kind=inspect.Parameter.KEYWORD_ONLY,
            default=(
                inspect.Parameter.empty if field.is_required() else field.default
            ),
            annotation=_annotation_for(field),
        )
        for name, field in spec.input_model.model_fields.items()
    ]
    handler.__signature__ = inspect.Signature(
        parameters, return_annotation=spec.output_model
    )
    handler.__annotations__ = {
        **{name: field.annotation for name, field in spec.input_model.model_fields.items()},
        "return": spec.output_model,
    }
    return handler


def build_mcp_server(tools, registry: ToolRegistry = REGISTRY) -> MCPServer:
    """Create the MCP server with every read-level registry tool registered."""
    mcp = MCPServer(
        "codebase-tools",
        instructions=(
            "Read-only tools over one project's source tree. All calls are "
            "scoped to a project_id and cannot leave it."
        ),
    )

    for spec in registry.list():
        if spec.permission != "read":
            # The agent only ever gets read-level tools; anything else in the
            # registry must never be exposed over MCP.
            logger.warning("Skipping MCP tool %s: permission is %r", spec.name, spec.permission)
            continue
        mcp.add_tool(
            build_tool_fn(spec, tools),
            name=spec.name,
            description=spec.description,
            annotations=ToolAnnotations(read_only_hint=True, open_world_hint=False),
        )

    @mcp.custom_route(HEALTH_PATH, methods=["GET"])
    async def health(request) -> JSONResponse:
        # Deliberately unauthenticated: container healthchecks run before any
        # token exists (and the bearer middleware below exempts this path).
        return JSONResponse({"status": "UP", "service": "mcp"})

    return mcp


async def _send_json(send, status: int, payload: Dict[str, Any], extra=()) -> None:
    body = json.dumps(payload).encode("utf-8")
    headers = [
        (b"content-type", b"application/json"),
        (b"content-length", str(len(body)).encode("ascii")),
        *extra,
    ]
    await send({"type": "http.response.start", "status": status, "headers": headers})
    await send({"type": "http.response.body", "body": body})


def _bearer_token(scope: Dict[str, Any]) -> Optional[str]:
    for name, value in scope.get("headers", []):
        if name == b"authorization":
            scheme, _, token = value.decode("latin-1").partition(" ")
            if scheme.lower() == "bearer" and token:
                return token
            return None
    return None


class BearerAuthMiddleware:
    """ASGI gate requiring ``Authorization: Bearer <token>`` on every request.

    A pure ASGI wrapper (not Starlette's BaseHTTPMiddleware) so the MCP SSE
    streams pass through untouched. ``GET /health`` is exempt for probes.
    """

    def __init__(self, app, token: str, exempt_paths: Iterable[str] = (HEALTH_PATH,)):
        self.app = app
        self.token = token
        self.exempt_paths = tuple(exempt_paths)

    async def __call__(self, scope, receive, send) -> None:
        if scope["type"] != "http" or scope.get("path") in self.exempt_paths:
            await self.app(scope, receive, send)
            return

        if not self.token:
            # Same failure mode as the ai-service: refuse rather than run open.
            await _send_json(send, 503, {"detail": "MCP authentication is not configured"})
            return

        provided = _bearer_token(scope)
        if provided is None:
            await _send_json(
                send,
                401,
                {"detail": "Unauthorized"},
                extra=[(b"www-authenticate", b"Bearer")],
            )
            return
        if not secrets.compare_digest(provided, self.token):
            await _send_json(send, 403, {"detail": "Forbidden"})
            return

        await self.app(scope, receive, send)


def create_app(
    tools=None,
    token: Optional[str] = None,
    allowed_hosts: Optional[List[str]] = None,
):
    """Build the full ASGI app: bearer auth in front of the MCP HTTP app.

    Used as the ``uvicorn --factory`` target (reads the environment) and by
    the tests, which inject a fake ``tools`` object, a fixed ``token`` and an
    explicit host allowlist.
    """
    if token is None:
        token = os.getenv("AI_SERVICE_INTERNAL_TOKEN", "")
    if allowed_hosts is None:
        allowed_hosts = [
            host.strip()
            for host in os.getenv("MCP_ALLOWED_HOSTS", DEFAULT_ALLOWED_HOSTS).split(",")
            if host.strip()
        ]
    if tools is None:
        from agent_tools import create_agent_tools_from_env

        tools = create_agent_tools_from_env()

    mcp = build_mcp_server(tools)
    inner = mcp.streamable_http_app(
        transport_security=TransportSecuritySettings(allowed_hosts=allowed_hosts)
    )
    return BearerAuthMiddleware(inner, token)


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(create_app(), host="0.0.0.0", port=8001)



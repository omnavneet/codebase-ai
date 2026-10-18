from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import StreamingResponse
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field
from typing import List, Optional
import json
import os
import logging
import queue
import secrets
import threading
import time
import uuid
from dotenv import load_dotenv
from embedding_service import EmbeddingService
from chat_service import ChatService
from agent import CodebaseAgent
from agent_tools import create_agent_tools_from_env
from explain_service import ExplainService
from debug_service import DebugService
from llm_errors import (
    GENERIC_MESSAGE,
    AgentContextError,
    AgentRateLimitError,
    is_context_length_error,
    is_rate_limit_error,
    retry_after_seconds,
)
from mcp_client import McpToolClient, McpUnavailableError

logger = logging.getLogger(__name__)

# Make the agent's per-iteration timing logs (model_ms / tool_ms with the
# request id) visible in the service output; uvicorn only configures its own
# loggers, so without this the root logger drops INFO lines.
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)

# Load environment variables
load_dotenv()

# Initialize FastAPI
# NOTE: no CORS middleware is configured on purpose. This service is only ever
# called server-to-server by the Spring backend (WebClient), never by a browser;
# if it is ever exposed to one, add an explicit allow-list here.
app = FastAPI(title="Codebase AI Service")

@app.middleware("http")
async def require_internal_token(request: Request, call_next):
    if request.url.path == "/health":
        return await call_next(request)

    expected = os.getenv("AI_SERVICE_INTERNAL_TOKEN", "")
    provided = request.headers.get("X-Internal-Token", "")
    if not expected:
        logger.error("AI_SERVICE_INTERNAL_TOKEN is not configured")
        return JSONResponse(status_code=503, content={"detail": "AI service authentication is not configured"})
    if not provided or not secrets.compare_digest(provided, expected):
        return JSONResponse(status_code=403, content={"detail": "Forbidden"})

    return await call_next(request)

# Initialize services
embedding_service = EmbeddingService()
chat_service = ChatService()

# Project files live wherever the backend put them, so the file tools read them
# through the provider named by APP_STORAGE_PROVIDER (local, the default, or
# s3). Building it once at startup fails fast on a misconfiguration instead of
# on the first agent tool call. The MCP server builds its own copy from the
# same environment (agent_tools.create_agent_tools_from_env), so both
# processes always see identical configuration.
agent_tools = create_agent_tools_from_env(embedding_service)

# The investigation agent still owns its MCP-discovered tool loop for the
# deep-investigate path. Explain and Debug use the fixed pipelines below,
# which read through the in-process agent_tools (no MCP, no agent loop).
agent = CodebaseAgent(chat_service.client, McpToolClient())
explain_service = ExplainService(agent_tools, chat_service.client)
debug_service = DebugService(agent_tools, chat_service.client)

# Request/Response models
class EmbedRequest(BaseModel):
    texts: List[str]

class EmbedResponse(BaseModel):
    embeddings: List[List[float]]

class ChatRequest(BaseModel):
    question: str
    context: List[dict]
    # Prior conversation turns ({"role": "user"|"assistant", "content": str})
    # so follow-up questions have continuity. Optional for compatibility.
    history: List[dict] = Field(default_factory=list)

class ChatResponse(BaseModel):
    answer: str
    citations: List[dict]

@app.get("/health")
async def health():
    return {
        "status": "UP",
        "embedding_model": embedding_service.model_name,
        "embedding_dim": embedding_service.embedding_dim,
        "llm_model": chat_service.model,
    }

# NOTE: embed/chat/investigate call blocking LLM/model code, so they are
# defined as sync endpoints — FastAPI runs those in its threadpool instead
# of blocking the event loop.

@app.post("/embed", response_model=EmbedResponse)
def embed(request: EmbedRequest):
    try:
        embeddings = embedding_service.generate_embeddings(request.texts)
        return EmbedResponse(embeddings=embeddings)
    except Exception as e:
        logger.exception("Embedding request failed")
        raise HTTPException(status_code=502, detail="Embedding service request failed") from e

@app.post("/chat", response_model=ChatResponse)
def chat(request: ChatRequest):
    try:
        answer = chat_service.generate_answer(request.question, request.context, request.history)
        
        citations = [
            {
                "file_path": ctx["file_path"],
                "start_line": ctx["start_line"],
                "end_line": ctx["end_line"]
            }
            for ctx in request.context
        ]
        
        return ChatResponse(answer=answer, citations=citations)
    except Exception as e:
        logger.exception("Chat request failed")
        raise HTTPException(status_code=502, detail="AI chat request failed") from e

@app.post("/chat/stream")
def chat_stream(request: ChatRequest):
    """Stream a RAG answer as Server-Sent Events.

    Emits `token` events carrying content deltas, then a terminal `done` or
    `error` event. Citations are the backend's responsibility (it builds the
    context), so this endpoint never sends a `meta` event.
    """
    def sse_format(event: str, data: dict) -> str:
        return f"event: {event}\ndata: {json.dumps(data)}\n\n"

    def generate():
        try:
            for delta in chat_service.generate_answer_stream(request.question, request.context, request.history):
                yield sse_format("token", {"t": delta})
            yield sse_format("done", {})
        except Exception as exc:
            logger.exception("Chat stream failed")
            yield sse_format("error", _friendly_ai_error(exc))

    return StreamingResponse(generate(), media_type="text/event-stream")

class AgentRequest(BaseModel):
    question: str
    project_id: str

class AgentResponse(BaseModel):
    answer: str
    trace: List[str]
    iterations: int
    tool_calls: int = 0
    files_read: List[str]
    searches_performed: List[str]
    truncated: bool = False
    # Groq token usage summed over the investigation's LLM calls
    # ({"input_tokens": int, "output_tokens": int, "total_tokens": int}).
    usage: dict = Field(default_factory=dict)

@app.post("/agent/investigate", response_model=AgentResponse)
def investigate(request: AgentRequest):
    # Correlates the per-iteration timing logs with this request.
    request_id = uuid.uuid4().hex[:12]
    try:
        result = agent.investigate(
            request.question, request.project_id, request_id=request_id)
        return AgentResponse(**result)
    except Exception as e:
        logger.exception("Agent investigation failed request_id=%s", request_id)
        raise HTTPException(status_code=502, detail="Agent investigation failed") from e


# ---- Streaming agent variants -------------------------------------------
#
# The sync endpoints above are kept for the evaluation harness
# (agent-eval.yml) and any non-streaming client. The `/stream` variants emit
# the SAME step events the agent produces, as Server-Sent Events, so the UI
# can show progress instead of a spinner. Both run the identical bounded loop;
# only the transport differs.

def _sse(event: str, data: dict) -> str:
    return f"event: {event}\ndata: {json.dumps(data)}\n\n"


def _friendly_ai_error(exc: Exception) -> dict:
    """Map a server-side failure to a short user-safe reason plus a code.

    The stream endpoints emit this as the SSE ``error`` event payload; the
    full exception (with its stack trace, the project id and a request id) is
    logged separately. Raw provider bodies, tokens and stack traces never
    reach the user - the generic message is the last resort only.
    """
    if isinstance(exc, McpUnavailableError):
        return {"message": "Code tools unavailable (MCP)", "code": "mcp_unavailable"}
    if isinstance(exc, AgentRateLimitError):
        wait = max(1, int(exc.wait_seconds or 30))
        return {
            "message": "Model rate limited, try again in {} second{}.".format(
                wait, "" if wait == 1 else "s"),
            "code": "rate_limited",
        }
    if isinstance(exc, AgentContextError) or is_context_length_error(exc):
        return {
            "message": "This question touched too much code, try naming a file or folder",
            "code": "context_too_large",
        }
    if is_rate_limit_error(exc):
        raw = retry_after_seconds(exc)
        wait = max(1, int(raw)) if raw else 30
        return {
            "message": "Model rate limited, try again in {} second{}.".format(
                wait, "" if wait == 1 else "s"),
            "code": "rate_limited",
        }
    if isinstance(exc, HTTPException):
        detail = exc.detail if isinstance(exc.detail, str) else GENERIC_MESSAGE
        code = {
            404: "not_found",
            413: "context_too_large",
            429: "rate_limited",
            502: "ai_unreachable",
            503: "ai_unreachable",
        }.get(exc.status_code, "upstream_error")
        return {"message": detail, "code": code}
    if isinstance(exc, (FileNotFoundError, PermissionError)):
        return {"message": "Project files could not be read", "code": "storage_read_failed"}
    if isinstance(exc, ValueError) and "APP_S3" in str(exc):
        return {"message": "Project files could not be read", "code": "storage_read_failed"}
    if isinstance(exc, (ConnectionError, TimeoutError, OSError)):
        return {"message": "AI service unreachable", "code": "ai_unreachable"}
    if type(exc).__name__ in ("APIConnectionError", "APITimeoutError"):
        return {"message": "AI service unreachable", "code": "ai_unreachable"}
    if "mcp server" in str(exc).lower() or "mcp tool server" in str(exc).lower():
        # e.g. RuntimeError("The MCP server exposed no read-only tools")
        return {"message": "Code tools unavailable (MCP)", "code": "mcp_unavailable"}
    return {"message": GENERIC_MESSAGE, "code": "internal"}


def _run_agent_stream(fn, request: Request, request_id: Optional[str] = None):
    """Turn a blocking agent call into an SSE stream of step events.

    ``fn(on_event, cancel_event)`` runs the bounded agent loop on a worker
    thread; every progress dict it emits is forwarded to the client as a
    `step`/`status` event, followed by a terminal `done` (with the full result
    payload) or `error` event. The worker is a daemon so a client disconnect
    can never leak a thread.

    A disconnect-monitor thread checks ``request.is_disconnected()`` and sets
    ``cancel_event``, which is threaded to the agent so a cancelled run really
    stops the AI work instead of just hiding the UI.

    ``request_id`` (optional) lets the caller pick the id used in the logs so
    its own lines correlate with the agent's per-iteration timing lines.
    """
    cancel_event = threading.Event()
    events: "queue.Queue" = queue.Queue()
    result_box: dict = {}
    # Correlates the server-side stack trace with what the client saw.
    request_id = request_id or uuid.uuid4().hex[:12]

    emit = events.put

    def monitor():
        while not request.is_disconnected():
            time.sleep(0.2)
        cancel_event.set()
        logger.info("Client disconnected — signalling cancel to the AI agent")

    def worker():
        try:
            result_box["value"] = fn(emit, cancel_event)
        except Exception as exc:  # surfaced as an SSE error event below
            logger.exception(
                "Streaming agent run failed project_id=%s request_id=%s",
                getattr(request, "project_id", "?"), request_id)
            result_box["error"] = _friendly_ai_error(exc)
        finally:
            events.put(None)  # sentinel: the worker is finished

    def generate():
        threading.Thread(target=worker, daemon=True).start()
        threading.Thread(target=monitor, daemon=True).start()
        while True:
            item = events.get()
            if item is None:
                break
            etype = item.get("type")
            if etype == "answer":
                # The final answer text is delivered in `done` with the whole
                # payload, so don't emit it twice here.
                continue
            yield _sse(etype if etype in ("status", "step") else "step", item)

        if "error" in result_box:
            # Carries both the user-safe message and its machine-readable code.
            yield _sse("error", result_box["error"])
        else:
            payload = result_box.get("value", {})
            yield _sse("done", payload)

    return StreamingResponse(generate(), media_type="text/event-stream")


@app.post("/agent/investigate/stream")
def investigate_stream(request: AgentRequest):
    """Streaming twin of POST /agent/investigate (same loop, step events)."""
    # One id per run, shared with the agent's own per-iteration timing logs
    # so a slow run can be diagnosed from the ai-service logs.
    request_id = uuid.uuid4().hex[:12]

    def run(emit, cancel_event):
        return agent.investigate(
            request.question, request.project_id,
            on_event=emit, cancel_event=cancel_event, request_id=request_id,
        )
    return _run_agent_stream(run, request, request_id=request_id)

class ExplainCodeRequest(BaseModel):
    file_path: str
    project_id: str
    symbol: Optional[str] = None

class DebugRequest(BaseModel):
    issue_description: str
    project_id: str
    stack_trace: Optional[str] = None
    file_path: Optional[str] = None

@app.post("/agent/explain-code", response_model=AgentResponse)
def explain_code(request: ExplainCodeRequest):
    """Explain a file (or symbol) with the fixed pipeline: one model call."""
    try:
        result = explain_service.explain(
            request.file_path, request.project_id, request.symbol)
        return AgentResponse(**result)
    except HTTPException:
        raise
    except Exception as e:
        logger.exception("Code explanation failed")
        raise HTTPException(status_code=502, detail="Code explanation failed") from e


@app.post("/agent/explain-code/stream")
def explain_code_stream(request: ExplainCodeRequest):
    """Streaming twin of POST /agent/explain-code (fixed pipeline, step events)."""
    def run(emit, cancel_event):
        try:
            return explain_service.explain(
                request.file_path, request.project_id, request.symbol,
                on_event=emit, cancel_event=cancel_event)
        except HTTPException as exc:
            # Surface the not-found as an SSE error rather than a raised 404,
            # because the stream has already started (200 OK).
            raise McpUnavailableError(
                exc.detail if isinstance(exc.detail, str)
                else "That file or symbol could not be found.")
    return _run_agent_stream(run, request)

class DebugFinding(BaseModel):
    severity: str
    category: str
    title: str
    lines: str
    description: str
    suggestion: str
    code_before: Optional[str] = None
    code_after: Optional[str] = None


class StackFrame(BaseModel):
    file: Optional[str] = None
    line: Optional[int] = None
    symbol: Optional[str] = None
    language: Optional[str] = None
    raw: Optional[str] = None


class DebugResponse(BaseModel):
    answer: str
    findings: List[DebugFinding]
    trace: List[str]
    iterations: int
    files_read: List[str]
    searches_performed: List[str]
    truncated: bool = False
    frames: List[StackFrame] = Field(default_factory=list)
    project_frames: List[StackFrame] = Field(default_factory=list)
    stack_trace_language: Optional[str] = None
    stack_trace_parsed: bool = False
    stack_trace_notes: List[str] = Field(default_factory=list)


@app.post("/agent/debug", response_model=DebugResponse)
def debug(request: DebugRequest):
    """Debugging pipeline: parse the stack trace, locate the suspect code in the
    project, let the agent investigate, and return structured findings with a
    suggested diff."""
    try:
        return DebugResponse(**debug_service.debug(
            request.issue_description,
            request.project_id,
            stack_trace=request.stack_trace,
            file_path=request.file_path,
        ))
    except HTTPException:
        raise
    except Exception as e:
        logger.exception("Debug request failed")
        raise HTTPException(status_code=502, detail="Debug request failed") from e


@app.post("/agent/debug/stream")
def debug_stream(request: DebugRequest):
    """Streaming twin of POST /agent/debug (same pipeline, step events)."""
    def run(emit, cancel_event):
        return debug_service.debug(
            request.issue_description,
            request.project_id,
            stack_trace=request.stack_trace,
            file_path=request.file_path,
            on_event=emit,
            cancel_event=cancel_event,
        )
    return _run_agent_stream(run, request)


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
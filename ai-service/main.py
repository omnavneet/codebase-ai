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
from dotenv import load_dotenv
from embedding_service import EmbeddingService
from review_service import ReviewService
from chat_service import ChatService
from agent import CodebaseAgent
from agent_tools import create_agent_tools_from_env
from debug_service import DebugService
from mcp_client import McpToolClient, McpUnavailableError

logger = logging.getLogger(__name__)

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

# The investigation agent no longer holds a tool list of its own: it discovers
# the read-only tools from the MCP tool server on first use and executes every
# call through it, always scoped to the project it was asked about.
agent = CodebaseAgent(chat_service.client, McpToolClient())
review_service = ReviewService(agent_tools, chat_service.client)
debug_service = DebugService(agent, agent_tools, chat_service.client)

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
        except Exception:
            logger.exception("Chat stream failed")
            yield sse_format("error", {"message": "AI chat request failed"})

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
    try:
        result = agent.investigate(request.question, request.project_id)
        return AgentResponse(**result)
    except Exception as e:
        logger.exception("Agent investigation failed")
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


def _friendly_ai_error(exc: Exception) -> str:
    """Map a server-side failure to a message safe to show a user."""
    if isinstance(exc, McpUnavailableError):
        return str(exc)
    if isinstance(exc, HTTPException):
        return exc.detail if isinstance(exc.detail, str) else "That request could not be completed."
    return "The AI service could not complete this. Please try again."


def _run_agent_stream(fn, request: Request):
    """Turn a blocking agent call into an SSE stream of step events.

    ``fn(on_event, cancel_event)`` runs the bounded agent loop on a worker
    thread; every progress dict it emits is forwarded to the client as a
    `step`/`status` event, followed by a terminal `done` (with the full result
    payload) or `error` event. The worker is a daemon so a client disconnect
    can never leak a thread.

    A disconnect-monitor thread checks ``request.is_disconnected()`` and sets
    ``cancel_event``, which is threaded to the agent so a cancelled run really
    stops the AI work instead of just hiding the UI.
    """
    cancel_event = threading.Event()
    events: "queue.Queue" = queue.Queue()
    result_box: dict = {}

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
            logger.exception("Streaming agent run failed")
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
            yield _sse("error", {"message": result_box["error"]})
        else:
            payload = result_box.get("value", {})
            yield _sse("done", payload)

    return StreamingResponse(generate(), media_type="text/event-stream")


@app.post("/agent/investigate/stream")
def investigate_stream(request: AgentRequest):
    """Streaming twin of POST /agent/investigate (same loop, step events)."""
    def run(emit, cancel_event):
        return agent.investigate(
            request.question, request.project_id, on_event=emit, cancel_event=cancel_event
        )
    return _run_agent_stream(run, request)

class GenerateDocsRequest(BaseModel):
    file_path: str
    project_id: str
    symbol: Optional[str] = None  # function/class name if specific

class GenerateDocsResponse(BaseModel):
    file_path: str
    documentation: str
    language: str
    symbol: Optional[str] = None

def _generate_docs(request: "GenerateDocsRequest", emit=None) -> dict:
    """Shared doc-generation body for the sync and streaming endpoints."""
    def status(message: str) -> None:
        if emit is not None:
            try:
                emit({"type": "status", "message": message})
            except Exception:
                pass

    file_content = agent_tools.read_file(request.file_path, request.project_id)
    if "error" in file_content:
        raise HTTPException(status_code=404, detail=file_content["error"])

    language = detect_language(request.file_path)
    doc_format = get_doc_format(language)

    if request.symbol:
        prompt = f"""Generate {doc_format} documentation for {request.symbol} in this file:

File: {request.file_path}
Language: {language}

Code:
{file_content['content'][:5000]}

Generate appropriate documentation based on the actual implementation.
Include:
- Description of what it does
- Parameters/inputs
- Return values/outputs
- Exceptions/errors
- Important notes

Format as {doc_format} comments. Return only the documentation."""
    else:
        prompt = f"""Generate {doc_format} documentation for this entire file:

File: {request.file_path}
Language: {language}

Code:
{file_content['content'][:5000]}

Generate appropriate file-level documentation including:
- File purpose
- Main functions/classes
- Dependencies
- Usage example if relevant

Format as {doc_format} comments. Return only the documentation."""

    status("Writing documentation")
    response = chat_service.client.chat.completions.create(
        model=os.getenv("LLM_MODEL"),
        messages=[
            {"role": "system", "content": "You are a documentation expert. Generate clear, concise code documentation."},
            {"role": "user", "content": prompt}
        ],
        temperature=0.3
    )

    return {
        "file_path": request.file_path,
        "documentation": response.choices[0].message.content or "",
        "language": language,
        "symbol": request.symbol,
    }


@app.post("/agent/generate-docs", response_model=GenerateDocsResponse)
def generate_docs(request: GenerateDocsRequest):
    """Generate doc comments (JavaDoc/JSDoc/docstring/...) for a file or symbol."""
    try:
        return GenerateDocsResponse(**_generate_docs(request))
    except HTTPException:
        raise
    except Exception as e:
        logger.exception("Documentation generation failed")
        raise HTTPException(status_code=502, detail="Documentation generation failed") from e


@app.post("/agent/generate-docs/stream")
def generate_docs_stream(request: GenerateDocsRequest):
    """Streaming twin of POST /agent/generate-docs (single LLM call, step events)."""
    def run(emit, cancel_event):
        try:
            return _generate_docs(request, emit)
        except HTTPException as exc:
            raise McpUnavailableError(exc.detail if isinstance(exc.detail, str) else "That file could not be documented.")
    return _run_agent_stream(run, request)

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
    """Explain a file (or symbol) in depth using the investigation agent."""
    try:
        file_content = agent_tools.read_file(request.file_path, request.project_id)
        if "error" in file_content:
            raise HTTPException(status_code=404, detail=file_content["error"])

        symbol_context = None
        if request.symbol:
            symbol_context = agent_tools.analyze_symbol(
                request.symbol,
                request.project_id,
                request.file_path,
            )
            candidates = symbol_context.get("candidates", [])
            matching_candidates = [
                candidate for candidate in candidates
                if candidate.get("file_path") == request.file_path
            ]
            if not symbol_context.get("found") or not matching_candidates:
                raise HTTPException(
                    status_code=404,
                    detail=f"Symbol '{request.symbol}' was not found in {request.file_path}",
                )

        target = f"'{request.symbol}' in {request.file_path}" if request.symbol else request.file_path
        question = f"""Explain {target} in depth.

First read {request.file_path}, then follow its key dependencies to understand the full context.
Cover: what it does, how it works step by step, the key data flow, and how it connects to the rest of the codebase.
Cite specific files and line numbers.

Verified target file content:
{file_content['content'][:8000]}
"""
        if symbol_context:
            question += f"""

Verified symbol-index data for the requested symbol:
{json.dumps(symbol_context, default=str)[:8000]}
"""

        result = agent.investigate(question, request.project_id)
        return AgentResponse(**result)
    except HTTPException:
        raise
    except Exception as e:
        logger.exception("Code explanation failed")
        raise HTTPException(status_code=502, detail="Code explanation failed") from e


def _build_explain_question(request: "ExplainCodeRequest", emit=None) -> str:
    """Shared prep for explain (read file, resolve symbol, build the question).

    Raises HTTPException(404) if the file or symbol can't be found — surfaced
    to the streaming client as an error event.
    """
    def status(message: str) -> None:
        if emit is not None:
            emit({"type": "status", "message": message})

    file_content = agent_tools.read_file(request.file_path, request.project_id)
    if "error" in file_content:
        raise HTTPException(status_code=404, detail=file_content["error"])

    symbol_context = None
    if request.symbol:
        status(f"Locating {request.symbol}")
        symbol_context = agent_tools.analyze_symbol(
            request.symbol, request.project_id, request.file_path,
        )
        candidates = symbol_context.get("candidates", [])
        matching_candidates = [
            candidate for candidate in candidates
            if candidate.get("file_path") == request.file_path
        ]
        if not symbol_context.get("found") or not matching_candidates:
            raise HTTPException(
                status_code=404,
                detail=f"Symbol '{request.symbol}' was not found in {request.file_path}",
            )

    target = f"'{request.symbol}' in {request.file_path}" if request.symbol else request.file_path
    question = f"""Explain {target} in depth.

First read {request.file_path}, then follow its key dependencies to understand the full context.
Cover: what it does, how it works step by step, the key data flow, and how it connects to the rest of the codebase.
Cite specific files and line numbers.

Verified target file content:
{file_content['content'][:8000]}
"""
    if symbol_context:
        question += f"""

Verified symbol-index data for the requested symbol:
{json.dumps(symbol_context, default=str)[:8000]}
"""
    return question


@app.post("/agent/explain-code/stream")
def explain_code_stream(request: ExplainCodeRequest):
    """Streaming twin of POST /agent/explain-code (same loop, step events)."""
    def run(emit, cancel_event):
        try:
            question = _build_explain_question(request, emit)
        except HTTPException as exc:
            # Surface the not-found as an SSE error rather than a raised 404,
            # because the stream has already started (200 OK).
            raise McpUnavailableError(exc.detail if isinstance(exc.detail, str) else "That file or symbol could not be found.")
        return agent.investigate(question, request.project_id, on_event=emit, cancel_event=cancel_event)
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

class ImproveCodeRequest(BaseModel):
    file_path: str
    project_id: str

class ImprovementFinding(BaseModel):
    severity: str
    category: str
    title: str
    lines: str
    description: str
    suggestion: str
    code_before: Optional[str] = None
    code_after: Optional[str] = None

class ImproveCodeResponse(BaseModel):
    file_path: str
    summary: str
    findings: List[ImprovementFinding]

@app.post("/agent/improve-code", response_model=ImproveCodeResponse)
def improve_code(request: ImproveCodeRequest):
    """Review a file for bugs, performance, security and readability issues.

    Uses a deterministic pipeline (read -> dependencies -> semantic search ->
    one LLM call) instead of the agent loop, because the task is bounded to a
    single known file.
    """
    try:
        result = review_service.review_file(request.file_path, request.project_id)
        return ImproveCodeResponse(**result)
    except FileNotFoundError as e:
        raise HTTPException(status_code=404, detail=str(e))
    except Exception as e:
        logger.exception("Code review failed")
        raise HTTPException(status_code=502, detail="Code review failed") from e

def detect_language(file_path: str) -> str:
    """Detect language from file extension"""
    ext = file_path.rsplit(".", 1)[-1].lower() if "." in file_path else ""

    language_map = {
        "java": "java",
        "py": "python",
        "js": "javascript",
        "ts": "typescript",
        "jsx": "javascript",
        "tsx": "typescript",
        "go": "go",
        "rb": "ruby",
        "rs": "rust"
    }

    return language_map.get(ext, "unknown")

def get_doc_format(language: str) -> str:
    """Get documentation comment format for language"""
    format_map = {
        "java": "JavaDoc",
        "python": "docstring",
        "javascript": "JSDoc",
        "typescript": "JSDoc",
        "go": "GoDoc",
        "ruby": "RDoc",
        "rust": "RustDoc"
    }

    return format_map.get(language, "standard comment")


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
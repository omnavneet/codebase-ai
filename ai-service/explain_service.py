"""Fixed Explain pipeline (no agent loop, no MCP).

Predictable task: read the file, gather a little surrounding evidence when a
symbol is named, then write the explanation in ONE model call. The in-process
``agent_tools`` do the reading. Emits real ``status`` step events, enforces a
hard wall-clock timeout (``PIPELINE_TIMEOUT_SECONDS``), and on timeout/cancel
returns the partial text with the existing "stopped early" signal.
"""
import logging
import os
import time
import uuid
from typing import Any, Dict, List, Optional

from fastapi import HTTPException

logger = logging.getLogger(__name__)


def _reasoning_effort() -> Optional[str]:
    """reasoning_effort for these latency-sensitive calls, or None to skip.

    Read from ``LLM_REASONING_EFFORT`` (default "low"); empty disables it. The
    create() wrapper retries without it when the model/SDK rejects it.
    """
    value = os.getenv("LLM_REASONING_EFFORT", "low").strip()
    return value or None


def streaming_model_call(client, model, messages, *, temperature, max_tokens,
                        deadline, cancel_event):
    """One Groq completion, streamed. Returns (text, stopped_early, stop_reason).

    Streaming lets a hard timeout be honoured mid-flight: the loop breaks on
    the deadline or on ``cancel_event`` and returns the text so far.
    ``reasoning_effort`` is set to low when supported, else the call is retried
    without it.
    """
    reasoning = _reasoning_effort()
    stopped_early = False
    stop_reason = ""
    try:
        stream = client.chat.completions.create(
            model=model, messages=messages, temperature=temperature,
            max_tokens=max_tokens, stream=True,
            **({"reasoning_effort": reasoning} if reasoning else {}),
        )
    except TypeError:
        stream = client.chat.completions.create(
            model=model, messages=messages, temperature=temperature,
            max_tokens=max_tokens, stream=True,
        )

    chunks: List[str] = []
    for chunk in stream:
        if cancel_event is not None and cancel_event.is_set():
            stopped_early, stop_reason = True, "cancelled"
            break
        if time.monotonic() > deadline:
            stopped_early, stop_reason = True, "timeout"
            break
        if not chunk.choices:
            continue
        delta = chunk.choices[0].delta
        if delta and delta.content:
            chunks.append(delta.content)
    return "".join(chunks), stopped_early, stop_reason


class ExplainService:
    """Read -> (usages) -> one model call. Deterministic and bounded."""

    MAX_FILE_CHARS = int(os.getenv("EXPLAIN_MAX_FILE_CHARS", "8000"))
    MAX_USAGE_CHARS = int(os.getenv("EXPLAIN_MAX_USAGE_CHARS", "6000"))
    MAX_DEPENDENCY_ITEMS = int(os.getenv("EXPLAIN_MAX_DEPENDENCIES", "20"))
    MAX_TOKENS = int(os.getenv("EXPLAIN_MAX_TOKENS", "1500"))

    def __init__(self, tools, groq_client):
        self.tools = tools
        self.client = groq_client
        self.model = os.getenv("LLM_MODEL")
        self.timeout_seconds = float(os.getenv("PIPELINE_TIMEOUT_SECONDS", "30"))

    def explain(self, file_path, project_id, symbol=None,
                on_event=None, cancel_event=None):
        request_id = uuid.uuid4().hex[:12]
        deadline = time.monotonic() + self.timeout_seconds

        def emit(event: Dict[str, Any]) -> None:
            if on_event is not None:
                try:
                    on_event(event)
                except Exception:
                    pass

        # -- Step 1: read the file -------------------------------------------
        emit({"type": "status", "message": "Reading file"})
        t0 = time.monotonic()
        file_result = self.tools.read_file(file_path, project_id)
        if "error" in file_result:
            raise HTTPException(status_code=404, detail=file_result["error"])
        content = (file_result.get("content") or "")[: self.MAX_FILE_CHARS]
        logger.info("explain read request_id=%s file=%s chars=%d ms=%.0f",
                    request_id, file_path, len(content), (time.monotonic() - t0) * 1000)

        # -- Step 2: gather usages (only when it is cheap/asked) -------------
        searches: List[str] = []
        usage_text = ""
        emit({"type": "status", "message": "Finding usages"})
        t0 = time.monotonic()
        if symbol:
            analysis = self.tools.analyze_symbol(symbol, project_id, file_path)
            usage_text = self._format_symbol(analysis)
            searches.append(f"analyze_symbol:{symbol}")
        else:
            usage_text = self._format_dependencies(
                self.tools.find_dependencies(file_path, project_id))
        logger.info("explain usages request_id=%s ms=%.0f symbol=%s",
                    request_id, (time.monotonic() - t0) * 1000, bool(symbol))

        # -- Step 3: one streamed model call ---------------------------------
        emit({"type": "status", "message": "Writing explanation"})
        target = f"'{symbol}' in {file_path}" if symbol else file_path
        t0 = time.monotonic()
        answer, stopped_early, stop_reason = streaming_model_call(
            self.client, self.model,
            [
                {"role": "system",
                 "content": "You are a senior engineer explaining code clearly and precisely."},
                {"role": "user", "content": self._build_prompt(target, content, usage_text)},
            ],
            temperature=0.3, max_tokens=self.MAX_TOKENS,
            deadline=deadline, cancel_event=cancel_event,
        )
        logger.info("explain model request_id=%s ms=%.0f stopped_early=%s reason=%s",
                    request_id, (time.monotonic() - t0) * 1000, stopped_early, stop_reason)

        trace = [f"Read {file_path}"]
        trace.append(
            f"Analyzed symbol {symbol} (definition + callers)" if symbol
            else f"Read dependencies of {file_path}")
        trace.append("Wrote explanation")

        return {
            "answer": answer,
            "trace": trace,
            "iterations": 1,
            "tool_calls": 0,
            "files_read": [file_path],
            "searches_performed": searches,
            "truncated": stopped_early,
            "stopped_early": stopped_early,
            "stop_reason": stop_reason,
            "usage": {},
        }

    def _format_symbol(self, analysis: Dict[str, Any]) -> str:
        if not analysis.get("found"):
            return ""
        blocks: List[str] = []
        for candidate in analysis.get("candidates", [])[:3]:
            blocks.append("Definition {fp} (L{sl}-{el}):\n{sig}".format(
                fp=candidate.get("file_path"), sl=candidate.get("start_line"),
                el=candidate.get("end_line"),
                sig=(candidate.get("content") or "")[:1500]))
        callers = analysis.get("callers", [])[: self.MAX_DEPENDENCY_ITEMS]
        callees = analysis.get("callees", [])[: self.MAX_DEPENDENCY_ITEMS]
        if callers:
            blocks.append("Called from: " + ", ".join(
                f"{c.get('file_path')}:{c.get('line')}" for c in callers))
        if callees:
            blocks.append("Calls: " + ", ".join(
                f"{c.get('file_path')}:{c.get('line')}" for c in callees))
        return "\n\n".join(blocks)[: self.MAX_USAGE_CHARS]

    def _format_dependencies(self, deps: Dict[str, Any]) -> str:
        imports = (deps.get("imports") or [])[: self.MAX_DEPENDENCY_ITEMS]
        referenced_by = (deps.get("referenced_by") or [])[: self.MAX_DEPENDENCY_ITEMS]
        blocks: List[str] = []
        if imports:
            blocks.append("Imports: " + ", ".join(str(i) for i in imports))
        if referenced_by:
            blocks.append("Referenced by: " + ", ".join(str(r) for r in referenced_by))
        return "\n".join(blocks)[: self.MAX_USAGE_CHARS]

    def _build_prompt(self, target: str, content: str, usage_text: str) -> str:
        usage_block = f"\n\nSurrounding evidence (verified):\n{usage_text}" if usage_text else ""
        return f"""Explain {target} for another engineer who is new to this code.

Cover, in plain language and without padding:
- What the code does
- How it works, step by step
- The key functions/types and their inputs and outputs
- Any gotchas, edge cases or surprises

Rules:
- Cite specific file:line locations from the code you can see.
- If something is uncertain or not visible in the code, say so instead of guessing.
- Do not restate the code line by line.

File content:
```
{content}
```
{usage_block}"""


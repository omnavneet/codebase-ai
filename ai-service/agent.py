import json
import logging
import os
import time
import uuid
from typing import Any, Callable, Dict, List, Optional, Tuple

from groq import Groq

from llm_errors import (
    AgentContextError,
    AgentRateLimitError,
    is_context_length_error,
    is_rate_limit_error,
    retry_after_seconds,
)
from mcp_client import McpUnavailableError
from tool_registry import groq_function_from_mcp

logger = logging.getLogger(__name__)

# Replaces the oldest tool results when the conversation grows past the
# context budget. The model is told nothing; it simply stops paying for
# evidence it already acted on.
EARLIER_RESULT_STUB = "[earlier result omitted]"

# Appended (with NO tools) when the loop hits any bound: the model must answer
# from the evidence it already gathered, so a run always ends with a real
# answer instead of an "out of steps" message.
FINAL_ANSWER_INSTRUCTION = (
    "Answer now using only what you have gathered. State what you could not verify."
)

# When less than this much of the wall-clock budget remains, skip another tool
# round and go straight to the final no-tools answer.
FINAL_ANSWER_MIN_SECONDS = 10.0


class CodebaseAgent:
    """Single-loop LLM agent with native (Groq) tool calling.

    The model decides which tools to call; all of one iteration's calls run
    CONCURRENTLY through a single MCP batch (one connection, one event loop)
    and the results are fed back until the model produces a final answer or a
    bound is hit.

    The tool list is not hardcoded: it is discovered from the MCP tool server
    (``list_tools``), cached for a short TTL, and refreshed on demand when the
    model asks for a tool the cache does not know. Every call is executed
    through the same read-only, project-scoped tool surface.

    The loop is bounded three ways so a run answers in seconds rather than
    minutes: an iteration cap, a tool-call cap, and a wall-clock deadline.
    When any bound is hit — or the remaining budget no longer allows another
    tool round — the model gets one final call WITHOUT tools and must answer
    from what it has gathered, so the run always ends with a real (possibly
    ``truncated``) answer rather than an "out of steps" message. When
    ``on_event`` is supplied it emits ``step``/``status``/``answer`` events as
    work happens so a streaming caller can show progress.
    """

    def __init__(self, groq_client: Groq, mcp_client):
        self.client = groq_client
        self.mcp = mcp_client
        self.model = os.getenv("LLM_MODEL")
        # Speed-tuned defaults: deep investigate should answer in ~15-40s, so
        # the loop gets at most 4 model calls / 8 tool calls instead of the
        # old 10/10 serial marathon.
        self.max_iterations = int(os.getenv("AGENT_MAX_ITERATIONS", "4"))
        self.max_tool_calls = int(os.getenv("AGENT_MAX_TOOL_CALLS", "8"))
        # Hard wall-clock budget for one investigation. Kept well under the
        # backend's 300s relay timeout so the final answer can still be
        # generated and streamed back before the backend gives up.
        self.run_timeout_seconds = int(os.getenv("AGENT_RUN_TIMEOUT_SECONDS", "60"))
        # Approximate budget for one running conversation (tokens ~ chars/4).
        # When the conversation grows past it, the oldest tool results are
        # stubbed; a context-length rejection halves it once before giving up.
        self.context_budget_chars = int(os.getenv("AGENT_CONTEXT_BUDGET_CHARS", "60000"))
        # Per-run retry flags: the agent instance is shared across requests,
        # so investigate() resets them at the start of every run.
        self._context_trimmed = False
        self._rate_limit_retries = 0
        # Discovered lazily from the MCP server so the service can start even
        # if the MCP server is still booting; cached for a short TTL so the
        # list_tools connection cost is paid once per window, not per run.
        self.tool_definitions: Optional[List[Dict]] = None
        self.tool_definitions_at = 0.0
        self.tool_cache_ttl_seconds = float(
            os.getenv("AGENT_TOOL_CACHE_TTL_SECONDS", "300"))

    def discover_tools(self) -> List[Dict]:
        """Fetch the MCP server's read-only tools as Groq function schemas."""
        discovered = self.mcp.list_tools()
        definitions = [
            groq_function_from_mcp(tool["name"], tool["description"], tool["input_schema"])
            for tool in discovered
            if tool.get("read_only")
        ]
        if not definitions:
            raise RuntimeError("The MCP server exposed no read-only tools")
        return definitions

    def _fresh_tool_definitions(self, force: bool = False) -> List[Dict]:
        """Cached tool schemas, refreshed after the TTL (or on demand).

        ``list_tools`` opens an MCP connection, so the result is cached in the
        agent process instead of being re-fetched on every run. ``force`` is
        the manual refresh used when a call references an unknown tool: the
        server may have gained tools since the cached listing was taken.
        """
        cached = self.tool_definitions
        if (
            not force
            and cached
            and self.tool_definitions_at
            and time.monotonic() - self.tool_definitions_at < self.tool_cache_ttl_seconds
        ):
            return cached
        self.tool_definitions = self.discover_tools()
        self.tool_definitions_at = time.monotonic()
        return self.tool_definitions

    def _known_tools(self) -> set:
        """Names of the tools currently exposed to the model."""
        return {
            definition["function"]["name"]
            for definition in (self.tool_definitions or [])
        }

    def investigate(
        self,
        question: str,
        project_id: str,
        on_event: Optional[Callable[[Dict[str, Any]], None]] = None,
        cancel_event: Optional[Any] = None,
        request_id: Optional[str] = None,
        instructions: Optional[str] = None,
    ) -> Dict[str, Any]:
        """Main agent investigation loop.

        ``on_event`` (optional) is called with progress dicts as work happens:
        ``{"type": "status", "message": ...}``, ``{"type": "step", ...}`` and a
        final ``{"type": "answer", ...}``. ``cancel_event`` (optional) is a
        ``threading.Event``; when it is set the loop stops early and returns
        whatever it has found so far. ``request_id`` (optional) correlates the
        per-iteration timing logs with the caller's request.
        """

        def emit(event: Dict[str, Any]) -> None:
            if on_event is not None:
                try:
                    on_event(event)
                except Exception:
                    # Progress reporting must never break the investigation.
                    pass

        def cancelled() -> bool:
            return cancel_event is not None and cancel_event.is_set()

        # Retry policy is per run; this agent instance is shared.
        self._context_trimmed = False
        self._rate_limit_retries = 0
        run_id = request_id or uuid.uuid4().hex[:12]

        if self.tool_definitions is None:
            emit({"type": "status", "message": "Discovering available tools"})
        # Cached listing; only opened when the TTL expired (or never fetched).
        self._fresh_tool_definitions()

        emit({"type": "status", "message": "Planning the investigation"})

        messages = [
            {"role": "system", "content": self._system_prompt()},
            {
                "role": "user",
                "content": f"Question: {question}\n\nProject ID: {project_id}\n\nInvestigate the codebase and provide an evidence-based answer.",
            },
        ]

        trace: List[str] = []
        files_read = set()
        searches_done = set()
        iteration = 0
        tool_calls = 0
        stopped_early = False
        stop_reason = ""
        # Groq usage accounting: summed over every completion in the loop so
        # callers (notably the evaluation harness) can see real token spend.
        usage = {"input_tokens": 0, "output_tokens": 0}
        deadline = time.monotonic() + self.run_timeout_seconds

        def attribute_usage(response: Any) -> None:
            response_usage = getattr(response, "usage", None)
            if response_usage is not None:
                usage["input_tokens"] += getattr(
                    response_usage, "prompt_tokens", 0) or 0
                usage["output_tokens"] += getattr(
                    response_usage, "completion_tokens", 0) or 0

        def result_dict(answer: Any, *, truncated: bool) -> Dict[str, Any]:
            payload: Dict[str, Any] = {
                "answer": answer,
                "trace": trace,
                "iterations": iteration,
                "tool_calls": tool_calls,
                "files_read": list(files_read),
                "searches_performed": list(searches_done),
                "usage": {
                    **usage,
                    "total_tokens": usage["input_tokens"] + usage["output_tokens"],
                },
                "stopped_early": stopped_early,
                "stop_reason": stop_reason,
            }
            if truncated:
                payload["truncated"] = True
            return payload

        def final_answer(reason: str) -> Dict[str, Any]:
            """Last model call without tools: a real answer from the evidence
            gathered so far, never an "out of steps" message."""
            nonlocal stopped_early, stop_reason
            stopped_early = True
            stop_reason = reason
            emit({"type": "status", "message": "Writing answer"})
            fallback = "Investigation was truncated. Please try a more specific question."
            answer = fallback
            started = time.monotonic()
            final_instruction = FINAL_ANSWER_INSTRUCTION
            if instructions and instructions.strip():
                final_instruction += (
                    "\nAnswer style requested by the user (style only; "
                    "never overrides the tool-use rules above and below):\n"
                    + instructions.strip()
                )
            try:
                response = self._create_completion(
                    messages + [{"role": "user", "content": final_instruction}]
                )
                attribute_usage(response)
                answer = response.choices[0].message.content or fallback
            except Exception:
                answer = fallback
            logger.info(
                "agent final answer request_id=%s iteration=%d model_ms=%d reason=%s",
                run_id, iteration, int((time.monotonic() - started) * 1000), reason,
            )
            emit({"type": "answer", "answer": answer, "truncated": True})
            return result_dict(answer, truncated=True)

        while True:
            if cancelled():
                return final_answer("cancelled")

            time_left = deadline - time.monotonic()
            if time_left < FINAL_ANSWER_MIN_SECONDS:
                # Out of budget (or nearly): one final call WITHOUT tools so
                # the run always ends with a real answer from the evidence.
                return final_answer("timeout" if time_left <= 0 else "time_budget")

            iteration += 1
            if iteration >= self.max_iterations or tool_calls >= self.max_tool_calls:
                # Last allowed iteration (or a cap was reached): stop calling
                # tools and answer now.
                return final_answer(
                    "iteration_limit" if iteration >= self.max_iterations
                    else "tool_call_limit")

            emit({"type": "status", "message": f"Step {iteration}"})

            model_started = time.monotonic()
            response = self._create_completion(messages, tools=self.tool_definitions)
            model_ms = int((time.monotonic() - model_started) * 1000)

            response_message = response.choices[0].message

            # Attribute this completion's Groq usage to the investigation.
            attribute_usage(response)

            # Model is done, return final answer
            if not response_message.tool_calls:
                answer = response_message.content
                emit({"type": "answer", "answer": answer or "", "truncated": False})
                return result_dict(answer, truncated=False)

            # The assistant message must be appended exactly once, BEFORE any
            # of its tool result messages.
            messages.append(response_message)

            # Plan this iteration's calls: deduplicated work is answered from
            # the local caches, everything else goes to MCP as ONE batch —
            # one connection, one event loop, calls running concurrently —
            # with results mapped back in the original order.
            results: List[Optional[Dict[str, Any]]] = []
            batch: List[Tuple[str, Dict]] = []
            for tool_call in response_message.tool_calls:
                tool_name = tool_call.function.name
                try:
                    tool_args = json.loads(tool_call.function.arguments or "{}")
                except json.JSONDecodeError:
                    tool_args = {}

                if tool_calls >= self.max_tool_calls:
                    results.append(
                        {"error": "Tool-call budget exhausted; do not request more tools."})
                    continue
                if cancelled():
                    return final_answer("cancelled")
                tool_calls += 1
                emit({"type": "status", "message": self._step_label(tool_name, tool_args)})

                # Track what we've done and avoid repeating expensive work.
                if tool_name == "search_code":
                    search_key = tool_args.get("query", "")
                    if search_key in searches_done:
                        results.append({"note": "Already searched this query", "results": []})
                        continue
                    searches_done.add(search_key)
                    trace.append(f"Searched: {search_key}")

                elif tool_name == "read_file":
                    file_key = tool_args.get("file_path", "")
                    if file_key in files_read:
                        results.append({"note": "Already read this file"})
                        continue
                    files_read.add(file_key)
                    trace.append(f"Read: {file_key}")

                elif tool_name == "grep":
                    search_key = "grep:{}:{}".format(
                        tool_args.get("pattern", ""), tool_args.get("file_path", "")
                    )
                    if search_key in searches_done:
                        results.append({"note": "Already ran this search", "matches": []})
                        continue
                    searches_done.add(search_key)
                    trace.append(f"Grepped: {tool_args.get('pattern', '')}")

                results.append(None)  # placeholder: filled from the batch below
                batch.append((tool_name, tool_args))

            tool_ms = 0
            if batch:
                tools_started = time.monotonic()
                batch_results = self._execute_tools(batch, project_id)
                tool_ms = int((time.monotonic() - tools_started) * 1000)
                batch_iter = iter(batch_results)
                results = [
                    item if item is not None else next(batch_iter)
                    for item in results
                ]

            logger.info(
                "agent iteration request_id=%s iteration=%d model_ms=%d "
                "tool_ms=%d tool_calls=%d",
                run_id, iteration, model_ms, tool_ms, len(batch),
            )

            for tool_call, result in zip(response_message.tool_calls, results):
                # Add tool results to messages, in the model's original order.
                messages.append({
                    "role": "tool",
                    "tool_call_id": tool_call.id,
                    "content": json.dumps(result, default=str),
                })
            # Keep the running conversation inside the context budget
            # by stubbing the oldest tool results when it overflows.
            self._trim_to_budget(messages)

    @staticmethod
    def _step_label(tool_name: str, tool_args: Dict) -> str:
        """Short, human-readable action for a progress event.

        Uses the actual tool name plus the one argument that matters (the
        query, the file NAME — never the full argument dict).
        """
        if tool_name == "search_code":
            return f"Searching code for “{tool_args.get('query', '')}”"
        if tool_name == "read_file":
            path = str(tool_args.get("file_path", "") or "").replace("\\", "/")
            return f"Reading {path.rsplit('/', 1)[-1] or path}"
        if tool_name == "grep":
            return f"Grepping for “{tool_args.get('pattern', '')}”"
        return f"Running {tool_name}"

    def _execute_tool(self, tool_name: str, args: Dict, project_id: str) -> Any:
        """Execute a single tool call through the MCP server, scoped to the project."""
        try:
            if tool_name not in self._known_tools():
                # The cached listing may be stale: refresh it once from the
                # MCP server before refusing the call.
                self._fresh_tool_definitions(force=True)
                if tool_name not in self._known_tools():
                    return {"error": f"Unknown tool: {tool_name}"}

            arguments = dict(args or {})
            # The scope comes from the investigation, never from the model:
            # whatever it sent for project_id is overwritten here.
            arguments["project_id"] = project_id
            return self.mcp.call_tool(tool_name, arguments)

        except McpUnavailableError:
            # The tool server is down or timed out: fail the whole run fast
            # with the right reason instead of feeding the failure back to
            # the model as if it were a normal tool result.
            raise
        except Exception as e:
            return {"error": f"Tool execution failed: {e}"}

    def _execute_tools(
        self, calls: List[Tuple[str, Dict]], project_id: str
    ) -> List[Any]:
        """Run one iteration's tool calls as ONE MCP batch, order preserved.

        The calls share a single connection and event loop and run
        concurrently; a per-call failure comes back as that call's
        ``{"error": ...}`` result so one bad call does not sink the rest of
        the iteration. Connection-level failures still raise
        ``McpUnavailableError`` so the run fails fast when MCP is down.
        """
        prepared = []
        for tool_name, args in calls:
            arguments = dict(args or {})
            # The scope comes from the investigation, never from the model:
            # whatever it sent for project_id is overwritten here.
            arguments["project_id"] = project_id
            prepared.append((tool_name, arguments))

        # An unknown tool usually means the cached listing is stale: refresh
        # it once from the MCP server before refusing any of the calls.
        known = self._known_tools()
        if any(tool_name not in known for tool_name, _ in prepared):
            self._fresh_tool_definitions(force=True)
            known = self._known_tools()

        results: List[Any] = [None] * len(prepared)
        runnable: List[Tuple[int, Tuple[str, Dict]]] = []
        for index, (tool_name, arguments) in enumerate(prepared):
            if tool_name in known:
                runnable.append((index, (tool_name, arguments)))
            else:
                results[index] = {"error": f"Unknown tool: {tool_name}"}

        if runnable:
            batch_call = getattr(self.mcp, "call_tools", None)
            if callable(batch_call):
                outcomes = batch_call([item for _, item in runnable])
            else:
                # Simple clients without a batch method (test doubles, older
                # wrappers) run one connection per call, sequentially.
                outcomes = []
                for _, (tool_name, arguments) in runnable:
                    try:
                        outcomes.append(self.mcp.call_tool(tool_name, arguments))
                    except McpUnavailableError:
                        raise
                    except Exception as e:
                        outcomes.append({"error": f"Tool execution failed: {e}"})
            for (index, _), outcome in zip(runnable, outcomes):
                results[index] = outcome
        return results

    # -- context budget and provider retry policy --------------------------

    def _create_completion(self, messages: List[Any], tools: Optional[List[Dict]] = None) -> Any:
        """One model call with the context and rate-limit policies applied.

        - 413 / context-length rejection: halve the budget, stub the oldest
          tool results and retry ONCE; a second rejection raises
          AgentContextError so the caller can answer with the
          "touched too much code" reason.
        - 429: honour Retry-After once when it is under 10 seconds, otherwise
          raise AgentRateLimitError carrying the wait time.
        """
        while True:
            try:
                kwargs: Dict[str, Any] = {
                    "model": self.model,
                    "messages": messages,
                    "temperature": 0.3,
                }
                if tools is not None:
                    kwargs["tools"] = tools
                    kwargs["tool_choice"] = "auto"
                return self.client.chat.completions.create(**kwargs)
            except Exception as exc:
                if is_context_length_error(exc):
                    if self._context_trimmed:
                        raise AgentContextError(
                            "The conversation exceeded the model's context window"
                        ) from exc
                    self._context_trimmed = True
                    self.context_budget_chars = max(4_000, self.context_budget_chars // 2)
                    self._trim_to_budget(messages)
                    continue
                if is_rate_limit_error(exc):
                    wait = retry_after_seconds(exc)
                    if wait is None or wait >= 10 or self._rate_limit_retries >= 1:
                        raise AgentRateLimitError(
                            "The model provider rate-limited this request",
                            wait_seconds=int(wait) if wait else 30,
                        ) from exc
                    self._rate_limit_retries += 1
                    time.sleep(wait)
                    continue
                raise

    @staticmethod
    def _message_content(message: Any) -> str:
        if isinstance(message, dict):
            content = message.get("content")
        else:
            content = getattr(message, "content", None)
        return content if isinstance(content, str) else ""

    def _messages_size(self, messages: List[Any]) -> int:
        return sum(len(self._message_content(message)) for message in messages)

    def _trim_to_budget(self, messages: List[Any]) -> None:
        """Stub the oldest tool results until the conversation fits the budget.

        Only tool results are ever replaced: the system prompt and the
        question must survive so the model can still answer. Already-stubbed
        results are skipped, so the trim always makes progress.
        """
        size = self._messages_size(messages)
        if size <= self.context_budget_chars:
            return
        for message in messages:
            if not (isinstance(message, dict) and message.get("role") == "tool"):
                continue
            content = message.get("content")
            if not isinstance(content, str) or content == EARLIER_RESULT_STUB:
                continue
            size -= len(content) - len(EARLIER_RESULT_STUB)
            message["content"] = EARLIER_RESULT_STUB
            if size <= self.context_budget_chars:
                return

    def _system_prompt(self) -> str:
        """System prompt for an investigation (tool cap injected from config)."""
        return """You are a senior software engineer investigating a codebase.

Rules:
- Start with search_code to locate relevant files; do not list the whole tree
- Request independent tool calls together in the same turn
- Prefer reading specific line ranges over whole files
- Stop and answer as soon as you have enough evidence; do not keep exploring
- Lead with the direct answer, cite file:line, and say what is uncertain
- analyze_symbol returns real call data; when it reports unresolved, ambiguous
  or dynamic references, say so instead of presenting an uncertain edge as fact
- You have a maximum of %d tool calls""" % self.max_tool_calls

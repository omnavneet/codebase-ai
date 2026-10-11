import json
import os
import time
from typing import Any, Callable, Dict, List, Optional

from groq import Groq

from tool_registry import groq_function_from_mcp


class CodebaseAgent:
    """Single-loop LLM agent with native (Groq) tool calling.

    The model decides which tools to call; each result is fed back until the
    model produces a final answer or the bounded iteration limit is reached.

    The tool list is not hardcoded: it is discovered from the MCP tool server
    (``list_tools``) on first use, and every call is executed through the same
    The loop is bounded three ways so a run can never hang forever: an
    iteration cap, a tool-call cap, and a wall-clock deadline. When any bound
    is hit the agent still returns a usable *partial* answer (``truncated``)
    rather than raising, and — when ``on_event`` is supplied — it emits
    ``step``/``status``/``answer`` events as work happens so a streaming
    caller can show progress.
    """

    def __init__(self, groq_client: Groq, mcp_client):
        self.client = groq_client
        self.mcp = mcp_client
        self.model = os.getenv("LLM_MODEL")
        self.max_iterations = int(os.getenv("AGENT_MAX_ITERATIONS", "10"))
        self.max_tool_calls = int(os.getenv("AGENT_MAX_TOOL_CALLS", "10"))
        # Hard wall-clock budget for one investigation. Kept well under the
        # backend's 300s relay timeout so a partial answer can still be
        # generated and streamed back before the backend gives up.
        self.run_timeout_seconds = int(os.getenv("AGENT_RUN_TIMEOUT_SECONDS", "150"))
        # Discovered lazily from the MCP server so the service can start even
        # if the MCP server is still booting; cached for the process lifetime.
        self.tool_definitions: Optional[List[Dict]] = None

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

    def investigate(
        self,
        question: str,
        project_id: str,
        on_event: Optional[Callable[[Dict[str, Any]], None]] = None,
        cancel_event: Optional[Any] = None,
    ) -> Dict[str, Any]:
        """Main agent investigation loop.

        ``on_event`` (optional) is called with progress dicts as work happens:
        ``{"type": "status", "message": ...}``, ``{"type": "step", ...}`` and a
        final ``{"type": "answer", ...}``. ``cancel_event`` (optional) is a
        ``threading.Event``; when it is set the loop stops early and returns
        whatever it has found so far.
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

        if self.tool_definitions is None:
            emit({"type": "status", "message": "Discovering available tools"})
            self.tool_definitions = self.discover_tools()

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

        def partial(stop_message: str) -> Dict[str, Any]:
            """Build a truncated result, summarising what we have so far."""
            answer = self._generate_partial_answer(messages)
            combined = f"{stop_message}\n\n{answer}".strip()
            emit({"type": "answer", "answer": combined, "truncated": True})
            return {
                "answer": combined,
                "trace": trace,
                "iterations": iteration,
                "tool_calls": tool_calls,
                "files_read": list(files_read),
                "searches_performed": list(searches_done),
                "usage": {
                    **usage,
                    "total_tokens": usage["input_tokens"] + usage["output_tokens"],
                },
                "truncated": True,
                "stopped_early": stopped_early,
                "stop_reason": stop_reason,
            }

        while iteration < self.max_iterations:
            if cancelled():
                stopped_early = True
                stop_reason = "cancelled"
                return partial("This investigation was stopped early. Here's what I found before stopping.")

            if time.monotonic() > deadline:
                stopped_early = True
                stop_reason = "timeout"
                return partial(
                    "This investigation was stopped early because it hit the time limit. Here's what I found so far."
                )

            iteration += 1
            emit({"type": "status", "message": f"Thinking (step {iteration} of {self.max_iterations})"})

            response = self.client.chat.completions.create(
                model=self.model,
                messages=messages,
                tools=self.tool_definitions,
                tool_choice="auto",
                temperature=0.3,
            )

            response_message = response.choices[0].message

            # Attribute this completion's Groq usage to the investigation.
            response_usage = getattr(response, "usage", None)
            if response_usage is not None:
                usage["input_tokens"] += getattr(
                    response_usage, "prompt_tokens", 0) or 0
                usage["output_tokens"] += getattr(
                    response_usage, "completion_tokens", 0) or 0

            # Check if model wants to call a tool
            if response_message.tool_calls:
                # The assistant message must be appended exactly once,
                # BEFORE any of its tool result messages.
                messages.append(response_message)

                for tool_call in response_message.tool_calls:
                    tool_name = tool_call.function.name
                    tool_args = json.loads(tool_call.function.arguments)

                    if tool_calls >= self.max_tool_calls:
                        result = {"error": "Tool-call budget exhausted; do not request more tools."}
                    elif cancelled():
                        stopped_early = True
                        stop_reason = "cancelled"
                        return partial("This investigation was stopped early. Here's what I found before stopping.")
                    else:
                        tool_calls += 1
                        result = None

                        # Track what we've done and avoid repeating expensive work.
                        if tool_name == "search_code":
                            search_key = tool_args.get("query", "")
                            emit({"type": "step", "message": f"Searching for “{search_key}”"})
                            if search_key in searches_done:
                                result = {"note": "Already searched this query", "results": []}
                            else:
                                searches_done.add(search_key)
                                trace.append(f"Searched: {search_key}")

                        elif tool_name == "read_file":
                            file_key = tool_args.get("file_path", "")
                            emit({"type": "step", "message": f"Reading {file_key}"})
                            if file_key in files_read:
                                result = {"note": "Already read this file"}
                            else:
                                files_read.add(file_key)
                                trace.append(f"Read: {file_key}")

                        elif tool_name == "grep":
                            search_key = "grep:{}:{}".format(
                                tool_args.get("pattern", ""), tool_args.get("file_path", "")
                            )
                            emit({"type": "step", "message": f"Grepping for “{tool_args.get('pattern', '')}”"})
                            if search_key in searches_done:
                                result = {"note": "Already ran this search", "matches": []}
                            else:
                                searches_done.add(search_key)
                                trace.append(f"Grepped: {tool_args.get('pattern', '')}")

                        else:
                            emit({"type": "step", "message": f"Running {tool_name}"})

                        if result is None:
                            result = self._execute_tool(tool_name, tool_args, project_id)

                    # Add tool result to messages
                    messages.append({
                        "role": "tool",
                        "tool_call_id": tool_call.id,
                        "content": json.dumps(result, default=str),
                    })

            else:
                # Model is done, return final answer
                answer = response_message.content
                emit({"type": "answer", "answer": answer or "", "truncated": False})
                return {
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
                    "stopped_early": False,
                    "stop_reason": "",
                }

        # Iteration cap reached: still hand back a usable partial answer.
        stopped_early = True
        stop_reason = "iteration_limit"
        return partial(
            f"This investigation stopped after {self.max_iterations} steps (the tool-call limit). Here's what I found so far."
        )

    def _execute_tool(self, tool_name: str, args: Dict, project_id: str) -> Any:
        """Execute a tool call through the MCP server, scoped to the project."""
        try:
            known = {
                definition["function"]["name"]
                for definition in (self.tool_definitions or [])
            }
            if tool_name not in known:
                return {"error": f"Unknown tool: {tool_name}"}

            arguments = dict(args or {})
            # The scope comes from the investigation, never from the model:
            # whatever it sent for project_id is overwritten here.
            arguments["project_id"] = project_id
            return self.mcp.call_tool(tool_name, arguments)

        except Exception as e:
            return {"error": f"Tool execution failed: {e}"}

    def _system_prompt(self) -> str:
        """System prompt for an investigation (tool cap injected from config)."""
        return """You are a senior software engineer investigating a codebase.

You have tools to search and read code. Use them to build an evidence-based understanding before answering.

Rules:
- Start broad, then narrow down
- Verify claims by reading actual code
- Follow dependencies when useful
- Don't repeat searches you've already done
- Don't read the same file twice
- Cite specific files and line numbers
- If you can't find something, say so explicitly
- analyze_symbol returns real call data; when it reports unresolved, ambiguous
  or dynamic references, say so instead of presenting an uncertain edge as fact
- You have a maximum of %d tool calls""" % self.max_tool_calls

    def _generate_partial_answer(self, messages: List[Dict]) -> str:
        """Generate a partial answer if max iterations reached."""
        try:
            response = self.client.chat.completions.create(
                model=self.model,
                messages=messages + [{
                    "role": "user",
                    "content": "Summarize what you've found so far.",
                }],
                temperature=0.3,
            )
            return response.choices[0].message.content
        except Exception:
            return "Investigation was truncated. Please try a more specific question."

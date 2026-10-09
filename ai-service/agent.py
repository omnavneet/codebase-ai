import json
import os
from typing import Any, Dict, List, Optional

from groq import Groq

from tool_registry import groq_function_from_mcp


class CodebaseAgent:
    """Single-loop LLM agent with native (Groq) tool calling.

    The model decides which tools to call; each result is fed back until the
    model produces a final answer or the bounded iteration limit is reached.

    The tool list is not hardcoded: it is discovered from the MCP tool server
    (``list_tools``) on first use, and every call is executed through the same
    MCP client with the investigation's project_id injected server-side, so
    the model can never choose which project it reads.
    """

    def __init__(self, groq_client: Groq, mcp_client):
        self.client = groq_client
        self.mcp = mcp_client
        self.model = os.getenv("LLM_MODEL")
        self.max_iterations = 10
        self.max_tool_calls = 10
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

    def investigate(self, question: str, project_id: str) -> Dict[str, Any]:
        """Main agent investigation loop."""
        if self.tool_definitions is None:
            self.tool_definitions = self.discover_tools()

        messages = [
            {
                "role": "system",
                "content": """You are a senior software engineer investigating a codebase.

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
- You have a maximum of 10 tool calls""",
            },
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

        while iteration < self.max_iterations:
            iteration += 1

            response = self.client.chat.completions.create(
                model=self.model,
                messages=messages,
                tools=self.tool_definitions,
                tool_choice="auto",
                temperature=0.3,
            )

            response_message = response.choices[0].message

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
                    else:
                        tool_calls += 1
                        result = None

                        # Track what we've done and avoid repeating expensive work.
                        if tool_name == "search_code":
                            search_key = tool_args.get("query", "")
                            if search_key in searches_done:
                                result = {"note": "Already searched this query", "results": []}
                            else:
                                searches_done.add(search_key)
                                trace.append(f"Searched: {search_key}")

                        elif tool_name == "read_file":
                            file_key = tool_args.get("file_path", "")
                            if file_key in files_read:
                                result = {"note": "Already read this file"}
                            else:
                                files_read.add(file_key)
                                trace.append(f"Read: {file_key}")

                        elif tool_name == "grep":
                            search_key = "grep:{}:{}".format(
                                tool_args.get("pattern", ""), tool_args.get("file_path", "")
                            )
                            if search_key in searches_done:
                                result = {"note": "Already ran this search", "matches": []}
                            else:
                                searches_done.add(search_key)
                                trace.append(f"Grepped: {tool_args.get('pattern', '')}")

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
                return {
                    "answer": response_message.content,
                    "trace": trace,
                    "iterations": iteration,
                    "tool_calls": tool_calls,
                    "files_read": list(files_read),
                    "searches_performed": list(searches_done),
                }

        # Max iterations reached
        return {
            "answer": "I've reached the investigation tool-call limit. The available evidence is listed in the trace.",
            "trace": trace,
            "iterations": iteration,
            "tool_calls": tool_calls,
            "files_read": list(files_read),
            "searches_performed": list(searches_done),
            "truncated": True,
        }

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

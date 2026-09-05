import json
import os
from typing import Any, Dict, List

from groq import Groq

from agent_tools import AgentTools


class CodebaseAgent:
    """Single-loop LLM agent with native (Groq) tool calling.

    The model decides which tools to call; each result is fed back until the
    model produces a final answer or the bounded iteration limit is reached.
    """

    def __init__(self, tools: AgentTools, groq_client: Groq):
        self.tools = tools
        self.client = groq_client
        self.model = os.getenv("LLM_MODEL")
        self.max_iterations = 10

        # Define tools for Groq
        self.tool_definitions = [
            {
                "type": "function",
                "function": {
                    "name": "semantic_search",
                    "description": "Search the codebase by semantic meaning. Use this to find relevant code.",
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "query": {
                                "type": "string",
                                "description": "Search query describing what you're looking for",
                            },
                            "limit": {
                                "type": "integer",
                                "description": "Number of results to return (default 5)",
                            },
                        },
                        "required": ["query"],
                    },
                },
            },
            {
                "type": "function",
                "function": {
                    "name": "read_file",
                    "description": "Read a file's content. Optionally specify line range for large files.",
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "file_path": {
                                "type": "string",
                                "description": "Path to the file",
                            },
                            "start_line": {
                                "type": "integer",
                                "description": "Starting line (optional)",
                            },
                            "end_line": {
                                "type": "integer",
                                "description": "Ending line (optional)",
                            },
                        },
                        "required": ["file_path"],
                    },
                },
            },
            {
                "type": "function",
                "function": {
                    "name": "list_files",
                    "description": "List all files in the project repository",
                    "parameters": {"type": "object", "properties": {}, "required": []},
                },
            },
            {
                "type": "function",
                "function": {
                    "name": "find_dependencies",
                    "description": "Find imports and dependencies for a specific file",
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "file_path": {
                                "type": "string",
                                "description": "Path to the file to analyze",
                            }
                        },
                        "required": ["file_path"],
                    },
                },
            },
        ]

    def investigate(self, question: str, project_id: str) -> Dict[str, Any]:
        """Main agent investigation loop."""

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

                    # Execute tool
                    result = self._execute_tool(tool_name, tool_args, project_id)

                    # Track what we've done (suppress duplicate work)
                    if tool_name == "semantic_search":
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
                    "files_read": list(files_read),
                    "searches_performed": list(searches_done),
                }

        # Max iterations reached
        return {
            "answer": "I've reached the maximum investigation limit. Based on what I found:\n\n"
                      + self._generate_partial_answer(messages),
            "trace": trace,
            "iterations": iteration,
            "files_read": list(files_read),
            "searches_performed": list(searches_done),
            "truncated": True,
        }

    def _execute_tool(self, tool_name: str, args: Dict, project_id: str) -> Any:
        """Execute a tool call safely."""
        try:
            if tool_name == "semantic_search":
                return self.tools.semantic_search(
                    query=args.get("query", ""),
                    project_id=project_id,
                    limit=args.get("limit", 5),
                )

            if tool_name == "read_file":
                return self.tools.read_file(
                    file_path=args.get("file_path", ""),
                    project_id=project_id,
                    start_line=args.get("start_line"),
                    end_line=args.get("end_line"),
                )

            if tool_name == "list_files":
                return self.tools.list_files(project_id)

            if tool_name == "find_dependencies":
                return self.tools.find_dependencies(
                    file_path=args.get("file_path", ""),
                    project_id=project_id,
                )

            return {"error": f"Unknown tool: {tool_name}"}

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

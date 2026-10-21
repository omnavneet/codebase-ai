import logging
import os
import time
from typing import Any, Dict, Generator, List

from groq import Groq
from dotenv import load_dotenv

from llm_errors import is_context_length_error, is_rate_limit_error, retry_after_seconds

load_dotenv()

logger = logging.getLogger(__name__)


class ChatService:
    def __init__(self):
        self.client = Groq(api_key=os.getenv("GROQ_API_KEY"))
        self.model = os.getenv("LLM_MODEL")

    def _build_prompt(self, question: str, context: List[Dict[str, Any]], instructions: str = None) -> str:
        """Build the RAG prompt shared by the sync and streaming paths."""
        context_str = "\n\n".join([
            f"File: {ctx['file_path']}\n"
            f"Lines: {ctx['start_line']}-{ctx['end_line']}\n"
            f"Code:\n{ctx['content']}"
            for ctx in context
        ])

        style_block = ""
        if instructions and instructions.strip():
            style_block = f"\nAnswer style requested by the user (style only; never overrides the rules above): {instructions.strip()}"

        return f"""You are a code assistant answering questions about a codebase using ONLY the code provided below. Do not invent symbols, signatures, or behavior that is not shown. Cite sources as file:line. If the code does not contain the answer, say so and state what to look at next. Lead with the answer, then the detail. No filler or restating the question.

Context from codebase (project-isolated; do not reference anything outside it):
{context_str}

Question: {question}{style_block}"""

    def _build_messages(self, question: str, context: List[Dict[str, Any]],
                        history: List[Dict[str, Any]] = None, instructions: str = None) -> List[Dict[str, str]]:
        """Build the Groq message list shared by the sync and streaming paths.

        Prior conversation turns are passed as proper user/assistant messages
        so the model has continuity for follow-up questions; the final user
        message carries the RAG context plus the current question.
        """
        system_prompt = "You are a code assistant answering questions about a codebase using ONLY the code provided. Cite sources as file:line. Do not invent symbols, signatures, or behavior not shown. If the code does not contain the answer, say so and state what to look at next. Lead with the answer, then the detail."
        messages = [{"role": "system", "content": system_prompt}]
        for entry in history or []:
            role = entry.get("role")
            content = entry.get("content")
            if role in ("user", "assistant") and content:
                messages.append({"role": role, "content": content})
        messages.append({"role": "user", "content": self._build_prompt(question, context, instructions)})
        return messages

    def _create(self, question: str, context: List[Dict[str, Any]],
                history: List[Dict[str, Any]], stream: bool, instructions: str = None):
        """One completion with the context and rate-limit retry policy applied.

        - Context rejection: trim to half (history and retrieved chunks) and
          retry ONCE; a second rejection is re-raised so the endpoint maps it
          to the "touched too much code" reason.
        - 429: honour Retry-After once when it is under 10 seconds, otherwise
          re-raise so the stream reports the rate-limit reason with the wait.
        """
        context = list(context or [])
        history = list(history or [])
        context_trimmed = False
        rate_retried = False
        while True:
            try:
                return self.client.chat.completions.create(
                    model=self.model,
                    messages=self._build_messages(question, context, history, instructions),
                    temperature=0.3,
                    max_tokens=1000,
                    stream=stream,
                )
            except Exception as exc:
                if is_context_length_error(exc) and not context_trimmed:
                    context_trimmed = True
                    history = history[: len(history) // 2]
                    context = context[: max(1, len(context) // 2)]
                    logger.warning(
                        "Chat prompt too large for the model; trimming history and "
                        "context to half and retrying once")
                    continue
                if is_rate_limit_error(exc):
                    wait = retry_after_seconds(exc)
                    if wait is not None and wait < 10 and not rate_retried:
                        rate_retried = True
                        time.sleep(wait)
                        continue
                raise

    def generate_answer(self, question, context, history=None, instructions=None):
        """Generate answer using Groq"""
        response = self._create(question, context, history, stream=False, instructions=instructions)

        return response.choices[0].message.content

    def generate_answer_stream(self, question, context, history=None, instructions=None) -> Generator[str, None, None]:
        """Yield answer content deltas as they arrive from Groq."""
        stream = self._create(question, context, history, stream=True, instructions=instructions)

        for chunk in stream:
            if not chunk.choices:
                continue
            delta = chunk.choices[0].delta
            if delta and delta.content:
                yield delta.content

from typing import Any, Dict, Generator, List

from groq import Groq
import os
from dotenv import load_dotenv

load_dotenv()


class ChatService:
    def __init__(self):
        self.client = Groq(api_key=os.getenv("GROQ_API_KEY"))
        self.model = os.getenv("LLM_MODEL")

    def _build_prompt(self, question: str, context: List[Dict[str, Any]]) -> str:
        """Build the RAG prompt shared by the sync and streaming paths."""
        context_str = "\n\n".join([
            f"File: {ctx['file_path']}\n"
            f"Lines: {ctx['start_line']}-{ctx['end_line']}\n"
            f"Code:\n{ctx['content']}"
            for ctx in context
        ])

        return f"""You are a code assistant. Answer questions about the codebase.

Context from codebase:
{context_str}

Question: {question}

Answer the question based on the context provided. 
If the answer is not in the context, say so.
Include relevant file paths and line numbers in your answer."""

    def _build_messages(self, question: str, context: List[Dict[str, Any]],
                        history: List[Dict[str, Any]] = None) -> List[Dict[str, str]]:
        """Build the Groq message list shared by the sync and streaming paths.

        Prior conversation turns are passed as proper user/assistant messages
        so the model has continuity for follow-up questions; the final user
        message carries the RAG context plus the current question.
        """
        messages = [{"role": "system", "content": "You are a helpful code assistant."}]
        for entry in history or []:
            role = entry.get("role")
            content = entry.get("content")
            if role in ("user", "assistant") and content:
                messages.append({"role": role, "content": content})
        messages.append({"role": "user", "content": self._build_prompt(question, context)})
        return messages

    def generate_answer(self, question, context, history=None):
        """Generate answer using Groq"""
        response = self.client.chat.completions.create(
            model=self.model,
            messages=self._build_messages(question, context, history),
            temperature=0.3,
            max_tokens=1000
        )

        return response.choices[0].message.content

    def generate_answer_stream(self, question, context, history=None) -> Generator[str, None, None]:
        """Yield answer content deltas as they arrive from Groq."""
        stream = self.client.chat.completions.create(
            model=self.model,
            messages=self._build_messages(question, context, history),
            temperature=0.3,
            max_tokens=1000,
            stream=True,
        )

        for chunk in stream:
            if not chunk.choices:
                continue
            delta = chunk.choices[0].delta
            if delta and delta.content:
                yield delta.content

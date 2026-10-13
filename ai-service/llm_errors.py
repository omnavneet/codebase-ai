"""Classification helpers for LLM-provider failures.

Shared by the agent loop, the chat service and the stream endpoints so one
description of "rate limited", "context too long" or "unreachable" is used
everywhere. These helpers never leak provider detail: the stream endpoints
turn failures into a short user-safe message plus a machine-readable code
and log the original exception server-side instead.
"""

import time
from typing import Optional

# Last-resort user message; the real cause is always logged, never shown.
GENERIC_MESSAGE = "The AI service could not complete this. Please try again."

# Markers Groq (and OpenAI-compatible providers) use when the prompt is too long.
_CONTEXT_MARKERS = (
    "context length",
    "context_length",
    "maximum context",
    "max context",
    "reduce the length",
    "too many tokens",
    "prompt is too long",
    "input is too long",
    "string is too long",
    "exceeds the maximum number of tokens",
)


class AgentContextError(RuntimeError):
    """The conversation no longer fits the model's context window."""


class AgentRateLimitError(RuntimeError):
    """The provider rate-limited the run and the wait is too long to honour."""

    def __init__(self, message: str, wait_seconds: int = 30):
        super().__init__(message)
        self.wait_seconds = wait_seconds


def http_status(exc: BaseException) -> Optional[int]:
    """HTTP status behind a provider exception, when there is one."""
    status = getattr(exc, "status_code", None)
    if isinstance(status, int):
        return status
    status = getattr(getattr(exc, "response", None), "status_code", None)
    return status if isinstance(status, int) else None


def retry_after_seconds(exc: BaseException) -> Optional[float]:
    """Retry-After hint from a429, in seconds (None when absent/unparseable)."""
    for headers in (
        getattr(exc, "headers", None),
        getattr(getattr(exc, "response", None), "headers", None),
    ):
        if headers is None:
            continue
        try:
            raw = headers.get("Retry-After")
            if raw is None:
                raw = headers.get("retry-after")
        except Exception:
            continue
        if raw is None:
            continue
        try:
            return max(0.0, float(str(raw).strip()))
        except (TypeError, ValueError):
            return None  # HTTP-date form: callers fall back to their default
    return None


def is_rate_limit_error(exc: BaseException) -> bool:
    """True when the provider rejected the call because of rate limiting."""
    if http_status(exc) == 429 or type(exc).__name__ == "RateLimitError":
        return True
    text = str(exc).lower()
    return "rate limit" in text or "rate_limit" in text or "too many requests" in text


def is_context_length_error(exc: BaseException) -> bool:
    """True when the request was rejected for exceeding the context window."""
    if http_status(exc) == 413 or type(exc).__name__ == "ContextLengthLimitError":
        return True
    text = str(exc).lower()
    return any(marker in text for marker in _CONTEXT_MARKERS)


def short_retry_wait(exc: BaseException) -> Optional[float]:
    """Retry-After in seconds when it is safe to honour (under 10s), else None."""
    wait = retry_after_seconds(exc)
    if wait is None or wait >= 10:
        return None
    return wait

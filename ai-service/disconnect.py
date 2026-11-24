"""Cancel-on-disconnect helper for the streaming agent endpoints."""

import asyncio
import logging
import threading

logger = logging.getLogger(__name__)


async def watch_disconnect(request, cancel_event: threading.Event, poll_seconds: float = 0.2) -> None:
    """Set ``cancel_event`` once the HTTP client has gone away.

    ``request.is_disconnected()`` is a coroutine, so it must be awaited on the
    event loop. Calling it without ``await`` returns a truthy coroutine object.

    Any unexpected error is logged and treated as a disconnect so the worker
    does not keep running with a dead monitor.
    """
    try:
        while not await request.is_disconnected():
            await asyncio.sleep(poll_seconds)
        cancel_event.set()
        logger.info("Client disconnected — signalling cancel to the AI agent")
    except asyncio.CancelledError:
        # Normal shutdown (stream finished or task cancelled by the handler).
        raise
    except Exception as exc:  # pragma: no cover - defensive
        logger.warning("Disconnect monitor failed (%s); signalling cancel", exc)
        cancel_event.set()

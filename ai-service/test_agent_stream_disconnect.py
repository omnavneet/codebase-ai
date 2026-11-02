"""Regression test for ISSUES.md B1: the stream disconnect monitor.

Starlette's ``Request.is_disconnected`` is a coroutine function. Calling it
without ``await`` returns a coroutine object, which is always truthy, so a
monitor written as ``while not request.is_disconnected()`` would cancel the
agent immediately. These tests pin the correct behaviour.
"""

import asyncio
import threading
import time

from disconnect import watch_disconnect


class _FakeRequest:
    """Mimics starlette.Request: ``is_disconnected`` is a coroutine function."""

    def __init__(self, disconnect_after_s: float):
        self._disconnect_at = time.monotonic() + disconnect_after_s

    async def is_disconnected(self) -> bool:
        return time.monotonic() >= self._disconnect_at


def _run(coro):
    return asyncio.run(coro)


def test_cancel_is_not_set_while_client_is_still_connected():
    async def scenario():
        cancel = threading.Event()
        monitor = asyncio.create_task(
            watch_disconnect(_FakeRequest(disconnect_after_s=10), cancel, poll_seconds=0.01))
        await asyncio.sleep(0.2)
        assert not cancel.is_set()
        monitor.cancel()

    _run(scenario())


def test_cancel_is_set_once_client_disconnects():
    async def scenario():
        cancel = threading.Event()
        await asyncio.wait_for(
            watch_disconnect(_FakeRequest(disconnect_after_s=0.2), cancel, poll_seconds=0.01),
            timeout=2)
        assert cancel.is_set()

    _run(scenario())

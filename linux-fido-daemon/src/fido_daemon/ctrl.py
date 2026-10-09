"""Control-plane Unix socket for the daemon (pairing / channel rotation).

One long-running daemon owns the Centrifugo channel, so ``fido-daemon pair``
must talk to it rather than generate a channel itself. This is a second Unix
socket (separate from the CTAP2 socket) restricted to the same user with 0600
permissions. Request/response is one line of JSON per message. Requests are
dispatched to `dispatch` and fail closed: unknown commands, malformed input,
and handler errors all yield ``{"error": ...}``.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import socket
from collections.abc import Awaitable, Callable
from typing import Any

logger = logging.getLogger(__name__)

ErrorMessage = dict[str, str]


class ControlServer:
    def __init__(
        self,
        path: str,
        dispatch: Callable[[dict[str, Any]], Awaitable[dict[str, Any]]],
    ) -> None:
        self._path = path
        self._dispatch = dispatch
        self._server: asyncio.AbstractServer | None = None

    async def start(self) -> None:
        parent = os.path.dirname(self._path)
        os.makedirs(parent, exist_ok=True)
        self._server = await asyncio.start_unix_server(
            self._on_connection, path=self._path
        )
        os.chmod(self._path, 0o600)
        logger.info("listening on control socket %s", self._path)

    async def _on_connection(
        self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter
    ) -> None:
        response: dict[str, Any] = {"error": "invalid_request"}
        try:
            data = await reader.read(4096)
            if data:
                request = json.loads(data.decode("utf-8"))
                if not isinstance(request, dict):
                    raise ValueError("request must be a JSON object")
                response = await self._dispatch(request)
                if not isinstance(response, dict):
                    raise ValueError("dispatch returned a non-object response")
        except Exception:
            logger.exception("control request failed; fail closed")
        payload = json.dumps(response).encode("utf-8") + b"\n"
        writer.write(payload)
        await writer.drain()
        writer.close()
        await writer.wait_closed()

    async def close(self) -> None:
        if self._server is not None:
            self._server.close()
            await self._server.wait_closed()
        try:
            os.unlink(self._path)
        except FileNotFoundError:
            pass


def control_request(socket_path: str, request: dict[str, Any], timeout: float = 5.0) -> dict[str, Any]:
    """Send one control message to the daemon and return its response.

    Synchronous (stdlib ``socket``) so the CLI entry point can call it
    directly. Raises on connect/send errors; a response of ``{"error": ...}``
    is returned as-is for the caller to interpret.
    """
    payload = json.dumps(request).encode("utf-8") + b"\n"
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as sock:
        sock.settimeout(timeout)
        sock.connect(socket_path)
        sock.sendall(payload)
        data = sock.recv(4096)
    if not data:
        raise ValueError("daemon returned an empty control response")
    return json.loads(data.decode("utf-8"))
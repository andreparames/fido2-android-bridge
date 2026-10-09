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
import stat
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
        self._bound = False

    async def start(self) -> None:
        parent = os.path.dirname(self._path)
        os.makedirs(parent, exist_ok=True)
        self._clean_stale_socket()
        # Bind without serving, chmod 0600, then start accepting so there is no
        # window where the socket listens with weaker permissions.
        self._server = await asyncio.start_unix_server(
            self._on_connection, path=self._path, start_serving=False
        )
        os.chmod(self._path, 0o600)
        self._bound = True
        await self._server.start_serving()
        logger.info("listening on control socket %s", self._path)

    def _clean_stale_socket(self) -> None:
        """Unlink a leftover socket from a dead daemon; fail closed otherwise.

        If the path exists and is not a Unix socket — or is one still accepting
        connections by another live daemon — leave it untouched and let the
        bind fail rather than removing someone else's socket.
        """
        try:
            mode = os.stat(self._path).st_mode
        except FileNotFoundError:
            return
        if not stat.S_ISSOCK(mode):
            raise OSError(
                f"control socket path exists and is not a socket: {self._path}"
            )
        probe = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        try:
            probe.settimeout(0.5)
            probe.connect(self._path)
        except (ConnectionRefusedError, FileNotFoundError):
            # Stale socket from a dead daemon: safe to reclaim.
            os.unlink(self._path)
        else:
            raise OSError(
                f"control socket is in use by another daemon: {self._path}"
            )
        finally:
            probe.close()

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
        # Only unlink when this instance actually bound the socket, so a failed
        # start can never remove another daemon's socket.
        if self._bound:
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
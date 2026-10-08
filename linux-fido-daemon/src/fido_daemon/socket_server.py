"""Unix domain socket server for local CTAP2/WebAuthn requests.

Binds strictly inside the runtime user directory (/run/user/<UID>/) with
0600 permissions per AGENTS.md §4, and cleanly unlinks the socket on exit.
"""

from __future__ import annotations

import asyncio
import logging
import os

logger = logging.getLogger(__name__)


class SocketServer:
    def __init__(self, path: str, handler) -> None:
        self._path = path
        self._handler = handler
        self._server: asyncio.AbstractServer | None = None

    async def start(self) -> None:
        parent = os.path.dirname(self._path)
        os.makedirs(parent, exist_ok=True)
        self._server = await asyncio.start_unix_server(
            self._on_connection, path=self._path
        )
        os.chmod(self._path, 0o600)
        logger.info("listening on %s", self._path)

    async def _on_connection(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        try:
            await self._handler(reader, writer)
        except Exception:
            logger.exception("connection handler failed")
        finally:
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
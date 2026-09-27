"""Centrifugo relay client with E2EE sealing/opening.

The relay is an untrusted Centrifugo broker. Both peers subscribe and publish
to the channel ``fidobridge.<channel_id>``; the PROTOCOL.md §3 ``WireMessage``
envelope is the publication payload. Outbound messages are sealed with
AES-256-GCM before publish; inbound publications are opened, aborting (with a
security alert) on any GCM tag failure.

Responses are correlated to requests by the plaintext ``id`` (PROTOCOL.md §4):
`request()` publishes a sealed message and awaits the publication whose opened
plaintext echoes the same ``id``.

Auth: a Centrifugo connection JWT is supplied via ``FIDO2_RELAY_TOKEN``. It is
attached on startup and on every reconnect (via ``get_token``), and is never
logged or leaked into the connection URL.
"""

from __future__ import annotations

import asyncio
import json
import logging
from collections.abc import Callable

import centrifuge
from centrifuge import Client, SubscriptionEventHandler

from fido_daemon.crypto import (
    AesGcmCipher,
    TagMismatchError,
    message_from_json,
    message_to_json,
)

logger = logging.getLogger(__name__)


class _RelaySubscriptionHandler(SubscriptionEventHandler):
    def __init__(self, relay: "RelayClient") -> None:
        self._relay = relay

    async def on_publication(self, ctx: centrifuge.PublicationContext) -> None:
        await self._relay._handle_publication(ctx)


class RelayClient:
    def __init__(
        self,
        url: str,
        channel_id: str,
        cipher: AesGcmCipher,
        token: str = "",
        *,
        client_factory: Callable[[], Client] | None = None,
    ) -> None:
        self._url = url
        self._channel_id = channel_id
        self._cipher = cipher
        self._token = token
        self._client_factory = client_factory or self._build_client
        self._client: Client | None = None
        self._sub: centrifuge.Subscription | None = None
        self._pending: dict[str, asyncio.Future] = {}
        self._pending_wire: dict[str, str] = {}
        self._tampered = False

    @property
    def channel(self) -> str:
        return f"fidobridge.{self._channel_id}"

    @property
    def tampered(self) -> bool:
        """True after a GCM tag failure was detected on an inbound message."""
        return self._tampered

    async def _get_token(self) -> str:
        return self._token

    def _build_client(self) -> Client:
        return Client(
            self._url,
            token=self._token or "",
            get_token=self._get_token,
        )

    async def connect(self) -> None:
        self._client = self._client_factory()
        await self._client.connect()
        logger.info("connected to relay channel %s", self.channel)
        self._sub = self._client.new_subscription(
            self.channel, events=_RelaySubscriptionHandler(self)
        )
        await self._sub.subscribe()

    async def request(self, plaintext: bytes, timeout: float | None = None) -> bytes:
        """Seal + publish `plaintext` and await the response echoing its id."""
        if self._sub is None:
            raise RuntimeError("relay not connected")
        message_id = json.loads(plaintext.decode("utf-8"))["id"]
        future: asyncio.Future = asyncio.get_running_loop().create_future()
        self._pending[message_id] = future
        try:
            message = self._cipher.seal(self._channel_id, plaintext)
            wire_data = message_to_json(message)
            self._pending_wire[message_id] = wire_data
            await self._sub.publish(wire_data)
            if timeout is None:
                return await future
            return await asyncio.wait_for(asyncio.shield(future), timeout=timeout)
        finally:
            self._pending.pop(message_id, None)
            self._pending_wire.pop(message_id, None)

    async def _handle_publication(self, ctx: centrifuge.PublicationContext) -> None:
        raw = ctx.pub.data
        if isinstance(raw, (bytes, bytearray)):
            raw = bytes(raw).decode("utf-8")
        try:
            wire = message_from_json(raw)
            plaintext = self._cipher.open(wire)
            message_id = json.loads(plaintext.decode("utf-8"))["id"]
        except TagMismatchError:
            self._tampered = True
            logger.error(
                "SECURITY ALERT: GCM tag verification failed; dropping message"
            )
            return
        except (ValueError, KeyError, json.JSONDecodeError) as exc:
            logger.warning("dropping malformed relay message: %s", exc)
            return
        future = self._pending.get(message_id)
        if future is None or future.done():
            logger.warning("dropping unsolicited relay message with id %s", message_id)
            return
        # Skip our own echo (Centrifugo publishes back to all subscribers including us)
        if self._pending_wire.get(message_id) == raw:
            logger.debug("skipping own echo (id=%s)", message_id)
            return
        future.set_result(plaintext)

    async def close(self) -> None:
        if self._client is not None:
            await self._client.disconnect()
            self._client = None
            self._sub = None

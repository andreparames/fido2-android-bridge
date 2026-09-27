"""Integration test harness — mocked browser + phone peers.

Provides ``MockPhone`` and ``MockBrowser`` for exercising the full
daemon -> Centrifugo -> phone path in a controlled, single-machine setup.

``MockPhone`` wraps the existing ``StubPhone`` logic but accepts a
config-derived cipher and channel, making it easy to pair with a real
or fake Centrifugo broker via ``client_factory``.

``MockBrowser`` opens the daemon's Unix socket, writes a synthetic
CTAP2 CBOR request, and reads back the CTAP2 response.
"""

from __future__ import annotations

import asyncio
import json
import logging
from dataclasses import dataclass, field
from typing import Any, Callable

import fido2.cbor as cbor

from fido_daemon.config import Config
from fido_daemon.crypto import (
    AesGcmCipher,
    SecretKey,
    message_from_json,
    message_to_json,
)
from fido_daemon.ctap2 import CMD_GET_ASSERTION, CMD_MAKE_CREDENTIAL
from tests.fakes import FakeBroker, _PhoneHandler

logger = logging.getLogger(__name__)


@dataclass
class HarnessConfig:
    """Configuration for the integration harness."""

    socket_path: str
    channel_id: str
    session_key_b64: str
    relay_url: str = "ws://localhost:8000/connection/websocket"
    relay_token: str = ""
    request_timeout: float = 5.0

    @classmethod
    def from_config(cls, config: Config) -> "HarnessConfig":
        return cls(
            socket_path=config.socket_path,
            channel_id=config.channel_id,
            session_key_b64=config.session_key_b64,
            relay_url=config.relay_url,
            relay_token=config.relay_token,
            request_timeout=config.request_timeout,
        )


class MockPhone:
    """A phone peer that subscribes to the Centrifugo channel, decrypts
    incoming requests, calls a responder, and publishes the sealed reply.

    Uses the same cipher and channel derivation as the real daemon, so it
    interoperates with ``RelayClient`` over a real or fake broker.
    """

    def __init__(
        self,
        broker: FakeBroker,
        config: Config | HarnessConfig,
        responder: Callable[[dict], dict | None],
    ) -> None:
        key_bytes = (
            config.session_key_b64
            if isinstance(config.session_key_b64, bytes)
            else __import__("base64").b64decode(config.session_key_b64)
        )
        self._cipher = AesGcmCipher(SecretKey(key_bytes))
        self._channel_id = config.channel_id
        self._channel = f"fidobridge.{config.channel_id}"
        self._broker = broker
        self._responder = responder
        self.client: Any = None
        self.sub: Any = None
        self.received: list[dict] = []

    async def start(self) -> None:
        self.client = self._broker.new_client()
        await self.client.connect()
        self.sub = self.client.new_subscription(
            self._channel, events=_PhoneHandler(self)
        )
        await self.sub.subscribe()

    async def _handle(self, ctx) -> None:
        wire = message_from_json(ctx.pub.data)
        plaintext = self._cipher.open(wire)
        request = json.loads(plaintext.decode("utf-8"))
        self.received.append(request)
        response = self._responder(request)
        if asyncio.iscoroutine(response):
            response = await response
        if response is None:
            return
        reply = json.dumps(response).encode("utf-8")
        sealed = self._cipher.seal(self._channel_id, reply)
        await self.sub.publish(message_to_json(sealed))


class MockBrowser:
    """Opens the daemon's Unix socket, sends a synthetic CTAP2 request,
    and reads back the CTAP2 response."""

    def __init__(self, socket_path: str) -> None:
        self._socket_path = socket_path

    async def send_raw(self, frame: bytes, timeout: float = 5.0) -> bytes:
        reader, writer = await asyncio.open_unix_connection(self._socket_path)
        try:
            writer.write(frame)
            await writer.drain()
            response = b""
            try:
                while True:
                    chunk = await asyncio.wait_for(reader.read(4096), timeout=timeout)
                    if not chunk:
                        break
                    response += chunk
            except (asyncio.TimeoutError, TimeoutError):
                pass
            return response
        finally:
            writer.close()
            await writer.wait_closed()

    async def send_get_assertion(
        self,
        rp_id: str,
        client_data_hash: bytes,
        *,
        allow_credentials: list | None = None,
        options: dict | None = None,
        timeout: float = 5.0,
    ) -> bytes:
        cbor_map: dict = {1: rp_id, 2: client_data_hash}
        if allow_credentials is not None:
            cbor_map[3] = allow_credentials
        if options is not None:
            cbor_map[5] = options
        frame = bytes([CMD_GET_ASSERTION]) + cbor.encode(cbor_map)
        return await self.send_raw(frame, timeout=timeout)

    async def send_make_credential(
        self,
        rp_id: str,
        client_data_hash: bytes,
        user_id: bytes,
        user_name: str = "",
        *,
        pub_key_cred_params: list | None = None,
        exclude_credentials: list | None = None,
        timeout: float = 5.0,
    ) -> bytes:
        cbor_map: dict = {
            1: client_data_hash,
            2: {"id": rp_id, "name": rp_id},
            3: {"id": user_id, "name": user_name, "displayName": user_name},
            4: pub_key_cred_params or [{"alg": -7}],
        }
        if exclude_credentials is not None:
            cbor_map[5] = exclude_credentials
        frame = bytes([CMD_MAKE_CREDENTIAL]) + cbor.encode(cbor_map)
        return await self.send_raw(frame, timeout=timeout)

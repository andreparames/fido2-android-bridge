"""Integration test harness — mocked browser + phone peers.

Provides ``MockPhone`` and ``MockBrowser`` for exercising the full
daemon -> Centrifugo -> phone path in a controlled, single-machine setup.

``MockPhone`` is a Noise IK initiator peer (like ``tests.fakes.NoisePhonePeer``)
that loads the daemon's static key from the daemon ``Config`` so it interoperates
with ``RelayClient`` over a real or fake broker.

``MockBrowser`` opens the daemon's Unix socket, writes a synthetic
CTAP2 CBOR request, and reads back the CTAP2 response.
"""

from __future__ import annotations

import asyncio
import json
import logging
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable

import fido2.cbor as cbor

from fido_daemon.config import Config
from fido_daemon.ctap2 import CMD_GET_ASSERTION, CMD_MAKE_CREDENTIAL
from fido_daemon.noise import StaticKeyStore
from tests.fakes import FakeBroker, NoisePhonePeer

logger = logging.getLogger(__name__)


@dataclass
class HarnessConfig:
    """Configuration for the integration harness."""

    socket_path: str
    channel_id: str
    static_key_path: str
    relay_url: str = "ws://localhost:8000/connection/websocket"
    relay_token: str = ""
    request_timeout: float = 5.0


class MockPhone(NoisePhonePeer):
    """A phone peer that subscribes to the Centrifugo channel, opens the Noise
    handshake, decrypts incoming requests, calls a responder, and publishes the
    encrypted reply.

    Uses the daemon's static key (from config) to authenticate the daemon, so
    it interoperates with ``RelayClient`` over a real or fake broker.
    """

    def __init__(
        self,
        broker: FakeBroker,
        channel_id: str,
        config: Config | HarnessConfig,
        responder: Callable[[dict], dict | None],
    ) -> None:
        daemon_pub = StaticKeyStore.public_key(
            StaticKeyStore.load(Path(config.static_key_path).expanduser())
        )
        super().__init__(broker, channel_id, daemon_pub, responder)


async def pair_via_control(control_socket: str, timeout: float = 5.0) -> str:
    """Pair with the running daemon over its control socket; return the channel hex.

    Mirrors ``cli._run_pair``'s request; used to bootstrap the daemon before a
    phone peer starts in harness/e2e tests.
    """
    import json

    reader, writer = await asyncio.open_unix_connection(control_socket)
    try:
        writer.write(b'{"cmd": "pair"}\n')
        await writer.drain()
        data = await asyncio.wait_for(reader.readline(), timeout)
    finally:
        writer.close()
        await writer.wait_closed()
    response = json.loads(data.decode())
    if "error" in response:
        raise AssertionError(f"pair failed: {response}")
    return response["channel"]


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
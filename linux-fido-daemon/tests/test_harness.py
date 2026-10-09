"""Integration harness tests — mocked browser + phone, real daemon code path.

These tests exercise the full daemon flow (socket -> CTAP2 parse -> Noise seal ->
relay -> open -> response -> socket reply) using MockBrowser and MockPhone
from ``tests.harness``.  The broker is injected (FakeBroker in CI; real
Centrifugo when ``FIDO2_HARNESS=1`` and ``FIDO2_RELAY_URL`` points to one).

Marked ``integration`` so the default ``pytest`` run stays fast.
"""

from __future__ import annotations

import asyncio
import base64
import json
import os

import fido2.cbor as cbor
import pytest

from fido_daemon.cli import _run
from fido_daemon.config import Config
from fido_daemon.ctap2 import CMD_GET_ASSERTION, CMD_MAKE_CREDENTIAL
from fido_daemon.noise import (
    KIND_DATA,
    KIND_IK2,
    StaticKeyStore,
    WireEnvelope,
    envelope_from_json,
    envelope_to_json,
)
from fido_daemon.pairing import derive_channel_id
from tests.fakes import FakeBroker, RELAY_URL
from tests.harness import HarnessConfig, MockBrowser, MockPhone, pair_via_control

DAEMON_PRIVATE = bytes(range(32))
CLIENT_DATA_HASH = b"\x11" * 32

integration = pytest.mark.skipif(
    os.environ.get("FIDO2_HARNESS") != "1",
    reason="integration harness tests require FIDO2_HARNESS=1",
)


def _assertion_responder(request: dict) -> dict:
    auth_data = b"\x00" * 32 + b"\x05" + b"\x00\x00\x00\x00"
    return {
        "version": 3,
        "type": "assertionResult",
        "id": request["id"],
        "payload": {
            "credentialId": base64.b64encode(b"cred-1").decode().rstrip("="),
            "authenticatorData": base64.b64encode(auth_data).decode().rstrip("="),
            "signature": base64.b64encode(b"sig").decode().rstrip("="),
        },
    }


def _make_credential_responder(request: dict) -> dict:
    att_obj = cbor.encode(
        {
            "fmt": "packed",
            "authData": b"\x00" * 32 + b"\x01" + b"\x00\x00\x00\x00",
            "attStmt": {},
        }
    )
    return {
        "version": 3,
        "type": "makeCredentialResult",
        "id": request["id"],
        "payload": {
            "attestationObject": base64.b64encode(att_obj).decode().rstrip("="),
        },
    }


def _config(socket_path: str, tmp_path) -> Config:
    key_path = tmp_path / "static_key.pem"
    StaticKeyStore.save(key_path, DAEMON_PRIVATE)
    return Config(
        socket_path=socket_path,
        control_socket=str(tmp_path / "fido2-ctrl.sock"),
        relay_url=RELAY_URL,
        static_key_path=str(key_path),
        relay_token="",
        request_timeout=5.0,
        uhid_enabled=False,
        uhid_name="fido-daemon",
    )


def _with_timeout(config: Config, timeout: float) -> Config:
    return Config(
        socket_path=config.socket_path,
        control_socket=config.control_socket,
        relay_url=config.relay_url,
        static_key_path=config.static_key_path,
        relay_token=config.relay_token,
        request_timeout=timeout,
        uhid_enabled=config.uhid_enabled,
        uhid_name=config.uhid_name,
    )


async def _start_phone(broker, config, channel_id, responder):
    phone = MockPhone(broker, channel_id, config, responder)
    await phone.start()
    return phone


async def _await_socket(socket_path: str, task) -> None:
    for _ in range(200):
        if os.path.exists(socket_path):
            return
        await asyncio.sleep(0.01)
    task.cancel()
    raise AssertionError(f"socket never appeared: {socket_path}")


async def _await_paired(config: Config, task) -> str:
    """Wait for both sockets, pair via the control socket, return channel_id."""
    await _await_socket(config.socket_path, task)
    if config.control_socket:
        await _await_socket(config.control_socket, task)
        channel_hex = await pair_via_control(config.control_socket)
        return derive_channel_id(channel_hex)
    raise AssertionError("control socket not configured")


@integration
async def test_harness_get_assertion(tmp_path) -> None:
    socket_path = str(tmp_path / "fido2-bridge.sock")
    config = _config(socket_path, tmp_path)
    broker = FakeBroker()

    task = asyncio.create_task(_run(config, client_factory=broker.new_client))
    channel_id = await _await_paired(config, task)

    phone = await _start_phone(broker, config, channel_id, _assertion_responder)

    browser = MockBrowser(socket_path)
    response = await browser.send_get_assertion("example.com", CLIENT_DATA_HASH)

    assert response[0] == 0x00
    decoded = cbor.decode(response[1:])
    assert decoded[1] == {"id": b"cred-1", "type": "public-key"}
    assert decoded[3] == b"sig"
    assert phone.received[0]["payload"]["rpId"] == "example.com"

    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert not os.path.exists(socket_path)


@integration
async def test_harness_make_credential(tmp_path) -> None:
    socket_path = str(tmp_path / "fido2-bridge.sock")
    config = _config(socket_path, tmp_path)
    broker = FakeBroker()

    task = asyncio.create_task(_run(config, client_factory=broker.new_client))
    channel_id = await _await_paired(config, task)

    phone = await _start_phone(broker, config, channel_id, _make_credential_responder)

    browser = MockBrowser(socket_path)
    response = await browser.send_make_credential(
        rp_id="example.com",
        client_data_hash=CLIENT_DATA_HASH,
        user_id=b"user-1",
        user_name="alice@example.com",
    )

    assert response[0] == 0x00
    decoded = cbor.decode(response[1:])
    assert decoded[1] == "packed"
    assert phone.received[0]["payload"]["rpId"] == "example.com"

    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert not os.path.exists(socket_path)


@integration
async def test_harness_timeout(tmp_path) -> None:
    socket_path = str(tmp_path / "fido2-bridge.sock")
    config = _config(socket_path, tmp_path)
    config = _with_timeout(config, 0.3)
    broker = FakeBroker()

    async def _no_responder(request: dict) -> None:
        return None

    task = asyncio.create_task(_run(config, client_factory=broker.new_client))
    channel_id = await _await_paired(config, task)

    await _start_phone(broker, config, channel_id, _no_responder)

    browser = MockBrowser(socket_path)
    response = await browser.send_get_assertion("example.com", CLIENT_DATA_HASH)

    assert response[0] != 0x00

    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert not os.path.exists(socket_path)


@integration
async def test_harness_tamper_detected(tmp_path) -> None:
    socket_path = str(tmp_path / "fido2-bridge.sock")
    config = _config(socket_path, tmp_path)
    config = _with_timeout(config, 0.3)
    broker = FakeBroker()

    class TamperPhone(MockPhone):
        async def _handle(self, ctx) -> None:
            envelope = envelope_from_json(ctx.pub.data)
            if envelope.kind == KIND_IK2:
                self._session.receive_ik2(envelope.payload)
                return
            if envelope.kind != KIND_DATA:
                return
            plaintext = self._session.decrypt(envelope.payload)
            request = json.loads(plaintext.decode("utf-8"))
            self.received.append(request)
            response = self._responder(request)
            if asyncio.iscoroutine(response):
                response = await response
            if response is None:
                return
            ciphertext = self._session.encrypt(json.dumps(response).encode("utf-8"))
            tampered = b"\xff" + ciphertext[1:]
            await self.sub.publish(
                envelope_to_json(WireEnvelope(self._channel_id, KIND_DATA, tampered))
            )

    task = asyncio.create_task(_run(config, client_factory=broker.new_client))
    channel_id = await _await_paired(config, task)

    phone = TamperPhone(broker, channel_id, config, _assertion_responder)
    await phone.start()

    browser = MockBrowser(socket_path)
    response = await browser.send_get_assertion("example.com", CLIENT_DATA_HASH)

    assert response[0] != 0x00

    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert not os.path.exists(socket_path)
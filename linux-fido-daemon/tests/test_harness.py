"""Integration harness tests — mocked browser + phone, real daemon code path.

These tests exercise the full daemon flow (socket -> CTAP2 parse -> seal ->
relay -> open -> response -> socket reply) using MockBrowser and MockPhone
from ``tests.harness``.  The broker is injected (FakeBroker in CI; real
Centrifugo when ``FIDO2_HARNESS=1`` and ``FIDO2_RELAY_URL`` points to one).

Marked ``integration`` so the default ``pytest`` run stays fast.
"""

from __future__ import annotations

import asyncio
import base64
import os

import fido2.cbor as cbor
import pytest

from fido_daemon.cli import _run
from fido_daemon.config import Config
from fido_daemon.ctap2 import CMD_GET_ASSERTION, CMD_MAKE_CREDENTIAL
from tests.fakes import CHANNEL_ID, FakeBroker, RELAY_URL
from tests.harness import HarnessConfig, MockBrowser, MockPhone

KEY = bytes(range(32))
SESSION_KEY_B64 = base64.b64encode(KEY).decode()
CLIENT_DATA_HASH = b"\x11" * 32

integration = pytest.mark.skipif(
    os.environ.get("FIDO2_HARNESS") != "1",
    reason="integration harness tests require FIDO2_HARNESS=1",
)


def _assertion_responder(request: dict) -> dict:
    auth_data = b"\x00" * 32 + b"\x05" + b"\x00\x00\x00\x00"
    return {
        "version": 1,
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
        "version": 1,
        "type": "makeCredentialResult",
        "id": request["id"],
        "payload": {
            "attestationObject": base64.b64encode(att_obj).decode().rstrip("="),
        },
    }


def _config(socket_path: str) -> Config:
    return Config(
        socket_path=socket_path,
        relay_url=RELAY_URL,
        channel_id=CHANNEL_ID,
        session_key_b64=SESSION_KEY_B64,
        relay_token="",
        request_timeout=5.0,
    )


@integration
async def test_harness_get_assertion(tmp_path) -> None:
    socket_path = str(tmp_path / "fido2-bridge.sock")
    config = _config(socket_path)
    broker = FakeBroker()

    phone = MockPhone(broker, config, _assertion_responder)
    await phone.start()

    task = asyncio.create_task(_run(config, client_factory=broker.new_client))

    for _ in range(200):
        if os.path.exists(socket_path):
            break
        await asyncio.sleep(0.01)
    assert os.path.exists(socket_path)

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
    config = _config(socket_path)
    broker = FakeBroker()

    phone = MockPhone(broker, config, _make_credential_responder)
    await phone.start()

    task = asyncio.create_task(_run(config, client_factory=broker.new_client))

    for _ in range(200):
        if os.path.exists(socket_path):
            break
        await asyncio.sleep(0.01)
    assert os.path.exists(socket_path)

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
    config = Config(
        socket_path=socket_path,
        relay_url=RELAY_URL,
        channel_id=CHANNEL_ID,
        session_key_b64=SESSION_KEY_B64,
        relay_token="",
        request_timeout=0.3,
    )
    broker = FakeBroker()

    async def _no_responder(request: dict) -> None:
        return None

    phone = MockPhone(broker, config, _no_responder)
    await phone.start()

    task = asyncio.create_task(_run(config, client_factory=broker.new_client))

    for _ in range(200):
        if os.path.exists(socket_path):
            break
        await asyncio.sleep(0.01)
    assert os.path.exists(socket_path)

    browser = MockBrowser(socket_path)
    response = await browser.send_get_assertion("example.com", CLIENT_DATA_HASH)

    assert response[0] != 0x00

    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert not os.path.exists(socket_path)


@integration
async def test_harness_tamper_detected(tmp_path) -> None:
    from fido_daemon.crypto import AesGcmCipher, SecretKey, b64encode, message_to_json
    from fido_daemon.protocol import PROTOCOL_VERSION

    socket_path = str(tmp_path / "fido2-bridge.sock")
    config = Config(
        socket_path=socket_path,
        relay_url=RELAY_URL,
        channel_id=CHANNEL_ID,
        session_key_b64=SESSION_KEY_B64,
        relay_token="",
        request_timeout=0.3,
    )
    broker = FakeBroker()

    wrong_key = AesGcmCipher(SecretKey(bytes(range(1, 33))))

    class TamperPhone(MockPhone):
        async def _handle(self, ctx) -> None:
            from fido_daemon.crypto import message_from_json

            wire = message_from_json(ctx.pub.data)
            plaintext = self._cipher.open(wire)
            request = __import__("json").loads(plaintext.decode("utf-8"))
            self.received.append(request)

            response = self._responder(request)
            if response is None:
                return
            reply = __import__("json").dumps(response).encode("utf-8")
            sealed = wrong_key.seal(self._channel_id, reply)
            await self.sub.publish(message_to_json(sealed))

    phone = TamperPhone(broker, config, _assertion_responder)
    await phone.start()

    task = asyncio.create_task(_run(config, client_factory=broker.new_client))

    for _ in range(200):
        if os.path.exists(socket_path):
            break
        await asyncio.sleep(0.01)
    assert os.path.exists(socket_path)

    browser = MockBrowser(socket_path)
    response = await browser.send_get_assertion("example.com", CLIENT_DATA_HASH)

    assert response[0] != 0x00

    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert not os.path.exists(socket_path)

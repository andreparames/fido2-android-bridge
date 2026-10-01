import asyncio
import base64
import os

import fido2.cbor as cbor
import pytest

from fido_daemon.cli import _resolve_config, _run, build_parser
from fido_daemon.config import Config
from fido_daemon.ctap2 import CMD_GET_ASSERTION
from fido_daemon.noise import StaticKeyStore
from tests.fakes import CHANNEL_ID, RELAY_URL, NoisePhonePeer

DAEMON_PRIVATE = bytes(range(32))
PHONE_PRIVATE = bytes(range(32, 64))
DAEMON_PUBLIC = StaticKeyStore.public_key(DAEMON_PRIVATE)
CLIENT_DATA_HASH = b"\x11" * 32


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


async def _read_all(reader: asyncio.StreamReader, timeout: float = 2.0) -> bytes:
    data = b""
    while True:
        chunk = await asyncio.wait_for(reader.read(4096), timeout=timeout)
        if not chunk:
            return data
        data += chunk


async def test_e2e_get_assertion(broker, tmp_path) -> None:
    socket_path = str(tmp_path / "fido2-bridge.sock")
    key_path = tmp_path / "static_key.pem"
    StaticKeyStore.save(key_path, DAEMON_PRIVATE)
    config = Config(
        socket_path=socket_path,
        relay_url=RELAY_URL,
        channel_id=CHANNEL_ID,
        static_key_path=str(key_path),
        relay_token="",
        request_timeout=5.0,
        uhid_enabled=False,
        uhid_name="fido-daemon",
    )

    task = asyncio.create_task(_run(config, client_factory=broker.new_client))

    for _ in range(200):
        if os.path.exists(socket_path):
            break
        await asyncio.sleep(0.01)
    assert os.path.exists(socket_path)

    phone = NoisePhonePeer(
        broker, CHANNEL_ID, DAEMON_PUBLIC, _assertion_responder, static_private=PHONE_PRIVATE
    )
    await phone.start()

    reader, writer = await asyncio.open_unix_connection(socket_path)
    frame = bytes([CMD_GET_ASSERTION]) + cbor.encode({1: "example.com", 2: CLIENT_DATA_HASH})
    writer.write(frame)
    await writer.drain()
    response = await _read_all(reader)
    writer.close()
    await writer.wait_closed()

    assert response[0] == 0x00
    decoded = cbor.decode(response[1:])
    assert decoded[1] == {"id": b"cred-1", "type": "public-key"}
    assert decoded[3] == b"sig"
    assert phone.received[0]["payload"]["rpId"] == "example.com"

    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert not os.path.exists(socket_path)


def test_socket_flag_overrides_config(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("FIDO2_CHANNEL_ID", CHANNEL_ID)
    args = build_parser().parse_args(["--socket", "/tmp/custom.sock"])
    config = _resolve_config(args)
    assert config.socket_path == "/tmp/custom.sock"
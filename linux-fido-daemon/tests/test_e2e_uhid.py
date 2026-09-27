"""End-to-end: virtual HID browser -> daemon relay core -> stub phone.

Uses the in-memory FakeBroker + StubPhone (no live Centrifugo), so it runs in
normal CI. Exercises the real CTAP2 -> PROTOCOL.md handler core
(`build_request_handler`) through the full uhid transport.
"""

import asyncio
import base64
import socket

import fido2.cbor as cbor
import pytest

from fido_daemon.cli import build_request_handler
from fido_daemon.config import Config
from fido_daemon.crypto import AesGcmCipher, SecretKey
from fido_daemon.ctap2 import CMD_GET_ASSERTION, CMD_GET_INFO
from fido_daemon.ctaphid import (
    BROADCAST_CID,
    CMD_CBOR,
    CMD_INIT,
    INIT_NONCE_LEN,
    build_init,
    fragment,
    parse_report,
)
from fido_daemon.relay import RelayClient
from fido_daemon.uhid_device import (
    UhidDevice,
    parse_event,
    uhid_output_event,
)
from tests.fakes import CHANNEL_ID, RELAY_URL, StubPhone
from tests.test_uhid_device import _exchange, _read_event, uhid_output_event

KEY = bytes(range(32))
SESSION_KEY_B64 = base64.b64encode(KEY).decode()
CLIENT_DATA_HASH = b"\x11" * 32


async def _read_response(peer: socket.socket, timeout: float = 2.0) -> bytes:
    """Read UHID_INPUT2 reports until one complete CTAPHID response is
    reassembled (a response may span several 64-byte fragments)."""
    reports: list[bytes] = []
    while True:
        event = await asyncio.wait_for(_read_event(peer), timeout)
        parsed = parse_event(event)
        if parsed.input2 is None:
            continue
        reports.append(parsed.input2.data)
        first = parse_report(reports[0])
        total = len(first.data) + sum(
            len(parse_report(r).data) for r in reports[1:] if parse_report(r).cid == first.cid
        )
        if total >= first.bcnt:
            break
    body = bytearray(first.data)
    for report in reports[1:]:
        body.extend(parse_report(report).data)
    return bytes(body[: first.bcnt])


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


@pytest.mark.asyncio
async def test_e2e_uhid_get_assertion(broker) -> None:
    config = Config(
        socket_path="/tmp/irrelevant.sock",
        relay_url=RELAY_URL,
        channel_id=CHANNEL_ID,
        session_key_b64=SESSION_KEY_B64,
        relay_token="",
        request_timeout=5.0,
        uhid_enabled=True,
        uhid_name="test-key",
    )

    cipher = AesGcmCipher(SecretKey(KEY))
    phone = StubPhone(broker, CHANNEL_ID, cipher, _assertion_responder)
    await phone.start()

    relay = RelayClient(
        RELAY_URL, CHANNEL_ID, cipher, token="", client_factory=broker.new_client
    )
    await relay.connect()
    handle = build_request_handler(relay, config)

    left, right = socket.socketpair()
    right.setblocking(False)
    device = UhidDevice(fd=left.fileno(), name="test-key", on_message=handle)
    await device.start()
    try:
        await asyncio.wait_for(_read_event(right), 1.0)  # consume CREATE2

        # Browser: INIT on the broadcast CID -> fresh channel.
        init_events = await _exchange(
            right, build_init(BROADCAST_CID, CMD_INIT, 8, b"01234567")
        )
        cid = int.from_bytes(
            parse_report(parse_event(init_events[0]).input2.data).data[
                INIT_NONCE_LEN : INIT_NONCE_LEN + 4
            ],
            "big",
        )

        # Browser: fragmented authenticatorGetAssertion over the channel.
        request = bytes([CMD_GET_ASSERTION]) + cbor.encode(
            {1: "example.com", 2: CLIENT_DATA_HASH}
        )
        reports = fragment(cid, CMD_CBOR, request)
        for report in reports[:-1]:
            right.sendall(uhid_output_event(report))
        right.sendall(uhid_output_event(reports[-1]))

        # Daemon -> phone -> daemon -> CTAPHID response (possibly fragmented).
        response = await _read_response(right)
        assert response[0] == 0x00  # CTAP2_OK
        decoded = cbor.decode(response[1:])
        assert decoded[1] == {"id": b"cred-1", "type": "public-key"}
        assert decoded[3] == b"sig"
        assert phone.received[0]["payload"]["rpId"] == "example.com"
    finally:
        await device.close()
        await relay.close()
        left.close()
        right.close()


@pytest.mark.asyncio
async def test_e2e_uhid_get_info_served_locally(broker) -> None:
    """authenticatorGetInfo must be answered locally (browsers require it during
    discovery) without touching the relay."""
    config = Config(
        socket_path="/tmp/irrelevant.sock",
        relay_url=RELAY_URL,
        channel_id=CHANNEL_ID,
        session_key_b64=SESSION_KEY_B64,
        relay_token="",
        request_timeout=5.0,
        uhid_enabled=True,
        uhid_name="test-key",
    )

    relay = RelayClient(
        RELAY_URL, CHANNEL_ID, AesGcmCipher(SecretKey(KEY)), token="", client_factory=broker.new_client
    )
    await relay.connect()
    handle = build_request_handler(relay, config)

    left, right = socket.socketpair()
    right.setblocking(False)
    device = UhidDevice(fd=left.fileno(), name="test-key", on_message=handle)
    await device.start()
    try:
        await asyncio.wait_for(_read_event(right), 1.0)  # consume CREATE2

        init_events = await _exchange(
            right, build_init(BROADCAST_CID, CMD_INIT, 8, b"01234567")
        )
        cid = int.from_bytes(
            parse_report(parse_event(init_events[0]).input2.data).data[
                INIT_NONCE_LEN : INIT_NONCE_LEN + 4
            ],
            "big",
        )

        reports = fragment(cid, CMD_CBOR, bytes([CMD_GET_INFO]))
        for report in reports:
            right.sendall(uhid_output_event(report))

        response = await _read_response(right)
        assert response[0] == 0x00  # CTAP2_OK
        info = cbor.decode(response[1:])
        assert "FIDO_2_0" in info[1]
        assert info[4]["up"] is True
        assert info[4]["uv"] is True
        assert info[4]["rk"] is True
        assert info[4]["clientPin"] is False
        assert info[10] == [{"type": "public-key", "alg": -7}]
    finally:
        await device.close()
        await relay.close()
        left.close()
        right.close()
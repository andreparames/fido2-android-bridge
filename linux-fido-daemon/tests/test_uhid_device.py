"""UHID transport tests (plan.md §10). A socketpair stands in for the
kernel's /dev/uhid end so no root is required."""

import asyncio
import socket

import pytest

from fido_daemon.ctaphid import (
    BROADCAST_CID,
    CMD_CBOR,
    CMD_INIT,
    INIT_NONCE_LEN,
    build_init,
    parse_report,
)
from fido_daemon.uhid_device import (
    FIDO_HID_DESCRIPTOR,
    UHID_CREATE2,
    UHID_DESTROY,
    UHID_GET_REPORT_REPLY,
    UHID_EVENT_SIZE,
    UhidDevice,
    create2_event,
    parse_event,
    uhid_get_report_event,
    uhid_output_event,
)


async def _read_event(peer: socket.socket) -> bytes:
    loop = asyncio.get_running_loop()
    buf = b""
    while len(buf) < UHID_EVENT_SIZE:
        chunk = await loop.sock_recv(peer, UHID_EVENT_SIZE - len(buf))
        if not chunk:
            raise EOFError("peer closed")
        buf += chunk
    return buf


async def _exchange(peer: socket.socket, report: bytes, expect: int = 1) -> list[bytes]:
    """Send a CTAPHID report as a UHID_OUTPUT event and read back input events."""
    peer.sendall(uhid_output_event(report))
    events = []
    for _ in range(expect):
        events.append(await asyncio.wait_for(_read_event(peer), 1.0))
    return events


@pytest.fixture
def socket_pair() -> tuple[socket.socket, socket.socket]:
    left, right = socket.socketpair()
    right.setblocking(False)
    yield left, right
    left.close()
    right.close()


# ---------------------------------------------------------------------------
# Event codec
# ---------------------------------------------------------------------------


def test_create2_event_layout() -> None:
    event = create2_event("test-key", FIDO_HID_DESCRIPTOR)
    assert len(event) == UHID_EVENT_SIZE
    parsed = parse_event(event)
    assert parsed.type == UHID_CREATE2
    assert parsed.create2 is not None
    assert parsed.create2.name == b"test-key"
    assert parsed.create2.rd_size == len(FIDO_HID_DESCRIPTOR)
    assert parsed.create2.rd_data == FIDO_HID_DESCRIPTOR
    assert parsed.create2.bus == 3  # USB


def test_fido_hid_descriptor_has_ctap_usage_page() -> None:
    assert FIDO_HID_DESCRIPTOR.startswith(b"\x06\xd0\xf1")  # usage page 0xF1D0
    assert FIDO_HID_DESCRIPTOR.endswith(b"\xc0")  # end collection


# ---------------------------------------------------------------------------
# Device lifecycle
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_start_writes_create2(socket_pair) -> None:
    _, peer = socket_pair
    device = UhidDevice(fd=socket_pair[0].fileno(), name="test-key")
    await device.start()
    try:
        event = await asyncio.wait_for(_read_event(peer), 1.0)
        parsed = parse_event(event)
        assert parsed.type == UHID_CREATE2
        assert parsed.create2.name == b"test-key"
    finally:
        await device.close()


@pytest.mark.asyncio
async def test_close_writes_destroy(socket_pair) -> None:
    _, peer = socket_pair
    device = UhidDevice(fd=socket_pair[0].fileno())
    await device.start()
    await asyncio.wait_for(_read_event(peer), 1.0)  # consume CREATE2
    await device.close()
    event = await asyncio.wait_for(_read_event(peer), 1.0)
    assert parse_event(event).type == UHID_DESTROY


# ---------------------------------------------------------------------------
# Routing
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_output_routes_to_handler_and_returns_input2(socket_pair) -> None:
    async def on_message(payload: bytes) -> bytes:
        return b"\x00" + payload

    _, peer = socket_pair
    device = UhidDevice(
        fd=socket_pair[0].fileno(), name="test-key", on_message=on_message
    )
    await device.start()
    await asyncio.wait_for(_read_event(peer), 1.0)  # consume CREATE2
    try:
        init_events = await _exchange(
            peer, build_init(BROADCAST_CID, CMD_INIT, 8, b"01234567")
        )
        init_report = parse_event(init_events[0]).input2.data
        cid = int.from_bytes(
            parse_report(init_report).data[INIT_NONCE_LEN : INIT_NONCE_LEN + 4], "big"
        )

        request = bytes([0x02]) + b"\xa0"
        events = await _exchange(peer, build_init(cid, CMD_CBOR, len(request), request))
        response_report = parse_event(events[0]).input2.data
        packet = parse_report(response_report)
        assert packet.data == b"\x00" + request
    finally:
        await device.close()


@pytest.mark.asyncio
async def test_get_report_answered_with_zero_report(socket_pair) -> None:
    _, peer = socket_pair
    device = UhidDevice(fd=socket_pair[0].fileno())
    await device.start()
    await asyncio.wait_for(_read_event(peer), 1.0)  # consume CREATE2
    try:
        peer.sendall(uhid_get_report_event(ev_id=42, rnum=0, rtype=0))
        event = await asyncio.wait_for(_read_event(peer), 1.0)
        parsed = parse_event(event)
        assert parsed.type == UHID_GET_REPORT_REPLY
        assert parsed.get_report_reply.id == 42
        assert parsed.get_report_reply.err == 0
        assert parsed.get_report_reply.size == 64
        assert parsed.get_report_reply.data == bytes(64)
    finally:
        await device.close()


@pytest.mark.asyncio
async def test_multipacket_request_through_uhid(socket_pair) -> None:
    async def on_message(payload: bytes) -> bytes:
        return b"\x00" + payload

    _, peer = socket_pair
    device = UhidDevice(
        fd=socket_pair[0].fileno(), name="test-key", on_message=on_message
    )
    await device.start()
    await asyncio.wait_for(_read_event(peer), 1.0)  # consume CREATE2
    try:
        init_events = await _exchange(
            peer, build_init(BROADCAST_CID, CMD_INIT, 8, b"01234567")
        )
        cid = int.from_bytes(
            parse_report(parse_event(init_events[0]).input2.data).data[
                INIT_NONCE_LEN : INIT_NONCE_LEN + 4
            ],
            "big",
        )
        from fido_daemon.ctaphid import fragment

        request = bytes([0x02]) + b"\xa0" * 200
        reports = fragment(cid, CMD_CBOR, request)
        for report in reports[:-1]:
            peer.sendall(uhid_output_event(report))
        events = await _exchange(peer, reports[-1])
        response_report = parse_event(events[0]).input2.data
        packet = parse_report(response_report)
        assert packet.bcnt == len(request) + 1
    finally:
        await device.close()
"""CTAPHID framing + channel state machine tests (plan.md §10)."""

import os

from fido_daemon.ctap2 import CMD_GET_ASSERTION, decode_request_frame
from fido_daemon.ctaphid import (
    BROADCAST_CID,
    CMD_CBOR,
    CMD_INIT,
    CMD_MSG,
    CMD_PING,
    CtapHidError,
    CtapHidSession,
    CidAllocator,
    ERR_INVALID_CHANNEL,
    ERR_INVALID_CMD,
    ERR_INVALID_SEQ,
    ERR_KEEPALIVE_CANCEL,
    INIT_NONCE_LEN,
    REPORT_SIZE,
    build_cont,
    build_error,
    build_init,
    fragment,
    parse_report,
)


def _init_report(cid, cmd, bcnt, data):
    return build_init(cid, cmd, bcnt, data)


def _cont_report(cid, seq, data):
    return build_cont(cid, seq, data)


def _parse_one(reports):
    assert len(reports) == 1
    return parse_report(reports[0])


# ---------------------------------------------------------------------------
# Packet codec
# ---------------------------------------------------------------------------


def test_parse_init_packet_fields() -> None:
    report = _init_report(0x01020304, CMD_INIT, 8, b"\xaa\xbb\xcc\xdd\xee\xff\x00\x11")
    packet = parse_report(report)
    assert packet.cid == 0x01020304
    assert packet.cmd == CMD_INIT
    assert packet.bcnt == 8
    assert packet.data == b"\xaa\xbb\xcc\xdd\xee\xff\x00\x11"


def test_parse_cont_packet_fields() -> None:
    report = _cont_report(0xDEADBEEF, 3, b"\xab\xcd")
    packet = parse_report(report)
    assert packet.cid == 0xDEADBEEF
    assert packet.seq == 3
    assert packet.data.startswith(b"\xab\xcd")
    assert len(packet.data) == 59


def test_init_build_roundtrip() -> None:
    payload = os.urandom(40)
    report = build_init(0x11223344, CMD_CBOR, len(payload), payload)
    assert len(report) == REPORT_SIZE
    packet = parse_report(report)
    assert packet.cid == 0x11223344
    assert packet.cmd == CMD_CBOR
    assert packet.bcnt == len(payload)
    assert packet.data == payload


def test_cont_build_roundtrip() -> None:
    payload = os.urandom(59)
    report = build_cont(0x55667788, 5, payload)
    assert len(report) == REPORT_SIZE
    packet = parse_report(report)
    assert packet.cid == 0x55667788
    assert packet.seq == 5
    assert packet.data == payload


def test_init_packet_sets_init_bit() -> None:
    report = build_init(1, CMD_PING, 0, b"")
    assert report[4] & 0x80


def test_cont_packet_clears_init_bit() -> None:
    report = build_cont(1, 0, b"")
    assert not (report[4] & 0x80)


def test_cont_payload_truncated_to_59_bytes() -> None:
    report = build_cont(1, 0, os.urandom(80))
    packet = parse_report(report)
    assert len(packet.data) == 59


def test_build_error_report() -> None:
    report = build_error(0x0A0B0C0D, ERR_INVALID_CMD)
    packet = parse_report(report)
    assert packet.cid == 0x0A0B0C0D
    assert packet.cmd == 0xBF
    assert packet.bcnt == 1
    assert packet.data == bytes([ERR_INVALID_CMD])


# ---------------------------------------------------------------------------
# CidAllocator
# ---------------------------------------------------------------------------


def test_cid_allocator_never_returns_broadcast_or_zero() -> None:
    allocator = CidAllocator()
    for _ in range(100):
        cid = allocator.alloc()
        assert cid != 0
        assert cid != BROADCAST_CID


def test_cid_allocator_does_not_reuse_active_cid() -> None:
    allocator = CidAllocator()
    first = allocator.alloc()
    assert allocator.alloc() != first
    allocator.release(first)
    assert allocator.alloc() == first


# ---------------------------------------------------------------------------
# Session: INIT / channel allocation
# ---------------------------------------------------------------------------


def test_broadcast_init_allocates_fresh_cid_and_echoes_nonce() -> None:
    session = CtapHidSession()
    nonce = os.urandom(INIT_NONCE_LEN)
    responses = session.handle_report(
        build_init(BROADCAST_CID, CMD_INIT, INIT_NONCE_LEN, nonce)
    )
    packet = _parse_one(responses)
    assert packet.cmd == CMD_INIT
    assert packet.bcnt == INIT_NONCE_LEN + 9
    assert packet.data[:INIT_NONCE_LEN] == nonce
    new_cid = int.from_bytes(packet.data[INIT_NONCE_LEN : INIT_NONCE_LEN + 4], "big")
    assert new_cid != 0 and new_cid != BROADCAST_CID
    assert new_cid in session.channels


def test_init_without_nonce_source_still_allocates() -> None:
    session = CtapHidSession()
    responses = session.handle_report(
        build_init(BROADCAST_CID, CMD_INIT, INIT_NONCE_LEN, os.urandom(INIT_NONCE_LEN))
    )
    packet = _parse_one(responses)
    assert len(packet.data) == INIT_NONCE_LEN + 9


def test_non_broadcast_init_is_rejected() -> None:
    session = CtapHidSession()
    responses = session.handle_report(build_init(0x12345678, CMD_INIT, 0, b""))
    packet = _parse_one(responses)
    assert packet.cmd == 0xBF
    assert packet.data == bytes([ERR_INVALID_CMD])


def test_cont_on_unknown_channel_returns_invalid_channel() -> None:
    session = CtapHidSession()
    responses = session.handle_report(build_cont(0x99999999, 0, b"data"))
    packet = _parse_one(responses)
    assert packet.cmd == 0xBF
    assert packet.data == bytes([ERR_INVALID_CHANNEL])


# ---------------------------------------------------------------------------
# Session: reassembly
# ---------------------------------------------------------------------------


def _established_session() -> tuple[CtapHidSession, int]:
    session = CtapHidSession()
    responses = session.handle_report(build_init(BROADCAST_CID, CMD_INIT, 8, b"01234567"))
    packet = _parse_one(responses)
    cid = int.from_bytes(packet.data[INIT_NONCE_LEN : INIT_NONCE_LEN + 4], "big")
    return session, cid


def test_out_of_sequence_cont_returns_invalid_seq() -> None:
    session, cid = _established_session()
    payload = os.urandom(80)
    session.handle_report(build_init(cid, CMD_CBOR, len(payload), payload[:57]))
    responses = session.handle_report(build_cont(cid, 1, payload[57:]))  # wrong seq (expected 0)
    packet = _parse_one(responses)
    assert packet.cmd == 0xBF
    assert packet.data == bytes([ERR_INVALID_SEQ])


def test_cont_without_init_is_rejected() -> None:
    session, cid = _established_session()
    responses = session.handle_report(build_cont(cid, 0, b"stray"))
    packet = _parse_one(responses)
    assert packet.cmd == 0xBF
    assert packet.data == bytes([ERR_INVALID_SEQ])


def test_multipacket_reassembly_yields_request_frame() -> None:
    session, cid = _established_session()
    payload = os.urandom(200)
    for report in fragment(cid, CMD_CBOR, payload):
        responses = session.handle_report(report)
        assert responses == []
    completed = session.poll_completed()
    assert len(completed) == 1
    got_cid, got_cmd, got_payload = completed[0]
    assert got_cid == cid
    assert got_cmd == CMD_CBOR
    assert got_payload == payload


def test_reassembled_frame_decodes_as_ctap2_request() -> None:
    import fido2.cbor as cbor

    request = bytes([CMD_GET_ASSERTION]) + cbor.encode(
        {1: "example.com", 2: b"\x11" * 32}
    )
    session, cid = _established_session()
    for report in fragment(cid, CMD_CBOR, request):
        session.handle_report(report)
    completed = session.poll_completed()
    message = decode_request_frame(completed[0][2])
    assert message.type == "getAssertion"
    assert message.payload["rpId"] == "example.com"


def test_single_packet_request_completes_immediately() -> None:
    session, cid = _established_session()
    payload = bytes([CMD_GET_ASSERTION]) + b"\xa0"
    session.handle_report(build_init(cid, CMD_CBOR, len(payload), payload))
    completed = session.poll_completed()
    assert len(completed) == 1
    assert completed[0][2] == payload


def test_unsupported_command_on_channel_returns_invalid_cmd() -> None:
    session, cid = _established_session()
    responses = session.handle_report(build_init(cid, 0xFF, 0, b""))
    packet = _parse_one(responses)
    assert packet.cmd == 0xBF
    assert packet.data == bytes([ERR_INVALID_CMD])


def test_cancel_marks_channel_and_returns_keepalive_cancel() -> None:
    session, cid = _established_session()
    session.handle_report(build_init(cid, CMD_CBOR, 100, os.urandom(57)))
    responses = session.handle_report(build_init(cid, 0x91, 0, b""))
    packet = _parse_one(responses)
    assert packet.cmd == 0xBF
    assert packet.data == bytes([ERR_KEEPALIVE_CANCEL])
    assert cid in session.take_closing()


# ---------------------------------------------------------------------------
# Response fragmentation
# ---------------------------------------------------------------------------


def test_short_response_is_single_fragment() -> None:
    payload = os.urandom(10)
    reports = fragment(0x11223344, CMD_CBOR, payload)
    assert len(reports) == 1
    packet = parse_report(reports[0])
    assert packet.cmd == CMD_CBOR
    assert packet.bcnt == len(payload)
    assert packet.data == payload


def test_long_response_reassembles_correctly() -> None:
    cid = 0x11223344
    payload = os.urandom(200)
    reports = fragment(cid, CMD_CBOR, payload)
    assert len(reports) > 1
    first = parse_report(reports[0])
    assert first.bcnt == len(payload)
    body = bytearray(first.data)
    for report in reports[1:]:
        packet = parse_report(report)
        assert packet.cid == cid
        body.extend(packet.data)
    assert bytes(body[: first.bcnt]) == payload


def test_fragment_roundtrip_matches_hand_rolled_report() -> None:
    payload = bytes(range(57))
    single = fragment(1, CMD_MSG, payload)
    assert single == [build_init(1, CMD_MSG, len(payload), payload)]


# ---------------------------------------------------------------------------
# Error path types
# ---------------------------------------------------------------------------


def test_ctaphid_error_has_code() -> None:
    error = CtapHidError(ERR_INVALID_SEQ)
    assert error.code == ERR_INVALID_SEQ
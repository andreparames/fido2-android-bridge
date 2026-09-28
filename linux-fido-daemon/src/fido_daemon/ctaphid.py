"""CTAPHID framing and channel state machine (DAEMON_PLAN.md §10).

Pure packet codec + per-channel reassembly for the virtual FIDO2 HID device.
No I/O lives here: the browser-facing transport (``uhid_device.py``) feeds
64-byte reports into a :class:`CtapHidSession` and sends the returned reports
back.

Framing (FIDO CTAPHID spec §7):

- Initialization packet: ``CID(4) CMD(1, bit7=1) BCNTH(1) BCNTL(1) DATA(57)``
- Continuation packet:   ``CID(4) SEQ(1, bit7=0) DATA(59)``

The completed MSG/CBOR payload is exactly the ``[command_byte, cbor...]`` frame
consumed by ``fido_daemon.ctap2.decode_request_frame``.
"""

from __future__ import annotations

import secrets
from dataclasses import dataclass, field

REPORT_SIZE = 64

BROADCAST_CID = 0xFFFFFFFF
INIT_PKT_BIT = 0x80

# CTAPHID commands (bit7 always set on the wire).
CMD_INIT = 0x86
CMD_PING = 0x81
CMD_MSG = 0x83
CMD_CBOR = 0x90
CMD_CANCEL = 0x91
CMD_ERROR = 0xBF
CMD_KEEPALIVE = 0xBB
CMD_WINK = 0x88

# CTAPHID error codes.
ERR_INVALID_CMD = 0x01
ERR_INVALID_PAR = 0x02
ERR_INVALID_LEN = 0x03
ERR_INVALID_SEQ = 0x04
ERR_MSG_TIMEOUT = 0x05
ERR_CHANNEL_BUSY = 0x06
ERR_INVALID_CHANNEL = 0x0B
ERR_KEEPALIVE_CANCEL = 0x0D
ERR_OTHER = 0x7F

# CTAPHID keepalive statuses.
STATUS_PROCESSING = 0x01
STATUS_UP_NEEDED = 0x02

# CTAPHID spec: INIT nonce is 8 bytes; the reply appends the 4-byte channel id
# and a 5-byte version/capabilities block (protocol, major, minor, build, caps).
INIT_NONCE_LEN = 8
CTAPHID_VERSION = 0x02
CAPABILITY_WINK = 0x01
CAPABILITY_CBOR = 0x02

_INIT_DATA_LEN = REPORT_SIZE - 7  # 57
_CONT_DATA_LEN = REPORT_SIZE - 5  # 59


class CtapHidError(Exception):
    """A CTAPHID error code to return to the browser."""

    def __init__(self, code: int, message: str = "") -> None:
        self.code = code
        super().__init__(message or f"CTAPHID error 0x{code:02x}")


@dataclass(frozen=True)
class CtaphidInitPacket:
    cid: int
    cmd: int
    bcnt: int
    data: bytes


@dataclass(frozen=True)
class CtaphidContPacket:
    cid: int
    seq: int
    data: bytes


# ---------------------------------------------------------------------------
# Packet codec
# ---------------------------------------------------------------------------


def parse_report(report: bytes) -> CtaphidInitPacket | CtaphidContPacket:
    """Parse one 64-byte report into an init or continuation packet."""
    cid = int.from_bytes(report[0:4], "big")
    byte4 = report[4]
    if byte4 & INIT_PKT_BIT:
        bcnt = int.from_bytes(report[5:7], "big")
        return CtaphidInitPacket(cid=cid, cmd=byte4, bcnt=bcnt, data=report[7 : 7 + bcnt])
    return CtaphidContPacket(cid=cid, seq=byte4, data=report[5:])


def build_init(cid: int, cmd: int, bcnt: int, payload: bytes) -> bytes:
    report = bytearray(REPORT_SIZE)
    report[0:4] = cid.to_bytes(4, "big")
    report[4] = cmd
    report[5:7] = bcnt.to_bytes(2, "big")
    chunk = payload[:_INIT_DATA_LEN]
    report[7 : 7 + len(chunk)] = chunk
    return bytes(report)


def build_cont(cid: int, seq: int, payload: bytes) -> bytes:
    report = bytearray(REPORT_SIZE)
    report[0:4] = cid.to_bytes(4, "big")
    report[4] = seq
    chunk = payload[:_CONT_DATA_LEN]
    report[5 : 5 + len(chunk)] = chunk
    return bytes(report)


def build_error(cid: int, code: int) -> bytes:
    return build_init(cid, CMD_ERROR, 1, bytes([code]))


def build_keepalive(cid: int, status: int) -> bytes:
    return build_init(cid, CMD_KEEPALIVE, 1, bytes([status]))


def fragment(cid: int, cmd: int, payload: bytes) -> list[bytes]:
    """Split a full CTAPHID message into one init report + continuation reports."""
    if len(payload) <= _INIT_DATA_LEN:
        return [build_init(cid, cmd, len(payload), payload)]
    reports = [build_init(cid, cmd, len(payload), payload[:_INIT_DATA_LEN])]
    rest = payload[_INIT_DATA_LEN:]
    seq = 0
    while rest:
        reports.append(build_cont(cid, seq, rest[:_CONT_DATA_LEN]))
        rest = rest[_CONT_DATA_LEN:]
        seq += 1
    return reports


# ---------------------------------------------------------------------------
# CID allocation
# ---------------------------------------------------------------------------


class CidAllocator:
    """Allocates fresh non-broadcast, non-zero CTAPHID channel IDs.

    Freed IDs are reused before advancing to fresh ones.
    """

    def __init__(self) -> None:
        self._freed: set[int] = set()
        self._next = 1

    def alloc(self) -> int:
        if self._freed:
            cid = min(self._freed)
            self._freed.discard(cid)
            return cid
        while True:
            candidate = self._next
            self._next = (self._next + 1) & 0xFFFFFFFF
            if candidate == 0 or candidate == BROADCAST_CID:
                continue
            return candidate

    def release(self, cid: int) -> None:
        if cid != 0 and cid != BROADCAST_CID:
            self._freed.add(cid)


# ---------------------------------------------------------------------------
# Session
# ---------------------------------------------------------------------------


class _FragmentedReader:
    """Reassembles one CTAPHID transaction (init + continuations) per channel."""

    def __init__(self, cid: int) -> None:
        self.cid = cid
        self._cmd: int | None = None
        self._expected = 0
        self._data = bytearray()
        self._next_seq = 0
        self.cancelled = False

    def start(self, cmd: int, bcnt: int, first: bytes) -> None:
        self._cmd = cmd
        self._expected = bcnt
        self._data = bytearray(first)
        self._next_seq = 0
        self.cancelled = False

    def feed_cont(self, seq: int, data: bytes) -> None:
        if self._cmd is None:
            raise CtapHidError(ERR_INVALID_SEQ, "continuation without an active transaction")
        if seq != self._next_seq:
            raise CtapHidError(
                ERR_INVALID_SEQ, f"expected seq {self._next_seq}, got {seq}"
            )
        self._data.extend(data)
        self._next_seq += 1

    @property
    def complete(self) -> bool:
        return self._cmd is not None and len(self._data) >= self._expected

    def take(self) -> tuple[int, bytes] | None:
        """Return the completed (cmd, payload) and reset the transaction."""
        if not self.complete:
            return None
        result = (self._cmd, bytes(self._data[: self._expected]))
        self._cmd = None
        self._data = bytearray()
        return result


class CtapHidSession:
    """State machine for one virtual FIDO2 device.

    Feed kernel-sourced 64-byte reports via :meth:`handle_report`; it returns
    the outbound reports to write back. Completed MSG/CBOR messages are
    collected by :meth:`poll_completed` as ``(cid, cmd, payload)`` tuples.
    """

    def __init__(self, nonce_source=None) -> None:
        self._nonce_source = nonce_source or secrets.token_bytes
        self._allocator = CidAllocator()
        self._channels: dict[int, _FragmentedReader] = {}
        self._completed: list[tuple[int, int, bytes]] = []
        self._closing: list[int] = []

    @property
    def channels(self) -> dict[int, _FragmentedReader]:
        return self._channels

    def _set_nonce_source(self, source) -> None:
        self._nonce_source = source

    def handle_report(self, report: bytes) -> list[bytes]:
        packet = parse_report(report)
        if isinstance(packet, CtaphidInitPacket):
            return self._handle_init_packet(packet)
        return self._handle_cont_packet(packet)

    def poll_completed(self) -> list[tuple[int, int, bytes]]:
        completed, self._completed = self._completed, []
        return completed

    def take_closing(self) -> list[int]:
        closing, self._closing = self._closing, []
        return closing

    def release_channel(self, cid: int) -> None:
        self._channels.pop(cid, None)
        self._allocator.release(cid)

    # -- internals ----------------------------------------------------------

    def _handle_init_packet(self, packet: CtaphidInitPacket) -> list[bytes]:
        if packet.cmd == CMD_INIT:
            return self._handle_ctaphid_init(packet)
        channel = self._channels.get(packet.cid)
        if channel is None:
            return [build_error(packet.cid, ERR_INVALID_CHANNEL)]
        if packet.cmd == CMD_PING:
            return [build_init(packet.cid, CMD_PING, packet.bcnt, packet.data)]
        if packet.cmd == CMD_WINK:
            return [build_init(packet.cid, CMD_WINK, 0, b"")]
        if packet.cmd == CMD_KEEPALIVE:
            return []
        if packet.cmd == CMD_CANCEL:
            channel.cancelled = True
            self._closing.append(packet.cid)
            return [build_error(packet.cid, ERR_KEEPALIVE_CANCEL)]
        if packet.cmd == CMD_ERROR:
            self._closing.append(packet.cid)
            self.release_channel(packet.cid)
            return []
        if packet.cmd in (CMD_MSG, CMD_CBOR):
            channel.start(packet.cmd, packet.bcnt, packet.data)
            completed = channel.take()
            if completed is not None:
                self._completed.append((packet.cid, completed[0], completed[1]))
            return []
        return [build_error(packet.cid, ERR_INVALID_CMD)]

    def _handle_ctaphid_init(self, packet: CtaphidInitPacket) -> list[bytes]:
        if packet.cid != BROADCAST_CID:
            return [build_error(packet.cid, ERR_INVALID_CMD)]
        new_cid = self._allocator.alloc()
        self._channels[new_cid] = _FragmentedReader(new_cid)
        # CTAPHID spec: the device MUST echo the host's nonce (8 bytes) in the
        # INIT response; Chrome and libfido2 drop the device if it does not.
        # Response payload is 17 bytes: nonce(8) + channel id(4) +
        # protocol version(1) + major(1) + minor(1) + build(1) + capabilities(1).
        nonce = packet.data[:INIT_NONCE_LEN].ljust(INIT_NONCE_LEN, b"\x00")
        payload = (
            nonce
            + new_cid.to_bytes(4, "big")
            + bytes([CTAPHID_VERSION, 0x01, 0x00, 0x00, CAPABILITY_CBOR])
        )
        return [build_init(BROADCAST_CID, CMD_INIT, len(payload), payload)]

    def _handle_cont_packet(self, packet: CtaphidContPacket) -> list[bytes]:
        channel = self._channels.get(packet.cid)
        if channel is None:
            return [build_error(packet.cid, ERR_INVALID_CHANNEL)]
        try:
            channel.feed_cont(packet.seq, packet.data)
        except CtapHidError as exc:
            return [build_error(packet.cid, exc.code)]
        completed = channel.take()
        if completed is not None:
            self._completed.append((packet.cid, completed[0], completed[1]))
        return []
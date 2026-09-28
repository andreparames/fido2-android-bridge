"""Virtual FIDO2 HID device transport over /dev/uhid (DAEMON_PLAN.md §10).

Presents the daemon to local browsers as a standard security key: browsers see
a `/dev/hidraw*` node and speak 64-byte CTAPHID reports; the kernel forwards
them to us as ``UHID_OUTPUT`` events. Completed MSG/CBOR messages are handed
to the injected async ``on_message(cmd, payload) -> bytes`` handler (the same
CTAP2 -> PROTOCOL.md core as the Unix socket), and the response is fragmented
back into CTAPHID reports via ``UHID_INPUT2``.

The uhid fd is pollable, so it is serviced on the asyncio loop with
``loop.add_reader`` — no threads.
"""

from __future__ import annotations

import asyncio
import logging
import os
import struct
from dataclasses import dataclass

from fido_daemon.ctaphid import (
    STATUS_PROCESSING,
    CtapHidSession,
    build_keepalive,
    fragment,
)

logger = logging.getLogger(__name__)

UHID_DEVICE_PATH = "/dev/uhid"

# uhid_event_type (include/uapi/linux/uhid.h).
UHID_DESTROY = 0x01
UHID_START = 0x02
UHID_STOP = 0x03
UHID_OPEN = 0x04
UHID_CLOSE = 0x05
UHID_OUTPUT = 0x06
UHID_GET_REPORT = 0x09
UHID_GET_REPORT_REPLY = 0x0A
UHID_CREATE2 = 0x0B
UHID_INPUT2 = 0x0C

# uhid_report_type.
UHID_FEATURE_REPORT = 0x00
UHID_OUTPUT_REPORT = 0x01
UHID_INPUT_REPORT = 0x02

# Largest event is UHID_CREATE2: 4-byte type + 128+64+64+2+2+4+4+4+4+4096 bytes.
UHID_EVENT_SIZE = 4 + 4372

# Standard FIDO HID descriptor (usage page 0xF1D0, usage 0x06 CTAP HID):
# three 64-byte reports (input/output/feature), no report ID.
FIDO_HID_DESCRIPTOR = bytes(
    [
        0x06, 0xD0, 0xF1,  # Usage Page 0xF1D0 (FIDO Alliance)
        0x09, 0x01,  #   Usage 0x01 (FIDO Authenticator Device)
        0xA1, 0x01,  #   Collection (Application)
        0x09, 0x20,  #     Usage 0x20 (Input Report Data)
        0x15, 0x00,  #     Logical Minimum 0
        0x26, 0xFF, 0x00,  #   Logical Maximum 255
        0x75, 0x08,  #     Report Size 8
        0x95, 0x40,  #     Report Count 64
        0x81, 0x02,  #     Input (Data, Variable, Absolute)
        0x09, 0x21,  #     Usage 0x21 (Output Report Data)
        0x91, 0x02,  #     Output (Data, Variable, Absolute)
        0x09, 0x21,  #     Usage 0x21 (feature report data)
        0xB1, 0x02,  #     Feature (Data, Variable, Absolute)
        0xC0,  #   End Collection
    ]
)


def _le16(value: int) -> bytes:
    return struct.pack("<H", value)


def _le32(value: int) -> bytes:
    return struct.pack("<I", value)


# ---------------------------------------------------------------------------
# UHID event codec (kernel ABI)
# ---------------------------------------------------------------------------


@dataclass
class UhidCreate2Event:
    name: bytes
    rd_size: int
    bus: int
    rd_data: bytes


@dataclass
class UhidOutputEvent:
    size: int
    rtype: int
    data: bytes


@dataclass
class UhidGetReportEvent:
    id: int
    rnum: int
    rtype: int


@dataclass
class UhidInput2Event:
    size: int
    data: bytes


@dataclass
class UhidGetReportReplyEvent:
    id: int
    err: int
    size: int
    data: bytes


@dataclass
class UhidEvent:
    type: int
    create2: UhidCreate2Event | None = None
    output: UhidOutputEvent | None = None
    get_report: UhidGetReportEvent | None = None
    input2: UhidInput2Event | None = None
    get_report_reply: UhidGetReportReplyEvent | None = None


def parse_event(buf: bytes) -> UhidEvent:
    ev_type = struct.unpack_from("<I", buf, 0)[0]
    if ev_type == UHID_CREATE2:
        rd_size = struct.unpack_from("<H", buf, 260)[0]
        return UhidEvent(
            ev_type,
            create2=UhidCreate2Event(
                name=buf[4:132].rstrip(b"\x00"),
                rd_size=rd_size,
                bus=struct.unpack_from("<H", buf, 262)[0],
                rd_data=buf[280 : 280 + rd_size],
            ),
        )
    if ev_type == UHID_OUTPUT:
        size = struct.unpack_from("<H", buf, 4100)[0]
        return UhidEvent(
            ev_type,
            output=UhidOutputEvent(
                size=size, rtype=buf[4102], data=buf[4 : 4 + size]
            ),
        )
    if ev_type == UHID_GET_REPORT:
        return UhidEvent(
            ev_type,
            get_report=UhidGetReportEvent(
                id=struct.unpack_from("<I", buf, 4)[0],
                rnum=buf[8],
                rtype=buf[9],
            ),
        )
    if ev_type == UHID_INPUT2:
        size = struct.unpack_from("<H", buf, 4)[0]
        return UhidEvent(
            ev_type, input2=UhidInput2Event(size=size, data=buf[6 : 6 + size])
        )
    if ev_type == UHID_GET_REPORT_REPLY:
        size = struct.unpack_from("<H", buf, 10)[0]
        return UhidEvent(
            ev_type,
            get_report_reply=UhidGetReportReplyEvent(
                id=struct.unpack_from("<I", buf, 4)[0],
                err=struct.unpack_from("<H", buf, 8)[0],
                size=size,
                data=buf[12 : 12 + size],
            ),
        )
    return UhidEvent(ev_type)


def _new_event(ev_type: int, payload: bytes = b"") -> bytes:
    buf = bytearray(UHID_EVENT_SIZE)
    buf[0:4] = _le32(ev_type)
    buf[4 : 4 + len(payload)] = payload
    return bytes(buf)


def create2_event(name: str, descriptor: bytes, *, bus: int = 0x03) -> bytes:
    """Build a UHID_CREATE2 event (device -> kernel)."""
    ev = bytearray(UHID_EVENT_SIZE)
    ev[0:4] = _le32(UHID_CREATE2)
    name_bytes = name.encode("utf-8")[:128]
    ev[4 : 4 + len(name_bytes)] = name_bytes
    ev[260:262] = _le16(len(descriptor))  # rd_size
    ev[262:264] = _le16(bus)
    ev[280 : 280 + len(descriptor)] = descriptor
    return bytes(ev)


def input2_event(data: bytes) -> bytes:
    """Build a UHID_INPUT2 event carrying one CTAPHID report (device -> kernel)."""
    ev = bytearray(UHID_EVENT_SIZE)
    ev[0:4] = _le32(UHID_INPUT2)
    ev[4:6] = _le16(len(data))
    ev[6 : 6 + len(data)] = data
    return bytes(ev)


def destroy_event() -> bytes:
    return _new_event(UHID_DESTROY)


def get_report_reply_event(ev_id: int, err: int, data: bytes) -> bytes:
    ev = bytearray(UHID_EVENT_SIZE)
    ev[0:4] = _le32(UHID_GET_REPORT_REPLY)
    ev[4:8] = _le32(ev_id)
    ev[8:10] = _le16(err)
    ev[10:12] = _le16(len(data))
    ev[12 : 12 + len(data)] = data
    return bytes(ev)


def uhid_output_event(data: bytes, *, rtype: int = UHID_OUTPUT_REPORT) -> bytes:
    """Build a UHID_OUTPUT event (kernel -> device); used by tests."""
    ev = bytearray(UHID_EVENT_SIZE)
    ev[0:4] = _le32(UHID_OUTPUT)
    ev[4 : 4 + len(data)] = data
    ev[4100:4102] = _le16(len(data))
    ev[4102] = rtype
    return bytes(ev)


def uhid_get_report_event(ev_id: int, *, rnum: int = 0, rtype: int = 0) -> bytes:
    """Build a UHID_GET_REPORT event (kernel -> device); used by tests."""
    ev = bytearray(UHID_EVENT_SIZE)
    ev[0:4] = _le32(UHID_GET_REPORT)
    ev[4:8] = _le32(ev_id)
    ev[8] = rnum
    ev[9] = rtype
    return bytes(ev)


# ---------------------------------------------------------------------------
# Device
# ---------------------------------------------------------------------------


class UhidDevice:
    """Serve CTAPHID reports over a virtual FIDO2 HID device.

    ``fd`` may be injected for testing (e.g. one end of a socketpair); when
    omitted, ``/dev/uhid`` is opened on ``start()``.
    """

    def __init__(
        self,
        *,
        name: str = "fido-daemon",
        descriptor: bytes = FIDO_HID_DESCRIPTOR,
        fd: int | None = None,
        on_message=None,
    ) -> None:
        self._name = name
        self._descriptor = descriptor
        self._fd = fd
        self._owns_fd = fd is None
        self._on_message = on_message
        self._session = CtapHidSession()
        self._loop: asyncio.AbstractEventLoop | None = None
        self._pending: dict[int, asyncio.Task] = {}
        self._keepalive_interval = 1.0

    async def start(self) -> None:
        if self._fd is None:
            self._fd = os.open(UHID_DEVICE_PATH, os.O_RDWR)
        os.set_blocking(self._fd, False)
        self._loop = asyncio.get_running_loop()
        self._loop.add_reader(self._fd, self._on_readable)
        self._write_event(create2_event(self._name, self._descriptor))
        logger.info("virtual FIDO2 device '%s' created", self._name)

    async def close(self) -> None:
        if self._fd is None:
            return
        if self._loop is not None:
            self._loop.remove_reader(self._fd)
        for task in list(self._pending.values()):
            task.cancel()
        self._pending.clear()
        try:
            self._write_event(destroy_event())
        except OSError:
            pass
        if self._owns_fd:
            os.close(self._fd)
        self._fd = None
        logger.info("virtual FIDO2 device '%s' destroyed", self._name)

    # -- kernel event handling ----------------------------------------------

    def _on_readable(self) -> None:
        try:
            buf = self._read_exact(UHID_EVENT_SIZE)
        except EOFError:
            logger.warning("uhid device closed by the kernel")
            asyncio.create_task(self.close())
            return
        if not buf:
            return
        event = parse_event(buf)
        if event.output is not None:
            self._on_output(event.output)
        elif event.get_report is not None:
            self._on_get_report(event.get_report)

    def _on_output(self, output: UhidOutputEvent) -> None:
        if output.rtype != UHID_OUTPUT_REPORT:
            return
        data = output.data
        # hidraw delivers one leading report-id byte (0x00 for unnumbered
        # reports) in front of the CTAPHID report; Chrome sends it, while
        # some clients (python-fido2) omit it. Strip it when present so the
        # framing parser sees the 64-byte report starting at the CID.
        if len(data) > 64 and data[0] == 0x00:
            data = data[1:]
        report = data[:64]
        if len(report) != 64:
            report = report.ljust(64, b"\x00")
        responses = self._session.handle_report(report)
        for outbound in responses:
            self._write_event(input2_event(outbound))
        for cid in self._session.take_closing():
            self._cancel_pending(cid)
            self._session.release_channel(cid)
        for cid, cmd, payload in self._session.poll_completed():
            self._pending[cid] = asyncio.create_task(self._run_message(cid, cmd, payload))

    def _on_get_report(self, request: UhidGetReportEvent) -> None:
        self._write_event(get_report_reply_event(request.id, 0, bytes(64)))

    # -- request handling ----------------------------------------------------

    async def _run_message(self, cid: int, cmd: int, payload: bytes) -> None:
        if self._on_message is None:
            self._pending.pop(cid, None)
            return
        keepalive = asyncio.create_task(self._keepalive(cid))
        try:
            try:
                response = await self._on_message(payload)
            except Exception:
                logger.exception("ctap2 message handler failed for channel 0x%08x", cid)
                return
        finally:
            keepalive.cancel()
            self._pending.pop(cid, None)
        if cid not in self._session.channels:
            return
        for outbound in fragment(cid, cmd, response):
            self._write_event(input2_event(outbound))

    async def _keepalive(self, cid: int) -> None:
        try:
            while True:
                await asyncio.sleep(self._keepalive_interval)
                if cid not in self._session.channels:
                    return
                self._write_event(input2_event(build_keepalive(cid, STATUS_PROCESSING)))
        except asyncio.CancelledError:
            pass

    def _cancel_pending(self, cid: int) -> None:
        task = self._pending.pop(cid, None)
        if task is not None:
            task.cancel()

    # -- fd helpers ----------------------------------------------------------

    def _write_event(self, ev: bytes) -> None:
        os.write(self._fd, ev)

    def _read_exact(self, n: int) -> bytes:
        buf = bytearray()
        while len(buf) < n:
            try:
                chunk = os.read(self._fd, n - len(buf))
            except BlockingIOError:
                return b""
            if not chunk:
                raise EOFError
            buf.extend(chunk)
        return bytes(buf)
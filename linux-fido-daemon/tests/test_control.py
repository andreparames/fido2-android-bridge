"""Control-plane socket tests (relay-publisher-auth plan, step 1)."""

import asyncio
import json
import os
import socket
import stat

import pytest

from fido_daemon.ctrl import ControlServer, control_request


async def _read_line(path: str, payload: bytes, timeout: float = 2.0) -> str:
    reader, writer = await asyncio.open_unix_connection(path)
    try:
        writer.write(payload)
        await writer.drain()
        return await asyncio.wait_for(reader.readline(), timeout)
    finally:
        writer.close()
        await writer.wait_closed()


async def _dispatch_echo(request: dict) -> dict:
    return request


async def _dispatch_strict(request: dict) -> dict:
    if request.get("cmd") == "pair":
        return {"channel": "abc", "pubkey": "b64pub"}
    return {"error": "unknown_command"}


@pytest.mark.asyncio
async def test_pair_request_returns_channel_and_rotates(tmp_path) -> None:
    """First pair subscribes the daemon and returns a channel; a second pair
    rotates to a different channel."""
    path = str(tmp_path / "fido2-ctrl.sock")
    calls: list[str] = []

    async def dispatch(request: dict) -> dict:
        assert request == {"cmd": "pair"}
        calls.append(request["cmd"])
        return {"channel": f"abc{len(calls)}", "pubkey": "b64pub"}

    server = ControlServer(path, dispatch)
    await server.start()
    try:
        first = json.loads((await _read_line(path, b'{"cmd": "pair"}\n')).decode())
        second = json.loads((await _read_line(path, b'{"cmd": "pair"}\n')).decode())
    finally:
        await server.close()

    assert first == {"channel": "abc1", "pubkey": "b64pub"}
    assert second == {"channel": "abc2", "pubkey": "b64pub"}
    assert calls == ["pair", "pair"]


@pytest.mark.asyncio
async def test_unknown_command_fails_closed(tmp_path) -> None:
    path = str(tmp_path / "fido2-ctrl.sock")
    server = ControlServer(path, _dispatch_strict)
    await server.start()
    try:
        line = await _read_line(path, b'{"cmd": "nope"}\n')
    finally:
        await server.close()

    assert json.loads(line.decode()) == {"error": "unknown_command"}


@pytest.mark.asyncio
async def test_handler_error_yields_error_response(tmp_path) -> None:
    path = str(tmp_path / "fido2-ctrl.sock")

    async def bad_dispatch(_request: dict) -> dict:
        raise RuntimeError("boom")

    server = ControlServer(path, bad_dispatch)
    await server.start()
    try:
        response = json.loads((await _read_line(path, b'{"cmd": "pair"}\n')).decode())
    finally:
        await server.close()

    assert response == {"error": "invalid_request"}


@pytest.mark.asyncio
async def test_malformed_request_fails_closed(tmp_path) -> None:
    path = str(tmp_path / "fido2-ctrl.sock")
    server = ControlServer(path, _dispatch_echo)
    await server.start()
    try:
        line = await _read_line(path, b"not json\n")
    finally:
        await server.close()

    assert json.loads(line.decode()) == {"error": "invalid_request"}


@pytest.mark.asyncio
async def test_socket_path_mode_0600(tmp_path) -> None:
    path = str(tmp_path / "fido2-ctrl.sock")
    server = ControlServer(path, _dispatch_echo)
    await server.start()
    try:
        mode = stat.S_IMODE(os.stat(path).st_mode)
    finally:
        await server.close()

    assert mode == 0o600
    assert not os.path.exists(path)


@pytest.mark.asyncio
async def test_control_request_roundtrips_and_fails_after_close(tmp_path) -> None:
    path = str(tmp_path / "fido2-ctrl.sock")
    server = ControlServer(path, _dispatch_strict)
    await server.start()
    try:
        response = await asyncio.to_thread(control_request, path, {"cmd": "pair"})
        assert response == {"channel": "abc", "pubkey": "b64pub"}
    finally:
        await server.close()

    with pytest.raises(OSError):
        await asyncio.to_thread(control_request, path, {"cmd": "pair"})

@pytest.mark.asyncio
async def test_start_reclaims_stale_socket(tmp_path) -> None:
    """A leftover socket from a dead daemon is reclaimed; a live one is not."""
    path = str(tmp_path / "fido2-ctrl.sock")
    stale = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    stale.bind(path)
    stale.close()  # leaves the socket file behind (unbound -> refused)

    server = ControlServer(path, _dispatch_strict)
    await server.start()
    try:
        assert stat.S_ISSOCK(os.stat(path).st_mode)
        assert stat.S_IMODE(os.stat(path).st_mode) == 0o600
    finally:
        await server.close()
    assert not os.path.exists(path)


@pytest.mark.asyncio
async def test_failed_start_does_not_remove_live_socket(tmp_path) -> None:
    path = str(tmp_path / "fido2-ctrl.sock")
    live = ControlServer(path, _dispatch_strict)
    await live.start()
    try:
        intruder = ControlServer(path, _dispatch_strict)
        with pytest.raises(OSError):
            await intruder.start()
        await intruder.close()  # must not unlink live's socket
        assert os.path.exists(path)
    finally:
        await live.close()
    assert not os.path.exists(path)

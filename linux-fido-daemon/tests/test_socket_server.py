import asyncio
import os
import stat

import pytest

from fido_daemon.socket_server import SocketServer

SOCKET_NAME = "fido2-bridge.sock"


async def _echo(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
    data = await reader.read(4096)
    writer.write(data)
    await writer.drain()


async def _raising_handler(
    reader: asyncio.StreamReader, writer: asyncio.StreamWriter
) -> None:
    raise RuntimeError("boom")


@pytest.mark.asyncio
async def test_start_creates_socket_with_0600(tmp_path) -> None:
    path = str(tmp_path / SOCKET_NAME)
    server = SocketServer(path, _echo)
    try:
        await server.start()
        assert os.path.exists(path)
        assert stat.S_ISREG(os.stat(path).st_mode) is False
        assert stat.S_IMODE(os.stat(path).st_mode) == 0o600
    finally:
        await server.close()


@pytest.mark.asyncio
async def test_client_echo_roundtrip(tmp_path) -> None:
    path = str(tmp_path / SOCKET_NAME)
    server = SocketServer(path, _echo)
    try:
        await server.start()
        reader, writer = await asyncio.open_unix_connection(path)
        writer.write(b"hello")
        await writer.drain()
        response = await reader.read(4096)
        assert response == b"hello"
        writer.close()
        await writer.wait_closed()
    finally:
        await server.close()


@pytest.mark.asyncio
async def test_handler_exception_does_not_crash_server(tmp_path) -> None:
    path = str(tmp_path / SOCKET_NAME)
    server = SocketServer(path, _raising_handler)
    try:
        await server.start()
        reader, writer = await asyncio.open_unix_connection(path)
        writer.write(b"x")
        await writer.drain()
        try:
            await reader.read(4096)
        except OSError:
            pass
        writer.close()

        reader2, writer2 = await asyncio.open_unix_connection(path)
        writer2.close()
        await writer2.wait_closed()
    finally:
        await server.close()


@pytest.mark.asyncio
async def test_close_unlinks_socket(tmp_path) -> None:
    path = str(tmp_path / SOCKET_NAME)
    server = SocketServer(path, _echo)
    await server.start()
    assert os.path.exists(path)
    await server.close()
    assert not os.path.exists(path)

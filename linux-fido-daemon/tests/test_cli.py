"""CLI pairing tests (relay-publisher-auth plan, step 4).

``fido-daemon pair`` talks to the running daemon over its control socket and
builds the pairing URI from the daemon's response: classic mode embeds the
relay token, managed mode omits it (the publish proxy is the gate).
"""

import asyncio

import pytest

from fido_daemon.cli import _build_relay, _pair_uri, main
from fido_daemon.config import Config
from fido_daemon.ctrl import ControlServer

TOKEN = "abc123"

MANAGED_URL = "wss://relay.gatebridge.app/connection/websocket"
CLASSIC_URL = "ws://localhost:8000/connection/websocket"

DAEMON_PRIVATE = bytes(range(32))
CHANNEL_HEX = "0123456789abcdef0123456789abcdef"
PUBKEY = "b64urlpubkey"


def _config(relay_url: str, *, token: str = TOKEN, control_socket: str | None = None) -> Config:
    return Config(
        socket_path="/tmp/unused.sock",
        relay_url=relay_url,
        static_key_path="/tmp/unused_key.pem",
        relay_token=token,
        request_timeout=5.0,
        uhid_enabled=False,
        uhid_name="fido-daemon",
        control_socket=control_socket,
    )


def test_pair_uri_classic_embeds_token() -> None:
    uri = _pair_uri(_config(CLASSIC_URL), CHANNEL_HEX, PUBKEY)
    assert uri == f"fidobridge://pair?channel={CHANNEL_HEX}&pubkey={PUBKEY}&token={TOKEN}"


def test_pair_uri_managed_omits_token() -> None:
    uri = _pair_uri(_config(MANAGED_URL), CHANNEL_HEX, PUBKEY)
    assert uri == f"fidobridge://pair?channel={CHANNEL_HEX}&pubkey={PUBKEY}"


def test_pair_uri_classic_without_token_omits_token() -> None:
    uri = _pair_uri(_config(CLASSIC_URL, token=""), CHANNEL_HEX, PUBKEY)
    assert uri == f"fidobridge://pair?channel={CHANNEL_HEX}&pubkey={PUBKEY}"


def test_build_relay_forwards_url_and_token() -> None:
    relay = _build_relay(_config(CLASSIC_URL), DAEMON_PRIVATE)
    assert relay._url == CLASSIC_URL
    assert relay._token == TOKEN


async def _pair_daemon(monkeypatch, tmp_path, relay_url: str) -> str:
    """Run a ControlServer + daemon-side dispatch, call main(['pair']), return URI."""
    control_socket = str(tmp_path / "fido2-ctrl.sock")
    channel_hex = "feedfacefeedfacefeedfacefeedface"

    async def dispatch(request: dict) -> dict:
        assert request == {"cmd": "pair"}
        return {"channel": channel_hex, "pubkey": PUBKEY}

    server = ControlServer(control_socket, dispatch)
    await server.start()
    monkeypatch.setenv("FIDO2_CONTROL_SOCKET", control_socket)
    monkeypatch.setenv("FIDO2_RELAY_URL", relay_url)
    monkeypatch.setenv("FIDO2_RELAY_TOKEN", TOKEN)
    try:
        rc = await asyncio.to_thread(main, ["pair", "--no-qr"])
    finally:
        await server.close()
    assert rc == 0
    return channel_hex


@pytest.mark.asyncio
async def test_pair_cli_roundtrips_classic(monkeypatch, tmp_path, capsys) -> None:
    channel = await _pair_daemon(monkeypatch, tmp_path, CLASSIC_URL)
    uri = capsys.readouterr().out.strip()
    assert uri == f"fidobridge://pair?channel={channel}&pubkey={PUBKEY}&token={TOKEN}"


@pytest.mark.asyncio
async def test_pair_cli_roundtrips_managed_no_token(monkeypatch, tmp_path, capsys) -> None:
    channel = await _pair_daemon(monkeypatch, tmp_path, MANAGED_URL)
    uri = capsys.readouterr().out.strip()
    assert uri == f"fidobridge://pair?channel={channel}&pubkey={PUBKEY}"


@pytest.mark.asyncio
async def test_pair_cli_errors_when_daemon_down(monkeypatch, tmp_path, capsys) -> None:
    control_socket = str(tmp_path / "missing-ctrl.sock")
    monkeypatch.setenv("FIDO2_CONTROL_SOCKET", control_socket)
    monkeypatch.setenv("FIDO2_RELAY_URL", CLASSIC_URL)
    rc = await asyncio.to_thread(main, ["pair", "--no-qr"])
    assert rc == 2
    assert "start `fido-daemon` first" in capsys.readouterr().err


@pytest.mark.asyncio
async def test_pair_cli_errors_when_daemon_refuses(monkeypatch, tmp_path, capsys) -> None:
    control_socket = str(tmp_path / "fido2-ctrl.sock")

    async def refuse(_request: dict) -> dict:
        return {"error": "unknown_command"}

    server = ControlServer(control_socket, refuse)
    await server.start()
    monkeypatch.setenv("FIDO2_CONTROL_SOCKET", control_socket)
    monkeypatch.setenv("FIDO2_RELAY_URL", CLASSIC_URL)
    try:
        rc = await asyncio.to_thread(main, ["pair", "--no-qr"])
    finally:
        await server.close()
    assert rc == 2
    assert "pair refused" in capsys.readouterr().err
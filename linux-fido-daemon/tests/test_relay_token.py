import logging

import pytest

from fido_daemon.relay import RelayClient

CHANNEL_ID = "0123456789abcdef0123456789abcdef"
RELAY_URL = "ws://localhost:8000/connection/websocket"

TOKEN = "eyJhbGciOiJIUzI1NiJ9.SECRET"


def test_token_present_configured_on_client(daemon_static_private: bytes) -> None:
    relay = RelayClient(RELAY_URL, CHANNEL_ID, daemon_static_private, token=TOKEN)
    client = relay._build_client()
    assert client._token == TOKEN
    assert client._get_token.__self__ is relay


def test_token_absent_configures_anonymous_client(daemon_static_private: bytes) -> None:
    relay = RelayClient(RELAY_URL, CHANNEL_ID, daemon_static_private)
    client = relay._build_client()
    assert client._token == ""


async def test_token_not_in_url_or_logs(
    daemon_static_private: bytes, fake_client, caplog: pytest.LogCaptureFixture
) -> None:
    relay = RelayClient(
        RELAY_URL,
        CHANNEL_ID,
        daemon_static_private,
        token=TOKEN,
        client_factory=lambda: fake_client,
    )
    assert TOKEN not in relay._url
    with caplog.at_level(logging.INFO):
        await relay.connect()
    assert TOKEN not in caplog.text


async def test_get_token_reused_on_reconnect(daemon_static_private: bytes) -> None:
    relay = RelayClient(RELAY_URL, CHANNEL_ID, daemon_static_private, token=TOKEN)
    assert await relay._get_token() == TOKEN
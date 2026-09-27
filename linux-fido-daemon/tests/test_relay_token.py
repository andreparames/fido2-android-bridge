import logging

import pytest

from fido_daemon.crypto import AesGcmCipher
from fido_daemon.relay import RelayClient

CHANNEL_ID = "0123456789abcdef0123456789abcdef"
RELAY_URL = "ws://localhost:8000/connection/websocket"

TOKEN = "eyJhbGciOiJIUzI1NiJ9.SECRET"


def test_token_present_configured_on_client(cipher: AesGcmCipher) -> None:
    relay = RelayClient(RELAY_URL, CHANNEL_ID, cipher, token=TOKEN)
    client = relay._build_client()
    assert client._token == TOKEN
    assert client._get_token.__self__ is relay


def test_token_absent_configures_anonymous_client(cipher: AesGcmCipher) -> None:
    relay = RelayClient(RELAY_URL, CHANNEL_ID, cipher)
    client = relay._build_client()
    assert client._token == ""


async def test_token_not_in_url_or_logs(
    cipher: AesGcmCipher, fake_client, caplog: pytest.LogCaptureFixture
) -> None:
    relay = RelayClient(
        RELAY_URL, CHANNEL_ID, cipher, token=TOKEN, client_factory=lambda: fake_client
    )
    assert TOKEN not in relay._url
    with caplog.at_level(logging.INFO):
        await relay.connect()
    assert TOKEN not in caplog.text


async def test_get_token_reused_on_reconnect(cipher: AesGcmCipher) -> None:
    relay = RelayClient(RELAY_URL, CHANNEL_ID, cipher, token=TOKEN)
    assert await relay._get_token() == TOKEN

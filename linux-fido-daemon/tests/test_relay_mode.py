"""Relay mode detection tests (managed-relay plan, phase A).

Managed iff the relay URL host (parsed properly) is exactly
``relay.gatebridge.app`` (case-insensitive); scheme and port are ignored.
Anything else, including look-alike hosts and unparsable URLs, is classic.
"""

import pytest

from fido_daemon.relay_mode import is_managed_relay

MANAGED = "wss://relay.gatebridge.app/connection/websocket"


@pytest.mark.parametrize(
    "url",
    [
        "wss://relay.gatebridge.app/connection/websocket",
        "ws://relay.gatebridge.app/connection/websocket",
        "wss://relay.gatebridge.app:443/connection/websocket",
        "wss://relay.gatebridge.app:9000/connection/websocket",
        "wss://RELAY.GATEBRIDGE.APP/connection/websocket",
        "wss://Relay.GateBridge.App/connection/websocket",
        "http://relay.gatebridge.app/connection/websocket",
        "wss://relay.gatebridge.app/",
    ],
)
def test_managed_host_matches(url: str) -> None:
    assert is_managed_relay(url) is True


@pytest.mark.parametrize(
    "url",
    [
        "wss://evil-relay.gatebridge.app/connection/websocket",
        "wss://relay.gatebridge.app.evil.com/connection/websocket",
        "wss://notrelay.gatebridge.app/connection/websocket",
        "ws://localhost:8000/connection/websocket",
        "wss://gary.andreparames.com:8000/connection/websocket",
        "ws://127.0.0.1:8000/connection/websocket",
        "relay.gatebridge.app/connection/websocket",
    ],
)
def test_other_hosts_are_classic(url: str) -> None:
    assert is_managed_relay(url) is False


@pytest.mark.parametrize("url", ["", "not a url", "://", "wss://", None])
def test_unparsable_urls_are_classic(url) -> None:
    assert is_managed_relay(url) is False
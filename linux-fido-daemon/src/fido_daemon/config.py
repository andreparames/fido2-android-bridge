"""Daemon configuration: socket path, relay URL, session key, timeouts.

All values are overridable via environment variables, honoring agents.md
`FIDO2_REMOTE_SOCKET` and keeping secrets out of the process CLI args.
"""

from __future__ import annotations

import os
from dataclasses import dataclass

DEFAULT_SOCKET_PATH = "/run/user/{uid}/fido2-bridge.sock"
DEFAULT_RELAY_URL = "wss://relay.example.invalid/connection/websocket"
DEFAULT_REQUEST_TIMEOUT_SECONDS = 30.0
DEFAULT_CHANNEL_ID = ""


@dataclass(frozen=True)
class Config:
    socket_path: str
    relay_url: str
    channel_id: str
    session_key_b64: str
    relay_token: str
    request_timeout: float

    @classmethod
    def from_env(cls) -> "Config":
        uid = os.getuid()
        return cls(
            socket_path=os.environ.get(
                "FIDO2_REMOTE_SOCKET", DEFAULT_SOCKET_PATH.format(uid=uid)
            ),
            relay_url=os.environ.get("FIDO2_RELAY_URL", DEFAULT_RELAY_URL),
            channel_id=os.environ.get("FIDO2_CHANNEL_ID", DEFAULT_CHANNEL_ID),
            session_key_b64=os.environ.get("FIDO2_SESSION_KEY_B64", ""),
            relay_token=os.environ.get("FIDO2_RELAY_TOKEN", ""),
            request_timeout=float(
                os.environ.get("FIDO2_REQUEST_TIMEOUT", DEFAULT_REQUEST_TIMEOUT_SECONDS)
            ),
        )
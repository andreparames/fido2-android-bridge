"""Shared configuration for integration harness tests.

Reads from environment variables (same names as the daemon) and provides
helpers to generate random keys for local development.
"""

from __future__ import annotations

import base64
import os
import secrets
from dataclasses import dataclass

from fido_daemon.pairing import derive_channel_id


@dataclass(frozen=True)
class HarnessConfig:
    """Configuration for the integration harness.

    All fields map directly to daemon env vars so the same values work for
    both the daemon and the mock peers.
    """

    channel_id: str
    session_key_b64: str
    relay_url: str
    relay_token: str = ""
    request_timeout: float = 10.0

    @classmethod
    def from_env(cls) -> "HarnessConfig":
        """Read config from environment, generating random defaults if needed."""
        relay_url = os.environ.get(
            "FIDO2_RELAY_URL", "ws://localhost:8000/connection/websocket"
        )

        channel_hex = os.environ.get("FIDO2_CHANNEL_ID", "")
        if not channel_hex:
            channel_hex = secrets.token_hex(16)

        channel_id = derive_channel_id(channel_hex)

        session_key_b64 = os.environ.get("FIDO2_SESSION_KEY_B64", "")
        if not session_key_b64:
            key = secrets.token_bytes(32)
            session_key_b64 = base64.b64encode(key).decode()

        return cls(
            channel_id=channel_id,
            session_key_b64=session_key_b64,
            relay_url=relay_url,
            relay_token=os.environ.get("FIDO2_RELAY_TOKEN", ""),
            request_timeout=float(os.environ.get("FIDO2_REQUEST_TIMEOUT", "10.0")),
        )

    @property
    def session_key_bytes(self) -> bytes:
        return base64.b64decode(self.session_key_b64)

    @property
    def channel_hex(self) -> str:
        """The raw 32-char lowercase hex channel string (before derivation).

        Note: this is NOT recoverable from channel_id. It is only available
        when set via FIDO2_CHANNEL_ID env var. For generated configs, this
        returns the channel_id itself (which is also 32 lowercase hex).
        """
        return self.channel_id

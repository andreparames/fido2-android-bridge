"""Shared configuration for integration harness tests.

Reads from environment variables (same names as the daemon) and provides
helpers to load/generate the daemon and phone static identity keys for local
development.
"""

from __future__ import annotations

import os
import secrets
from dataclasses import dataclass
from pathlib import Path

from fido_daemon.noise import StaticKeyStore
from fido_daemon.pairing import derive_channel_id


@dataclass(frozen=True)
class HarnessConfig:
    """Configuration for the integration harness.

    All fields map directly to daemon env vars so the same values work for
    both the daemon and the mock peers.
    """

    channel_id: str
    static_key_path: str
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

        static_key_path = os.path.expanduser(
            os.environ.get(
                "FIDO2_STATIC_KEY_PATH", "~/.config/fido-daemon/static_key.pem"
            )
        )

        return cls(
            channel_id=channel_id,
            static_key_path=static_key_path,
            relay_url=relay_url,
            relay_token=os.environ.get("FIDO2_RELAY_TOKEN", ""),
            request_timeout=float(os.environ.get("FIDO2_REQUEST_TIMEOUT", "10.0")),
        )

    @property
    def channel_hex(self) -> str:
        """The raw 32-char lowercase hex channel string (before derivation).

        Note: this is NOT recoverable from channel_id. It is only available
        when set via FIDO2_CHANNEL_ID env var. For generated configs, this
        returns the channel_id itself (which is also 32 lowercase hex).
        """
        return self.channel_id

    def daemon_static_private(self) -> bytes:
        """Load (creating if needed) the daemon's static identity key."""
        return StaticKeyStore.load_or_create(Path(self.static_key_path))

    def daemon_static_public(self) -> bytes:
        return StaticKeyStore.public_key(self.daemon_static_private())

    def phone_static_private(self) -> bytes:
        """Phone's static identity key, from env or generated per run."""
        env = os.environ.get("FIDO2_PHONE_STATIC_PRIVATE_B64", "")
        if env:
            import base64

            return base64.b64decode(env)
        return StaticKeyStore.generate()
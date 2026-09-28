"""Daemon configuration: socket path, relay URL, session key, timeouts.

All values are overridable via environment variables, honoring agents.md
`FIDO2_REMOTE_SOCKET` and keeping secrets out of the process CLI args.

A TOML config file (``-c/--config``) can store the pairing-derived values
(session key, channel id, relay token).  File values override env vars for
those three fields; the remaining fields always come from env vars / defaults.
"""

from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

import tomlkit

DEFAULT_SOCKET_PATH = "/run/user/{uid}/fido2-bridge.sock"
DEFAULT_RELAY_URL = "wss://relay.example.invalid/connection/websocket"
DEFAULT_REQUEST_TIMEOUT_SECONDS = 30.0
DEFAULT_CHANNEL_ID = ""
DEFAULT_UHID_NAME = "fido-daemon"


@dataclass(frozen=True)
class Config:
    socket_path: str
    relay_url: str
    channel_id: str
    session_key_b64: str
    relay_token: str
    request_timeout: float
    uhid_enabled: bool
    uhid_name: str

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
            uhid_enabled=os.environ.get("FIDO2_UHID_ENABLED", "0").lower()
            in ("1", "true", "yes"),
            uhid_name=os.environ.get("FIDO2_UHID_NAME", DEFAULT_UHID_NAME),
        )

    def with_config_file(self, path: str | Path) -> "Config":
        """Return a new Config with fields overridden by the TOML file."""
        file_values = load_config_file(path)
        return Config(
            socket_path=self.socket_path,
            relay_url=file_values.get("relay_url") or self.relay_url,
            channel_id=file_values.get("channel_id") or self.channel_id,
            session_key_b64=file_values.get("session_key_b64") or self.session_key_b64,
            relay_token=file_values.get("relay_token") or self.relay_token,
            request_timeout=self.request_timeout,
            uhid_enabled=self.uhid_enabled,
            uhid_name=self.uhid_name,
        )


def load_config_file(path: str | Path) -> dict:
    """Read a TOML config file and return its contents as a dict.

    Returns an empty dict if the file does not exist.
    """
    p = Path(path)
    if not p.exists():
        return {}
    with p.open() as f:
        return tomlkit.load(f)


def write_config_file(
    path: str | Path,
    *,
    session_key_b64: str | None = None,
    channel_id: str | None = None,
    relay_token: str | None = None,
) -> None:
    """Create or update a TOML config file with pairing-derived values.

    Existing content is preserved; only the specified fields are set.
    """
    p = Path(path)
    if p.exists():
        with p.open() as f:
            doc = tomlkit.load(f)
    else:
        doc = tomlkit.document()

    if session_key_b64 is not None:
        doc["session_key_b64"] = session_key_b64
    if channel_id is not None:
        doc["channel_id"] = channel_id
    if relay_token is not None:
        doc["relay_token"] = relay_token

    with p.open("w") as f:
        tomlkit.dump(doc, f)
"""Daemon configuration: socket path, relay URL, static key path, timeouts.

All values are overridable via environment variables, honoring agents.md
`FIDO2_REMOTE_SOCKET` and keeping secrets out of the process CLI args.

A TOML config file (``-c/--config``) can store the pairing-derived values
(channel id, relay token).  File values override env vars for those fields;
the remaining fields always come from env vars / defaults.  The daemon's
static X25519 identity key lives in its own file (``FIDO2_STATIC_KEY_PATH``,
default ``~/.config/fido-daemon/static_key.pem``) and is not stored in TOML.
"""

from __future__ import annotations

import base64
import binascii
import os
from dataclasses import dataclass
from pathlib import Path

import tomlkit

DEFAULT_SOCKET_PATH = "/run/user/{uid}/fido2-bridge.sock"
DEFAULT_RELAY_URL = "wss://relay.gatebridge.app/connection/websocket"
DEFAULT_STATIC_KEY_PATH = "~/.config/fido-daemon/static_key.pem"
DEFAULT_REQUEST_TIMEOUT_SECONDS = 30.0
DEFAULT_CHANNEL_ID = ""
DEFAULT_UHID_NAME = "fido-daemon"
# Shared Centrifugo connection JWT, embedded in the daemon so no `pass`
# dependency at runtime. The `pass` store is only available on the dev host;
# the daemon runs on the remote server. Rotate by regenerating the token and
# updating this constant (or override per-deployment via FIDO2_RELAY_TOKEN).
DEFAULT_RELAY_TOKEN = (
    "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9."
    "eyJzdWIiOiJicmlkZ2UiLCJleHAiOjQ5NDQxMDA2MDAsImlhdCI6MTc5MDUwMDYwMH0."
    "o43c_DKBrf7NIgfywmVwxCq5R56TiRm83HbGHn9VTbk"
)
PHONE_KEY_BYTES = 32


@dataclass(frozen=True)
class Config:
    socket_path: str
    relay_url: str
    channel_id: str
    static_key_path: str
    relay_token: str
    request_timeout: float
    uhid_enabled: bool
    uhid_name: str
    phone_public_key: bytes | None = None
    config_path: str | None = None

    @classmethod
    def from_env(cls) -> "Config":
        uid = os.getuid()
        return cls(
            socket_path=os.environ.get(
                "FIDO2_REMOTE_SOCKET", DEFAULT_SOCKET_PATH.format(uid=uid)
            ),
            relay_url=os.environ.get("FIDO2_RELAY_URL", DEFAULT_RELAY_URL),
            channel_id=os.environ.get("FIDO2_CHANNEL_ID", DEFAULT_CHANNEL_ID),
            static_key_path=os.path.expanduser(
                os.environ.get("FIDO2_STATIC_KEY_PATH", DEFAULT_STATIC_KEY_PATH)
            ),
            relay_token=os.environ.get("FIDO2_RELAY_TOKEN", DEFAULT_RELAY_TOKEN),
            request_timeout=float(
                os.environ.get("FIDO2_REQUEST_TIMEOUT", DEFAULT_REQUEST_TIMEOUT_SECONDS)
            ),
            uhid_enabled=os.environ.get("FIDO2_UHID_ENABLED", "0").lower()
            in ("1", "true", "yes"),
            uhid_name=os.environ.get("FIDO2_UHID_NAME", DEFAULT_UHID_NAME),
            phone_public_key=decode_phone_key(
                os.environ.get("FIDO2_PHONE_PUBLIC_KEY", "")
            ),
        )

    def with_config_file(self, path: str | Path) -> "Config":
        """Return a new Config with fields overridden by the TOML file."""
        file_values = load_config_file(path)
        return Config(
            socket_path=self.socket_path,
            relay_url=file_values.get("relay_url") or self.relay_url,
            channel_id=file_values.get("channel_id") or self.channel_id,
            static_key_path=self.static_key_path,
            relay_token=file_values.get("relay_token") or self.relay_token,
            request_timeout=self.request_timeout,
            uhid_enabled=self.uhid_enabled,
            uhid_name=self.uhid_name,
            phone_public_key=self.phone_public_key
            or decode_phone_key(str(file_values.get("phone_public_key") or "")),
            config_path=str(path),
        )


def decode_phone_key(value: str) -> bytes | None:
    """Decode a base64 phone static public key; None for empty input."""
    if not value:
        return None
    try:
        key = base64.b64decode(value)
    except (ValueError, binascii.Error) as exc:
        raise ValueError("phone public key must be base64") from exc
    if len(key) != PHONE_KEY_BYTES:
        raise ValueError(f"phone public key must decode to {PHONE_KEY_BYTES} bytes")
    return key


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
    channel_id: str | None = None,
    relay_token: str | None = None,
    phone_public_key: str | None = None,
) -> None:
    """Create or update a TOML config file with pairing-derived values.

    Existing content is preserved; only the specified fields are set. The
    static key is never written to the TOML config. `phone_public_key` is a
    base64-encoded 32-byte phone static public key pinned by trust-on-first-use.
    """
    p = Path(path)
    if p.exists():
        with p.open() as f:
            doc = tomlkit.load(f)
    else:
        doc = tomlkit.document()

    if channel_id is not None:
        doc["channel_id"] = channel_id
    if relay_token is not None:
        doc["relay_token"] = relay_token
    if phone_public_key is not None:
        doc["phone_public_key"] = phone_public_key

    with p.open("w") as f:
        tomlkit.dump(doc, f)


def clear_phone_pin(path: str | Path) -> None:
    """Remove the pinned ``phone_public_key`` from a TOML config file.

    Use this to reset trust-on-first-use so a *different* phone can pair.
    Other fields (channel_id, relay_token, custom keys) are preserved.
    No-op if the file or key does not exist.
    """
    p = Path(path)
    if not p.exists():
        return
    with p.open() as f:
        doc = tomlkit.load(f)
    if "phone_public_key" not in doc:
        return
    del doc["phone_public_key"]
    with p.open("w") as f:
        tomlkit.dump(doc, f)
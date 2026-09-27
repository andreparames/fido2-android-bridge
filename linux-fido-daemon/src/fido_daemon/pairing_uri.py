"""Pairing URI formatter/parser per PROTOCOL.md §2.

ABNF:
    fidobridge://pair?channel=<32_lowercase_hex>&key=<base64url_32byte>[&v=1]

`key` is base64url, unpadded; `channel` is 32 lowercase hex chars; `v` is the
optional protocol version (default 1). Rejects missing/duplicate/unknown
params, non-hex channel, wrong key length, and version mismatch.
"""

from __future__ import annotations

import base64
import binascii
import re
from dataclasses import dataclass
from urllib.parse import parse_qsl, urlparse

from fido_daemon.pairing import (
    KEY_BYTES,
    PAIRING_SCHEME,
    PROTOCOL_VERSION,
    Pairing,
    derive_channel_id,
)

_CHANNEL_PATTERN = re.compile(r"^[0-9a-f]{32}$")
_ALLOWED_PARAMS = frozenset({"channel", "key", "v"})


@dataclass(frozen=True)
class ParsedPairing:
    channel_hex: str
    session_key: bytes
    version: int

    @property
    def channel_id(self) -> str:
        return derive_channel_id(self.channel_hex)


def _b64url_encode(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode().rstrip("=")


def _b64url_decode(value: str) -> bytes:
    if not value or "=" in value or len(value) % 4 == 1:
        raise ValueError("base64url key must be canonical unpadded")
    if not re.fullmatch(r"[A-Za-z0-9_-]+", value):
        raise ValueError("invalid base64url key")
    padded = value + "=" * (-len(value) % 4)
    try:
        return base64.b64decode(padded, altchars=b"-_", validate=True)
    except (ValueError, binascii.Error) as exc:
        raise ValueError("invalid base64url key") from exc


def format_pairing_uri(pairing: Pairing) -> str:
    return f"{PAIRING_SCHEME}?channel={pairing.channel_hex}&key={_b64url_encode(pairing.session_key)}"


def parse_pairing_uri(uri: str) -> ParsedPairing:
    parsed = urlparse(uri)
    if parsed.scheme != "fidobridge" or parsed.netloc != "pair":
        raise ValueError("pairing URI must use the fidobridge://pair scheme")
    if parsed.path not in ("", "/"):
        raise ValueError("pairing URI must not carry a path")

    params: dict[str, str] = {}
    for key, value in parse_qsl(parsed.query, keep_blank_values=True):
        if key in params:
            raise ValueError(f"duplicate parameter: {key}")
        if key not in _ALLOWED_PARAMS:
            raise ValueError(f"unknown parameter: {key}")
        params[key] = value

    if "channel" not in params:
        raise ValueError("missing channel parameter")
    if "key" not in params:
        raise ValueError("missing key parameter")

    channel_hex = params["channel"]
    if not _CHANNEL_PATTERN.fullmatch(channel_hex):
        raise ValueError("channel must be exactly 32 lowercase hex chars")

    session_key = _b64url_decode(params["key"])
    if len(session_key) != KEY_BYTES:
        raise ValueError("key must decode to exactly 32 bytes")

    version = PROTOCOL_VERSION
    if "v" in params:
        try:
            version = int(params["v"])
        except ValueError as exc:
            raise ValueError("v must be an integer") from exc
        if version != PROTOCOL_VERSION:
            raise ValueError(f"unsupported protocol version: {version}")

    return ParsedPairing(channel_hex=channel_hex, session_key=session_key, version=version)

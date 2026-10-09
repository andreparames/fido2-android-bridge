"""Pairing URI formatter/parser per PROTOCOL.md §2.

ABNF:
    fidobridge://pair?channel=<32_lowercase_hex>&pubkey=<base64url_32byte>[&token=<jwt>][&v=3]

`pubkey` is the daemon's static X25519 public key (base64url, unpadded);
`channel` is 32 lowercase hex chars; `token` is the optional Centrifugo
connection JWT; `v` is the optional protocol version (default 3). Rejects
missing/duplicate/unknown params, non-hex channel, wrong pubkey length, the
legacy v2 `key` param, and version mismatch.
"""

from __future__ import annotations

import base64
import binascii
import re
from dataclasses import dataclass
from urllib.parse import parse_qsl, urlparse

from fido_daemon.noise import STATIC_KEY_BYTES
from fido_daemon.pairing import (
    PAIRING_SCHEME,
    PROTOCOL_VERSION,
    Pairing,
    derive_channel_id,
)

_CHANNEL_PATTERN = re.compile(r"^[0-9a-f]{32}$")
_ALLOWED_PARAMS = frozenset({"channel", "pubkey", "token", "v"})


@dataclass(frozen=True)
class ParsedPairing:
    channel_hex: str
    static_public: bytes
    version: int
    relay_token: str | None = None

    @property
    def channel_id(self) -> str:
        return derive_channel_id(self.channel_hex)


def b64url_encode(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode().rstrip("=")


def _b64url_decode(value: str) -> bytes:
    if not value or "=" in value or len(value) % 4 == 1:
        raise ValueError("base64url pubkey must be canonical unpadded")
    if not re.fullmatch(r"[A-Za-z0-9_-]+", value):
        raise ValueError("invalid base64url pubkey")
    padded = value + "=" * (-len(value) % 4)
    try:
        return base64.b64decode(padded, altchars=b"-_", validate=True)
    except (ValueError, binascii.Error) as exc:
        raise ValueError("invalid base64url pubkey") from exc


def format_pairing_uri(pairing: Pairing) -> str:
    uri = (
        f"{PAIRING_SCHEME}?channel={pairing.channel_hex}"
        f"&pubkey={b64url_encode(pairing.static_public)}"
    )
    if pairing.relay_token:
        uri += f"&token={pairing.relay_token}"
    return uri


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
    if "pubkey" not in params:
        raise ValueError("missing pubkey parameter")

    channel_hex = params["channel"]
    if not _CHANNEL_PATTERN.fullmatch(channel_hex):
        raise ValueError("channel must be exactly 32 lowercase hex chars")

    static_public = _b64url_decode(params["pubkey"])
    if len(static_public) != STATIC_KEY_BYTES:
        raise ValueError("pubkey must decode to exactly 32 bytes")

    version = PROTOCOL_VERSION
    if "v" in params:
        try:
            version = int(params["v"])
        except ValueError as exc:
            raise ValueError("v must be an integer") from exc
        if version != PROTOCOL_VERSION:
            raise ValueError(f"unsupported protocol version: {version}")

    relay_token = params.get("token") or None

    return ParsedPairing(
        channel_hex=channel_hex,
        static_public=static_public,
        version=version,
        relay_token=relay_token,
    )
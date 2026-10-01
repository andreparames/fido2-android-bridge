"""Pairing: generate the channel id and derive the wire channel id.

Per PROTOCOL.md §2/§3: the raw 128-bit channel id travels only inside the
pairing URI as 32 lowercase hex chars; the value used on the wire (and for
routing) is the derived digest `lowercase hex(SHA-256(channel_hex_utf8)[:16])`.

The pairing URI carries the daemon's static X25519 public key (PROTOCOL.md
§2.4) instead of a symmetric session key; session keys are derived per
connection by the Noise handshake.
"""

from __future__ import annotations

import hashlib
import secrets
from dataclasses import dataclass

from fido_daemon.noise import STATIC_KEY_BYTES
from fido_daemon.protocol import PROTOCOL_VERSION

PAIRING_SCHEME = "fidobridge://pair"

CHANNEL_BYTES = 16


@dataclass(frozen=True)
class Pairing:
    static_public: bytes
    channel_hex: str
    relay_token: str | None = None

    def __post_init__(self) -> None:
        if len(self.static_public) != STATIC_KEY_BYTES:
            raise ValueError("static public key must be 32 bytes")
        if not _is_lower_hex(self.channel_hex, CHANNEL_BYTES):
            raise ValueError("channel must be 16 bytes as 32 lowercase hex chars")


class PairingGenerator:
    @staticmethod
    def generate(static_public: bytes) -> Pairing:
        return Pairing(
            static_public=static_public,
            channel_hex=secrets.token_hex(CHANNEL_BYTES),
        )


def _is_lower_hex(value: str, byte_len: int) -> bool:
    if len(value) != byte_len * 2:
        return False
    try:
        int(value, 16)
    except ValueError:
        return False
    return value == value.lower()


def derive_channel_id(channel_hex: str) -> str:
    """Pinned derivation: first 16 bytes of SHA-256 over the 32-char lowercase
    hex channel string, encoded as 32 lowercase hex chars (PROTOCOL.md §3.2)."""
    digest = hashlib.sha256(channel_hex.encode("utf-8")).digest()
    return digest[:16].hex()
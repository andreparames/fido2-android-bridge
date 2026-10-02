"""Pairing material + deep-link URI for the emulator harness.

One ``channel_hex`` + one daemon static key feed both peers: ``mock_daemon``
via ``FIDO2_CHANNEL_ID``/``FIDO2_STATIC_KEY_PATH``, and the app via the
deep-link pairing URI whose ``pubkey`` is base64url (per PROTOCOL.md §2.1 —
distinct from the standard-b64 wire encoding).
"""

from __future__ import annotations

import secrets
from dataclasses import dataclass
from pathlib import Path

from fido_daemon.noise import StaticKeyStore
from fido_daemon.pairing import Pairing
from fido_daemon.pairing_uri import format_pairing_uri


@dataclass(frozen=True)
class Material:
    channel_hex: str
    static_key_path: str
    pairing_uri: str


def generate_material(static_key_path: str) -> Material:
    """Create a fresh channel + daemon static key and the app's pairing URI."""
    key_path = Path(static_key_path)
    private = StaticKeyStore.generate()
    StaticKeyStore.save(key_path, private)
    public = StaticKeyStore.public_key(private)
    channel_hex = secrets.token_hex(16)
    uri = format_pairing_uri(Pairing(static_public=public, channel_hex=channel_hex))
    return Material(channel_hex=channel_hex, static_key_path=str(key_path), pairing_uri=uri)
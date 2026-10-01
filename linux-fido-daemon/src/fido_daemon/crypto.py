"""Canonical unpadded base64 helpers (PROTOCOL.md §3).

The wire uses standard base64 alphabet, unpadded. The pairing URI uses
base64url, unpadded. Both are strict: no padding, no mixed alphabets.
"""

from __future__ import annotations

import base64
import binascii
import re

_B64_PATTERN = re.compile(r"^[A-Za-z0-9+/]+$")


def b64encode(data: bytes) -> str:
    """Standard base64, canonical unpadded form."""
    return base64.b64encode(data).decode().rstrip("=")


def b64decode(value: str) -> bytes:
    """Strict standard base64: rejects padding and non-canonical input."""
    if "=" in value or not value or len(value) % 4 == 1:
        raise ValueError("base64 payloads must be canonical unpadded")
    if not _B64_PATTERN.fullmatch(value):
        raise ValueError("invalid base64")
    padded = value + "=" * (-len(value) % 4)
    try:
        return base64.b64decode(padded, validate=True)
    except (ValueError, binascii.Error) as exc:
        raise ValueError("invalid base64") from exc
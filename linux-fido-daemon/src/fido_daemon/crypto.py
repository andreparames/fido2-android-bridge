"""E2EE primitives: AES-256-GCM seal/open plus the wire message schema.

Wire schema (agents.md §5):

    { "channel_id": "HASHED_CHANNEL_ID",
      "nonce":      "BASE64_12BYTE_GCM_NONCE",
      "ciphertext": "BASE64_AES_256_GCM_PAYLOAD",
      "tag":        "BASE64_16BYTE_AUTH_TAG" }

A fresh 96-bit nonce is generated per message; never reused. GCM auth
tag failures abort and log a security alert.
"""

from __future__ import annotations

import base64
import binascii
import json
import re
import secrets
from dataclasses import dataclass

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM


class TagMismatchError(Exception):
    """Raised when the GCM authentication tag fails to verify."""


WIRE_KEYS = frozenset({"channel_id", "nonce", "ciphertext", "tag"})
CHANNEL_ID_PATTERN = re.compile(r"^[0-9a-f]{32}$")


@dataclass(frozen=True)
class SecretKey:
    material: bytes

    def __post_init__(self) -> None:
        if len(self.material) != 32:
            raise ValueError("AES-256-GCM requires a 32-byte key")


@dataclass(frozen=True)
class WireMessage:
    channel_id: str
    nonce: bytes
    ciphertext: bytes
    tag: bytes


class AesGcmCipher:
    NONCE_BYTES = 12
    TAG_BYTES = 16

    def __init__(self, key: SecretKey) -> None:
        self._aead = AESGCM(key.material)

    def seal(self, channel_id: str, plaintext: bytes) -> WireMessage:
        nonce = secrets.token_bytes(self.NONCE_BYTES)
        ciphertext = self._aead.encrypt(nonce, plaintext, None)
        tag = ciphertext[-self.TAG_BYTES :]
        return WireMessage(channel_id, nonce, ciphertext[: -self.TAG_BYTES], tag)

    def open(self, message: WireMessage) -> bytes:
        try:
            return self._aead.decrypt(
                message.nonce, message.ciphertext + message.tag, None
            )
        except InvalidTag as exc:
            raise TagMismatchError("GCM authentication tag verification failed") from exc


def b64encode(data: bytes) -> str:
    """Standard base64, canonical unpadded form (PROTOCOL.md §3.1)."""
    return base64.b64encode(data).decode().rstrip("=")


def b64decode(value: str) -> bytes:
    """Strict standard base64: rejects padding and non-canonical input."""
    if "=" in value or not value or len(value) % 4 == 1:
        raise ValueError("base64 payloads must be canonical unpadded")
    if not re.fullmatch(r"[A-Za-z0-9+/]+", value):
        raise ValueError("invalid base64")
    padded = value + "=" * (-len(value) % 4)
    try:
        return base64.b64decode(padded, validate=True)
    except (ValueError, binascii.Error) as exc:
        raise ValueError("invalid base64") from exc


def message_to_json(message: WireMessage) -> str:
    return json.dumps(
        {
            "channel_id": message.channel_id,
            "nonce": b64encode(message.nonce),
            "ciphertext": b64encode(message.ciphertext),
            "tag": b64encode(message.tag),
        }
    )


def message_from_json(raw: str) -> WireMessage:
    try:
        data = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise ValueError("wire message is not valid JSON") from exc
    if not isinstance(data, dict):
        raise ValueError("wire message must be a JSON object")
    if set(data) != WIRE_KEYS:
        raise ValueError(
            "wire message keys must be exactly {channel_id, nonce, ciphertext, tag}"
        )
    channel_id = data["channel_id"]
    if not isinstance(channel_id, str) or not CHANNEL_ID_PATTERN.fullmatch(channel_id):
        raise ValueError("channel_id must be 32 lowercase hex chars")
    nonce = b64decode(data["nonce"])
    ciphertext = b64decode(data["ciphertext"])
    tag = b64decode(data["tag"])
    if len(nonce) != AesGcmCipher.NONCE_BYTES:
        raise ValueError("nonce must be 12 bytes")
    if len(tag) != AesGcmCipher.TAG_BYTES:
        raise ValueError("tag must be 16 bytes")
    return WireMessage(
        channel_id=channel_id,
        nonce=nonce,
        ciphertext=ciphertext,
        tag=tag,
    )
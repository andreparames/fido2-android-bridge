import base64
import json
import re

import pytest

from fido_daemon.crypto import (
    AesGcmCipher,
    SecretKey,
    TagMismatchError,
    message_from_json,
    message_to_json,
)

NONCE_PATTERN = r"^[A-Za-z0-9+/]{16}$"  # 12 bytes, unpadded
TAG_PATTERN = r"^[A-Za-z0-9+/]{22}$"  # 16 bytes, unpadded


def _key() -> SecretKey:
    return SecretKey(bytes(range(32)))


def test_smoke() -> None:
    assert True


def test_seal_open_roundtrip() -> None:
    cipher = AesGcmCipher(_key())
    message = cipher.seal("0123456789abcdef0123456789abcdef", b"hello")
    assert len(message.nonce) == 12
    assert len(message.tag) == 16
    assert cipher.open(message) == b"hello"


def test_unique_nonce_per_message() -> None:
    cipher = AesGcmCipher(_key())
    first = cipher.seal("0123456789abcdef0123456789abcdef", b"hello")
    second = cipher.seal("0123456789abcdef0123456789abcdef", b"hello")
    assert first.nonce != second.nonce


def test_tamper_raises_tag_mismatch() -> None:
    cipher = AesGcmCipher(_key())
    message = cipher.seal("0123456789abcdef0123456789abcdef", b"hello")
    tampered = message.__class__(
        message.channel_id, message.nonce, b"evil", message.tag
    )
    with pytest.raises(TagMismatchError):
        cipher.open(tampered)


def test_wire_json_roundtrip() -> None:
    cipher = AesGcmCipher(_key())
    message = cipher.seal("0123456789abcdef0123456789abcdef", b"payload")
    encoded = message_to_json(message)
    decoded = message_from_json(encoded)
    assert decoded == message


def test_wire_json_schema() -> None:
    cipher = AesGcmCipher(_key())
    data = json.loads(message_to_json(cipher.seal("0123456789abcdef0123456789abcdef", b"x")))
    assert set(data) == {"channel_id", "nonce", "ciphertext", "tag"}
    for field in ("nonce", "ciphertext", "tag"):
        value = data[field]
        decoded = base64.b64decode(value + "=" * (-len(value) % 4))
        assert decoded  # valid base64, decodes to bytes


def test_wire_json_nonce_tag_match_schema_patterns() -> None:
    cipher = AesGcmCipher(_key())
    data = json.loads(message_to_json(cipher.seal("0123456789abcdef0123456789abcdef", b"x")))
    assert re.fullmatch(NONCE_PATTERN, data["nonce"])
    assert re.fullmatch(TAG_PATTERN, data["tag"])
    assert "=" not in data["nonce"]
    assert "=" not in data["tag"]
    assert "=" not in data["ciphertext"]


def test_wire_json_rejects_padded_base64() -> None:
    cipher = AesGcmCipher(_key())
    message = cipher.seal("0123456789abcdef0123456789abcdef", b"x")
    data = json.loads(message_to_json(message))
    padded = data.copy()
    padded["tag"] = padded["tag"] + "="
    with pytest.raises(ValueError):
        message_from_json(json.dumps(padded))


def test_wire_json_rejects_bad_lengths() -> None:
    cipher = AesGcmCipher(_key())
    message = cipher.seal("0123456789abcdef0123456789abcdef", b"x")
    data = json.loads(message_to_json(message))
    short = data.copy()
    short["nonce"] = base64.b64encode(b"\x00" * 4).decode().rstrip("=")
    with pytest.raises(ValueError):
        message_from_json(json.dumps(short))
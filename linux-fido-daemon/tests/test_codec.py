import base64
import json

import pytest

from fido_daemon.crypto import (
    AesGcmCipher,
    SecretKey,
    WireMessage,
    message_from_json,
    message_to_json,
)

CHANNEL_ID = "0123456789abcdef0123456789abcdef"

WIRE_KEYS = {"channel_id", "nonce", "ciphertext", "tag"}


def _message(plaintext: bytes = b"payload") -> WireMessage:
    return AesGcmCipher(SecretKey(bytes(range(32)))).seal(CHANNEL_ID, plaintext)


def test_json_roundtrip_preserves_fields() -> None:
    message = _message()
    decoded = message_from_json(message_to_json(message))
    assert decoded == message


def test_json_keys_are_exactly_wire_keys() -> None:
    data = json.loads(message_to_json(_message()))
    assert set(data) == WIRE_KEYS


def test_rejects_missing_fields() -> None:
    data = json.loads(message_to_json(_message()))
    for field in WIRE_KEYS:
        malformed = {k: v for k, v in data.items() if k != field}
        with pytest.raises(ValueError):
            message_from_json(json.dumps(malformed))


def test_rejects_unknown_keys() -> None:
    data = json.loads(message_to_json(_message()))
    data["extra"] = "boom"
    with pytest.raises(ValueError):
        message_from_json(json.dumps(data))


def test_rejects_invalid_base64_fields() -> None:
    for field in ("nonce", "ciphertext", "tag"):
        data = json.loads(message_to_json(_message()))
        data[field] = "not valid base64!!"
        with pytest.raises(ValueError):
            message_from_json(json.dumps(data))


def test_rejects_wrong_nonce_length() -> None:
    data = json.loads(message_to_json(_message()))
    data["nonce"] = base64.b64encode(b"\x00" * 8).decode().rstrip("=")
    with pytest.raises(ValueError):
        message_from_json(json.dumps(data))


def test_rejects_wrong_tag_length() -> None:
    data = json.loads(message_to_json(_message()))
    data["tag"] = base64.b64encode(b"\x00" * 8).decode().rstrip("=")
    with pytest.raises(ValueError):
        message_from_json(json.dumps(data))


def test_rejects_malformed_channel_id() -> None:
    for bad in ("", "chan-1", "ABC", "0123456789abcdef0123456789abcde",
                "0123456789abcdef0123456789abcdeg", "Z" * 32):
        data = json.loads(message_to_json(_message()))
        data["channel_id"] = bad
        with pytest.raises(ValueError):
            message_from_json(json.dumps(data))


def test_accepts_valid_channel_id() -> None:
    data = json.loads(message_to_json(_message()))
    data["channel_id"] = "deadbeefdeadbeefdeadbeefdeadbeef"
    message = message_from_json(json.dumps(data))
    assert message.channel_id == "deadbeefdeadbeefdeadbeefdeadbeef"

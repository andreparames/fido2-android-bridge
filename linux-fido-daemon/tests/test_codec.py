import base64
import json

import pytest

from fido_daemon.crypto import b64encode
from fido_daemon.noise import (
    KIND_IK1,
    WireEnvelope,
    envelope_from_json,
    envelope_to_json,
)

CHANNEL_ID = "0123456789abcdef0123456789abcdef"
ENVELOPE_KEYS = {"channel_id", "kind", "payload"}


def _message(payload: bytes = b"payload") -> WireEnvelope:
    return WireEnvelope(CHANNEL_ID, KIND_IK1, payload)


def test_json_roundtrip_preserves_fields() -> None:
    message = _message()
    assert envelope_from_json(envelope_to_json(message)) == message


def test_json_keys_are_exactly_envelope_keys() -> None:
    data = json.loads(envelope_to_json(_message()))
    assert set(data) == ENVELOPE_KEYS


def test_rejects_missing_fields() -> None:
    data = json.loads(envelope_to_json(_message()))
    for field in ENVELOPE_KEYS:
        malformed = {k: v for k, v in data.items() if k != field}
        with pytest.raises(ValueError):
            envelope_from_json(json.dumps(malformed))


def test_rejects_unknown_keys() -> None:
    data = json.loads(envelope_to_json(_message()))
    data["extra"] = "boom"
    with pytest.raises(ValueError):
        envelope_from_json(json.dumps(data))


def test_rejects_invalid_base64_payload() -> None:
    data = json.loads(envelope_to_json(_message()))
    data["payload"] = "not valid base64!!"
    with pytest.raises(ValueError):
        envelope_from_json(json.dumps(data))


def test_rejects_padded_base64_payload() -> None:
    data = json.loads(envelope_to_json(_message()))
    data["payload"] = data["payload"] + "="
    with pytest.raises(ValueError):
        envelope_from_json(json.dumps(data))


def test_rejects_malformed_channel_id() -> None:
    for bad in ("", "chan-1", "ABC", "0123456789abcdef0123456789abcde",
                "0123456789abcdef0123456789abcdeg", "Z" * 32):
        data = json.loads(envelope_to_json(_message()))
        data["channel_id"] = bad
        with pytest.raises(ValueError):
            envelope_from_json(json.dumps(data))


def test_accepts_valid_channel_id() -> None:
    data = json.loads(envelope_to_json(_message()))
    data["channel_id"] = "deadbeefdeadbeefdeadbeefdeadbeef"
    message = envelope_from_json(json.dumps(data))
    assert message.channel_id == "deadbeefdeadbeefdeadbeefdeadbeef"


def test_payload_uses_unpadded_standard_base64() -> None:
    data = json.loads(envelope_to_json(_message()))
    value = data["payload"]
    assert "=" not in value
    decoded = base64.b64decode(value + "=" * (-len(value) % 4))
    assert decoded == b"payload"
    assert b64encode(decoded) == value
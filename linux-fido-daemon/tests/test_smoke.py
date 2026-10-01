import json
import re

from fido_daemon.crypto import b64decode, b64encode
from fido_daemon.noise import (
    NoiseInitiatorSession,
    NoiseResponderSession,
    StaticKeyStore,
    WireEnvelope,
    envelope_from_json,
    envelope_to_json,
)

CHANNEL_ID = "0123456789abcdef0123456789abcdef"
CHANNEL_ID_PATTERN = r"^[0-9a-f]{32}$"
DAEMON_PRIVATE = bytes(range(32))
PHONE_PRIVATE = bytes(range(32, 64))


def test_smoke() -> None:
    assert True


def test_ik1_is_96_bytes_and_ik2_is_48_bytes() -> None:
    """Cross-peer fixture: message sizes are pinned (PROTOCOL.md §7)."""
    daemon = NoiseResponderSession(DAEMON_PRIVATE)
    phone = NoiseInitiatorSession(PHONE_PRIVATE, StaticKeyStore.public_key(DAEMON_PRIVATE))
    ik1 = phone.create_ik1()
    ik2 = daemon.receive_ik1(ik1)
    assert len(ik1) == 96
    assert len(ik2) == 48


def test_envelope_schema_patterns() -> None:
    envelope = WireEnvelope(CHANNEL_ID, "data", b"\x00\x01")
    data = json.loads(envelope_to_json(envelope))
    assert re.fullmatch(CHANNEL_ID_PATTERN, data["channel_id"])
    assert data["kind"] in ("ik1", "ik2", "data")
    assert "=" not in data["payload"]
    decoded = b64decode(data["payload"])
    assert envelope_from_json(
        envelope_to_json(WireEnvelope(CHANNEL_ID, data["kind"], decoded))
    ) == envelope


def test_data_payload_is_authenticated_ciphertext() -> None:
    daemon = NoiseResponderSession(DAEMON_PRIVATE)
    phone = NoiseInitiatorSession(PHONE_PRIVATE, StaticKeyStore.public_key(DAEMON_PRIVATE))
    ik1 = phone.create_ik1()
    ik2 = daemon.receive_ik1(ik1)
    phone.receive_ik2(ik2)

    ct = daemon.encrypt(b"secret payload")
    assert b"secret payload" not in ct
    assert phone.decrypt(ct) == b"secret payload"


def test_base64_helpers_are_unpadded() -> None:
    assert "=" not in b64encode(b"payload")
    assert b64decode(b64encode(b"payload")) == b"payload"
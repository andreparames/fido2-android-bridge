"""Noise IK responder/initiator sessions, static key store, wire envelope codec."""

import json
import os
import stat

import pytest

from fido_daemon.noise import (
    KIND_DATA,
    KIND_IK1,
    KIND_IK2,
    NOISE_PROLOGUE,
    NOISE_PROTOCOL_NAME,
    NoiseAuthenticationError,
    NoiseError,
    NoiseInitiatorSession,
    NoiseResponderSession,
    StaticKeyStore,
    WireEnvelope,
    envelope_from_json,
    envelope_to_json,
)

DAEMON_PRIVATE = bytes(range(32))
PHONE_PRIVATE = bytes(range(32, 64))
CHANNEL_ID = "0123456789abcdef0123456789abcdef"
ENVELOPE_KEYS = {"channel_id", "kind", "payload"}


def _daemon_pub(private: bytes = DAEMON_PRIVATE) -> bytes:
    return StaticKeyStore.public_key(private)


def _handshake_pair():
    daemon = NoiseResponderSession(DAEMON_PRIVATE)
    phone = NoiseInitiatorSession(PHONE_PRIVATE, _daemon_pub())
    ik1 = phone.create_ik1()
    ik2 = daemon.receive_ik1(ik1)
    phone.receive_ik2(ik2)
    return daemon, phone


# --- StaticKeyStore ---


def test_generate_returns_32_bytes() -> None:
    assert len(StaticKeyStore.generate()) == 32


def test_public_key_is_32_bytes_and_deterministic() -> None:
    pub = StaticKeyStore.public_key(DAEMON_PRIVATE)
    assert len(pub) == 32
    assert pub == StaticKeyStore.public_key(DAEMON_PRIVATE)


def test_save_load_roundtrip(tmp_path) -> None:
    path = tmp_path / "static_key.pem"
    StaticKeyStore.save(path, DAEMON_PRIVATE)
    assert StaticKeyStore.load(path) == DAEMON_PRIVATE


def test_save_sets_0600_permissions(tmp_path) -> None:
    path = tmp_path / "static_key.pem"
    StaticKeyStore.save(path, DAEMON_PRIVATE)
    assert stat.S_IMODE(os.stat(path).st_mode) == 0o600


def test_load_or_create_persists(tmp_path) -> None:
    path = tmp_path / "static_key.pem"
    assert StaticKeyStore.load_or_create(path) == StaticKeyStore.load_or_create(path)


def test_load_or_create_creates_missing_nested(tmp_path) -> None:
    path = tmp_path / "sub" / "static_key.pem"
    key = StaticKeyStore.load_or_create(path)
    assert path.is_file()
    assert len(key) == 32


def test_load_rejects_wrong_length(tmp_path) -> None:
    path = tmp_path / "static_key.pem"
    path.write_bytes(b"too short")
    with pytest.raises(ValueError):
        StaticKeyStore.load(path)


# --- Sessions ---


def test_handshake_roundtrip_transport() -> None:
    daemon, phone = _handshake_pair()
    assert daemon.decrypt(phone.encrypt(b"request")) == b"request"
    assert phone.decrypt(daemon.encrypt(b"response")) == b"response"


def test_responder_learns_initiator_static_key() -> None:
    daemon, _ = _handshake_pair()
    assert daemon.remote_static == _daemon_pub(PHONE_PRIVATE)


def test_tampered_ciphertext_raises() -> None:
    daemon, phone = _handshake_pair()
    ct = phone.encrypt(b"payload")
    with pytest.raises(NoiseAuthenticationError):
        daemon.decrypt(b"\xff" + ct[1:])


def test_replay_raises() -> None:
    daemon, phone = _handshake_pair()
    ct = phone.encrypt(b"payload")
    daemon.decrypt(ct)
    with pytest.raises(NoiseAuthenticationError):
        daemon.decrypt(ct)


def test_wrong_prologue_handshake_fails() -> None:
    daemon = NoiseResponderSession(DAEMON_PRIVATE, prologue=b"OTHER")
    phone = NoiseInitiatorSession(PHONE_PRIVATE, _daemon_pub())
    ik1 = phone.create_ik1()
    with pytest.raises(NoiseAuthenticationError):
        daemon.receive_ik1(ik1)


def test_wrong_remote_static_handshake_fails() -> None:
    phone = NoiseInitiatorSession(PHONE_PRIVATE, _daemon_pub(bytes(range(64, 96))))
    daemon = NoiseResponderSession(DAEMON_PRIVATE)
    ik1 = phone.create_ik1()
    with pytest.raises(NoiseAuthenticationError):
        daemon.receive_ik1(ik1)


def test_responder_rejects_bad_static_length() -> None:
    with pytest.raises(ValueError):
        NoiseResponderSession(b"short")


def test_initiator_rejects_bad_static_lengths() -> None:
    with pytest.raises(ValueError):
        NoiseInitiatorSession(b"short", _daemon_pub())
    with pytest.raises(ValueError):
        NoiseInitiatorSession(PHONE_PRIVATE, b"short")


def test_remote_static_unavailable_before_handshake() -> None:
    daemon = NoiseResponderSession(DAEMON_PRIVATE)
    with pytest.raises(NoiseError):
        _ = daemon.remote_static


def test_initiator_transport_before_handshake_raises() -> None:
    phone = NoiseInitiatorSession(PHONE_PRIVATE, _daemon_pub())
    with pytest.raises(NoiseError):
        phone.encrypt(b"nope")


def test_protocol_constants() -> None:
    assert NOISE_PROTOCOL_NAME == "Noise_IK_25519_AESGCM_SHA256"
    assert NOISE_PROLOGUE == "FIDO2_BRIDGE_V2"
    assert {KIND_IK1, KIND_IK2, KIND_DATA} == {"ik1", "ik2", "data"}


# --- WireEnvelope codec ---


def _envelope(payload: bytes = b"\x01\x02\x03") -> WireEnvelope:
    return WireEnvelope(CHANNEL_ID, KIND_IK1, payload)


def test_envelope_json_roundtrip() -> None:
    envelope = _envelope()
    assert envelope_from_json(envelope_to_json(envelope)) == envelope


def test_envelope_json_keys_are_exactly_three() -> None:
    assert set(json.loads(envelope_to_json(_envelope()))) == ENVELOPE_KEYS


def test_envelope_rejects_unknown_kind() -> None:
    data = json.loads(envelope_to_json(_envelope()))
    data["kind"] = "bogus"
    with pytest.raises(ValueError):
        envelope_from_json(json.dumps(data))


def test_envelope_rejects_bad_channel() -> None:
    for bad in ("", "chan-1", "ABC", "0123456789abcdef0123456789abcdeg", "Z" * 32):
        data = json.loads(envelope_to_json(_envelope()))
        data["channel_id"] = bad
        with pytest.raises(ValueError):
            envelope_from_json(json.dumps(data))


def test_envelope_rejects_bad_payload_base64() -> None:
    data = json.loads(envelope_to_json(_envelope()))
    data["payload"] = "not base64!!"
    with pytest.raises(ValueError):
        envelope_from_json(json.dumps(data))


def test_envelope_rejects_padded_payload() -> None:
    data = json.loads(envelope_to_json(_envelope()))
    data["payload"] = data["payload"] + "="
    with pytest.raises(ValueError):
        envelope_from_json(json.dumps(data))


def test_envelope_rejects_missing_and_extra_keys() -> None:
    data = json.loads(envelope_to_json(_envelope()))
    data["extra"] = "x"
    with pytest.raises(ValueError):
        envelope_from_json(json.dumps(data))
    del data["extra"]
    del data["payload"]
    with pytest.raises(ValueError):
        envelope_from_json(json.dumps(data))


def test_envelope_rejects_non_object() -> None:
    with pytest.raises(ValueError):
        envelope_from_json("[1,2,3]")
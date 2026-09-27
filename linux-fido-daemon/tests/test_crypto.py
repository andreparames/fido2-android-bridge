import pytest

from fido_daemon.crypto import (
    AesGcmCipher,
    SecretKey,
    TagMismatchError,
    WireMessage,
)

CHANNEL_ID = "0123456789abcdef0123456789abcdef"


def _key(seed: int = 0) -> SecretKey:
    return SecretKey(bytes([seed & 0xFF]) * 32)


def test_secret_key_rejects_wrong_length() -> None:
    with pytest.raises(ValueError):
        SecretKey(b"short")
    with pytest.raises(ValueError):
        SecretKey(b"x" * 33)


def test_seal_emits_nonce_and_tag_lengths() -> None:
    cipher = AesGcmCipher(_key())
    message = cipher.seal(CHANNEL_ID, b"payload")
    assert len(message.nonce) == AesGcmCipher.NONCE_BYTES == 12
    assert len(message.tag) == AesGcmCipher.TAG_BYTES == 16


def test_roundtrip() -> None:
    cipher = AesGcmCipher(_key())
    for plaintext in (b"", b"hello", bytes(range(256))):
        assert cipher.open(cipher.seal(CHANNEL_ID, plaintext)) == plaintext


def test_two_seals_differ() -> None:
    cipher = AesGcmCipher(_key())
    first = cipher.seal(CHANNEL_ID, b"same")
    second = cipher.seal(CHANNEL_ID, b"same")
    assert first.nonce != second.nonce
    assert first.ciphertext != second.ciphertext


def test_tampered_ciphertext_raises() -> None:
    cipher = AesGcmCipher(_key())
    message = cipher.seal(CHANNEL_ID, b"payload")
    tampered = WireMessage(message.channel_id, message.nonce, b"evil", message.tag)
    with pytest.raises(TagMismatchError):
        cipher.open(tampered)


def test_tampered_tag_raises() -> None:
    cipher = AesGcmCipher(_key())
    message = cipher.seal(CHANNEL_ID, b"payload")
    bad_tag = bytes([message.tag[0] ^ 0x01]) + message.tag[1:]
    tampered = WireMessage(message.channel_id, message.nonce, message.ciphertext, bad_tag)
    with pytest.raises(TagMismatchError):
        cipher.open(tampered)


def test_wrong_key_raises_tag_mismatch() -> None:
    seal_cipher = AesGcmCipher(_key(seed=1))
    open_cipher = AesGcmCipher(_key(seed=2))
    message = seal_cipher.seal(CHANNEL_ID, b"payload")
    with pytest.raises(TagMismatchError):
        open_cipher.open(message)

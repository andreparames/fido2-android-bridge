"""Canonical unpadded base64 helpers (PROTOCOL.md §3)."""

import pytest

from fido_daemon.crypto import b64decode, b64encode


def test_roundtrip() -> None:
    for data in (b"a", b"ab", b"abc", b"abcd", bytes(range(256))):
        assert b64decode(b64encode(data)) == data


def test_emit_is_unpadded() -> None:
    assert "=" not in b64encode(b"a")
    assert "=" not in b64encode(b"abcd")


def test_rejects_padded_input() -> None:
    with pytest.raises(ValueError):
        b64decode("YQ==")


def test_rejects_invalid_characters() -> None:
    with pytest.raises(ValueError):
        b64decode("YQ!=~")
    with pytest.raises(ValueError):
        b64decode("not-valid")


def test_rejects_empty() -> None:
    with pytest.raises(ValueError):
        b64decode("")


def test_rejects_length_mod_4_equal_1() -> None:
    with pytest.raises(ValueError):
        b64decode("AAAAA")
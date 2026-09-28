import base64
import re
from urllib.parse import parse_qsl, urlparse

import pytest

from fido_daemon.cli import main
from fido_daemon.pairing import (
    Pairing,
    PairingGenerator,
    derive_channel_id,
)
from fido_daemon.pairing_uri import (
    ParsedPairing,
    format_pairing_uri,
    parse_pairing_uri,
)

CHANNEL_HEX = "0123456789abcdef0123456789abcdef"
KEY = bytes(range(32))
KEY_B64URL = base64.urlsafe_b64encode(KEY).decode().rstrip("=")


def _pairing() -> Pairing:
    return Pairing(session_key=KEY, channel_hex=CHANNEL_HEX)


def test_generate_produces_32byte_key_and_16byte_channel() -> None:
    pairing = PairingGenerator.generate()
    assert len(pairing.session_key) == 32
    assert re.fullmatch(r"[0-9a-f]{32}", pairing.channel_hex)


def test_generate_is_random() -> None:
    first = PairingGenerator.generate()
    second = PairingGenerator.generate()
    assert first.session_key != second.session_key
    assert first.channel_hex != second.channel_hex


def test_format_pairing_uri_shape() -> None:
    uri = format_pairing_uri(_pairing())
    parsed = urlparse(uri)
    assert parsed.scheme == "fidobridge"
    assert parsed.netloc == "pair"
    params = dict(parse_qsl(parsed.query))
    assert params["channel"] == CHANNEL_HEX
    assert "=" not in params["key"]


def test_format_pairing_uri_key_decodes_to_32_bytes() -> None:
    uri = format_pairing_uri(_pairing())
    key_b64url = dict(parse_qsl(urlparse(uri).query))["key"]
    assert re.fullmatch(r"[A-Za-z0-9_-]+", key_b64url)
    decoded = base64.urlsafe_b64decode(key_b64url + "=" * (-len(key_b64url) % 4))
    assert decoded == KEY
    assert len(decoded) == 32


def test_derive_channel_id_is_pinned() -> None:
    assert derive_channel_id(CHANNEL_HEX) == "3eb1bd439947eb762998e566ccc2e099"


def test_parse_pairing_uri_roundtrip() -> None:
    parsed = parse_pairing_uri(format_pairing_uri(_pairing()))
    assert parsed.channel_hex == CHANNEL_HEX
    assert parsed.session_key == KEY
    assert parsed.version == 2
    assert parsed.channel_id == derive_channel_id(CHANNEL_HEX)
    assert parsed.relay_token is None


def test_parse_rejects_wrong_scheme() -> None:
    with pytest.raises(ValueError):
        parse_pairing_uri("https://pair?channel=" + CHANNEL_HEX + "&key=" + KEY_B64URL)


def test_parse_rejects_missing_params() -> None:
    with pytest.raises(ValueError):
        parse_pairing_uri("fidobridge://pair?channel=" + CHANNEL_HEX)
    with pytest.raises(ValueError):
        parse_pairing_uri("fidobridge://pair?key=" + KEY_B64URL)


def test_parse_rejects_duplicate_params() -> None:
    uri = "fidobridge://pair?channel=" + CHANNEL_HEX + "&channel=" + CHANNEL_HEX + "&key=" + KEY_B64URL
    with pytest.raises(ValueError):
        parse_pairing_uri(uri)


def test_parse_rejects_unknown_params() -> None:
    uri = "fidobridge://pair?channel=" + CHANNEL_HEX + "&key=" + KEY_B64URL + "&extra=1"
    with pytest.raises(ValueError):
        parse_pairing_uri(uri)


def test_parse_rejects_bad_channel() -> None:
    for bad in ("ABC", "0123456789ABCDEF0123456789ABCDEF", "g" * 32, "12345"):
        uri = "fidobridge://pair?channel=" + bad + "&key=" + KEY_B64URL
        with pytest.raises(ValueError):
            parse_pairing_uri(uri)


def test_parse_rejects_bad_key() -> None:
    for bad in ("abc", "abc=", "abc!", "a" * 50):
        uri = "fidobridge://pair?channel=" + CHANNEL_HEX + "&key=" + bad
        with pytest.raises(ValueError):
            parse_pairing_uri(uri)


def test_parse_rejects_version_mismatch() -> None:
    uri = "fidobridge://pair?channel=" + CHANNEL_HEX + "&key=" + KEY_B64URL + "&v=99"
    with pytest.raises(ValueError):
        parse_pairing_uri(uri)


def test_parse_accepts_default_version() -> None:
    uri = "fidobridge://pair?channel=" + CHANNEL_HEX + "&key=" + KEY_B64URL
    parsed = parse_pairing_uri(uri)
    assert parsed.version == 2


def test_pair_cli_prints_valid_uri(capsys: pytest.CaptureFixture) -> None:
    assert main(["pair"]) == 0
    out = capsys.readouterr().out.strip()
    assert isinstance(parse_pairing_uri(out), ParsedPairing)


def test_format_pairing_uri_includes_token() -> None:
    token = "eyJhbGciOiJIUzI1NiJ9.test.signature"
    pairing = Pairing(session_key=KEY, channel_hex=CHANNEL_HEX, relay_token=token)
    uri = format_pairing_uri(pairing)
    params = dict(parse_qsl(urlparse(uri).query))
    assert params["token"] == token


def test_format_pairing_uri_omits_token_when_none() -> None:
    uri = format_pairing_uri(_pairing())
    params = dict(parse_qsl(urlparse(uri).query))
    assert "token" not in params


def test_parse_pairing_uri_roundtrip_with_token() -> None:
    token = "eyJhbGciOiJIUzI1NiJ9.test.signature"
    pairing = Pairing(session_key=KEY, channel_hex=CHANNEL_HEX, relay_token=token)
    parsed = parse_pairing_uri(format_pairing_uri(pairing))
    assert parsed.relay_token == token
    assert parsed.channel_hex == CHANNEL_HEX
    assert parsed.session_key == KEY


def test_parse_pairing_uri_token_optional() -> None:
    uri = "fidobridge://pair?channel=" + CHANNEL_HEX + "&key=" + KEY_B64URL
    parsed = parse_pairing_uri(uri)
    assert parsed.relay_token is None


def test_parse_pairing_uri_rejects_empty_token() -> None:
    uri = "fidobridge://pair?channel=" + CHANNEL_HEX + "&key=" + KEY_B64URL + "&token="
    parsed = parse_pairing_uri(uri)
    # Empty token is treated as absent (no token).
    assert parsed.relay_token is None

import base64
import re
from urllib.parse import parse_qsl, urlparse

import pytest

from fido_daemon.cli import main
from fido_daemon.ctrl import ControlServer
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
STATIC_PUB = bytes(range(32))
STATIC_PUB_B64URL = base64.urlsafe_b64encode(STATIC_PUB).decode().rstrip("=")


def _pairing() -> Pairing:
    return Pairing(static_public=STATIC_PUB, channel_hex=CHANNEL_HEX)


def test_generate_produces_32byte_pubkey_and_16byte_channel() -> None:
    pairing = PairingGenerator.generate(STATIC_PUB)
    assert len(pairing.static_public) == 32
    assert re.fullmatch(r"[0-9a-f]{32}", pairing.channel_hex)


def test_generate_randomizes_channel_only() -> None:
    first = PairingGenerator.generate(STATIC_PUB)
    second = PairingGenerator.generate(STATIC_PUB)
    assert first.channel_hex != second.channel_hex
    assert first.static_public == second.static_public == STATIC_PUB


def test_format_pairing_uri_shape() -> None:
    uri = format_pairing_uri(_pairing())
    parsed = urlparse(uri)
    assert parsed.scheme == "fidobridge"
    assert parsed.netloc == "pair"
    params = dict(parse_qsl(parsed.query))
    assert params["channel"] == CHANNEL_HEX
    assert params["pubkey"] == STATIC_PUB_B64URL
    assert "key" not in params


def test_format_pairing_uri_pubkey_decodes_to_32_bytes() -> None:
    uri = format_pairing_uri(_pairing())
    pub_b64url = dict(parse_qsl(urlparse(uri).query))["pubkey"]
    assert re.fullmatch(r"[A-Za-z0-9_-]+", pub_b64url)
    decoded = base64.urlsafe_b64decode(pub_b64url + "=" * (-len(pub_b64url) % 4))
    assert decoded == STATIC_PUB
    assert len(decoded) == 32


def test_derive_channel_id_is_pinned() -> None:
    assert derive_channel_id(CHANNEL_HEX) == "3eb1bd439947eb762998e566ccc2e099"


def test_parse_pairing_uri_roundtrip() -> None:
    parsed = parse_pairing_uri(format_pairing_uri(_pairing()))
    assert parsed.channel_hex == CHANNEL_HEX
    assert parsed.static_public == STATIC_PUB
    assert parsed.version == 3
    assert parsed.channel_id == derive_channel_id(CHANNEL_HEX)
    assert parsed.relay_token is None


def test_parse_rejects_wrong_scheme() -> None:
    with pytest.raises(ValueError):
        parse_pairing_uri("https://pair?channel=" + CHANNEL_HEX + "&pubkey=" + STATIC_PUB_B64URL)


def test_parse_rejects_missing_params() -> None:
    with pytest.raises(ValueError):
        parse_pairing_uri("fidobridge://pair?channel=" + CHANNEL_HEX)
    with pytest.raises(ValueError):
        parse_pairing_uri("fidobridge://pair?pubkey=" + STATIC_PUB_B64URL)


def test_parse_rejects_duplicate_params() -> None:
    uri = (
        "fidobridge://pair?channel="
        + CHANNEL_HEX
        + "&channel="
        + CHANNEL_HEX
        + "&pubkey="
        + STATIC_PUB_B64URL
    )
    with pytest.raises(ValueError):
        parse_pairing_uri(uri)


def test_parse_rejects_unknown_params() -> None:
    uri = "fidobridge://pair?channel=" + CHANNEL_HEX + "&pubkey=" + STATIC_PUB_B64URL + "&extra=1"
    with pytest.raises(ValueError):
        parse_pairing_uri(uri)


def test_parse_rejects_legacy_key_param() -> None:
    # v2 URIs carrying `key=` are not accepted in v3 (no v2 compat).
    uri = "fidobridge://pair?channel=" + CHANNEL_HEX + "&key=" + STATIC_PUB_B64URL
    with pytest.raises(ValueError):
        parse_pairing_uri(uri)


def test_parse_rejects_bad_channel() -> None:
    for bad in ("ABC", "0123456789ABCDEF0123456789ABCDEF", "g" * 32, "12345"):
        uri = "fidobridge://pair?channel=" + bad + "&pubkey=" + STATIC_PUB_B64URL
        with pytest.raises(ValueError):
            parse_pairing_uri(uri)


def test_parse_rejects_bad_pubkey() -> None:
    for bad in ("abc", "abc=", "abc!", "a" * 50):
        uri = "fidobridge://pair?channel=" + CHANNEL_HEX + "&pubkey=" + bad
        with pytest.raises(ValueError):
            parse_pairing_uri(uri)


def test_parse_rejects_version_mismatch() -> None:
    uri = "fidobridge://pair?channel=" + CHANNEL_HEX + "&pubkey=" + STATIC_PUB_B64URL + "&v=99"
    with pytest.raises(ValueError):
        parse_pairing_uri(uri)


def test_parse_accepts_default_version() -> None:
    uri = "fidobridge://pair?channel=" + CHANNEL_HEX + "&pubkey=" + STATIC_PUB_B64URL
    assert parse_pairing_uri(uri).version == 3


def test_pair_cli_prints_valid_uri(
    monkeypatch: pytest.MonkeyPatch, tmp_path, capsys: pytest.CaptureFixture
) -> None:
    """The pair CLI builds a parseable URI from the daemon's control response."""
    import asyncio

    control_socket = str(tmp_path / "fido2-ctrl.sock")

    async def dispatch(request: dict) -> dict:
        assert request == {"cmd": "pair"}
        return {"channel": CHANNEL_HEX, "pubkey": STATIC_PUB_B64URL}

    async def run_pair() -> int:
        server = ControlServer(control_socket, dispatch)
        await server.start()
        try:
            return await asyncio.to_thread(main, ["pair", "--no-qr"])
        finally:
            await server.close()

    monkeypatch.setenv("FIDO2_CONTROL_SOCKET", control_socket)
    monkeypatch.setenv("FIDO2_RELAY_URL", "ws://localhost:8000/connection/websocket")
    monkeypatch.setenv("FIDO2_RELAY_TOKEN", "")
    assert asyncio.run(run_pair()) == 0
    out = capsys.readouterr().out
    uri = out.splitlines()[0].strip()
    assert isinstance(parse_pairing_uri(uri), ParsedPairing)
    assert "pubkey=" in uri
    params = dict(parse_qsl(urlparse(uri).query))
    assert "key" not in params


def test_format_pairing_uri_includes_token() -> None:
    token = "eyJhbGciOiJIUzI1NiJ9.test.signature"
    pairing = Pairing(static_public=STATIC_PUB, channel_hex=CHANNEL_HEX, relay_token=token)
    uri = format_pairing_uri(pairing)
    params = dict(parse_qsl(urlparse(uri).query))
    assert params["token"] == token


def test_format_pairing_uri_omits_token_when_none() -> None:
    uri = format_pairing_uri(_pairing())
    params = dict(parse_qsl(urlparse(uri).query))
    assert "token" not in params


def test_parse_pairing_uri_roundtrip_with_token() -> None:
    token = "eyJhbGciOiJIUzI1NiJ9.test.signature"
    pairing = Pairing(static_public=STATIC_PUB, channel_hex=CHANNEL_HEX, relay_token=token)
    parsed = parse_pairing_uri(format_pairing_uri(pairing))
    assert parsed.relay_token == token
    assert parsed.channel_hex == CHANNEL_HEX
    assert parsed.static_public == STATIC_PUB


def test_parse_pairing_uri_token_optional() -> None:
    uri = "fidobridge://pair?channel=" + CHANNEL_HEX + "&pubkey=" + STATIC_PUB_B64URL
    assert parse_pairing_uri(uri).relay_token is None


def test_parse_pairing_uri_rejects_empty_token() -> None:
    uri = "fidobridge://pair?channel=" + CHANNEL_HEX + "&pubkey=" + STATIC_PUB_B64URL + "&token="
    assert parse_pairing_uri(uri).relay_token is None


def test_pairing_rejects_wrong_pubkey_length() -> None:
    with pytest.raises(ValueError):
        Pairing(static_public=b"short", channel_hex=CHANNEL_HEX)
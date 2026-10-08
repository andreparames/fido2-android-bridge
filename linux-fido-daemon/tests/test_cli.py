"""CLI pairing tests (managed-relay plan, phase B).

In classic mode the pairing QR carries the Centrifugo relay token. In managed
mode (relay host ``relay.gatebridge.app``) the token must not appear in the QR
or the recorded config: the subscribe proxy is the gate, so no token is handed
to the Android client.
"""

import pytest

from fido_daemon.cli import _run_pair
from fido_daemon.config import load_config_file

TOKEN = "abc123"

MANAGED_URL = "wss://relay.gatebridge.app/connection/websocket"
CLASSIC_URL = "ws://localhost:8000/connection/websocket"


def test_classic_uri_embeds_token(monkeypatch, tmp_path, capsys) -> None:
    monkeypatch.setenv("FIDO2_RELAY_URL", CLASSIC_URL)
    monkeypatch.setenv("FIDO2_RELAY_TOKEN", TOKEN)
    monkeypatch.setenv("FIDO2_STATIC_KEY_PATH", str(tmp_path / "static_key.pem"))

    _run_pair(no_qr=True, config_path=str(tmp_path / "pairing.toml"))
    uri = capsys.readouterr().out.strip()

    assert uri.startswith("fidobridge://pair?channel=")
    assert f"&token={TOKEN}" in uri


def test_classic_config_records_token(monkeypatch, tmp_path, capsys) -> None:
    monkeypatch.setenv("FIDO2_RELAY_URL", CLASSIC_URL)
    monkeypatch.setenv("FIDO2_RELAY_TOKEN", TOKEN)
    monkeypatch.setenv("FIDO2_STATIC_KEY_PATH", str(tmp_path / "static_key.pem"))
    config_path = str(tmp_path / "pairing.toml")

    _run_pair(no_qr=True, config_path=config_path)
    doc = load_config_file(config_path)

    assert doc.get("relay_token") == TOKEN
    assert "channel_id" in doc


def test_managed_uri_omits_token(monkeypatch, tmp_path, capsys) -> None:
    monkeypatch.setenv("FIDO2_RELAY_URL", MANAGED_URL)
    monkeypatch.setenv("FIDO2_RELAY_TOKEN", TOKEN)
    monkeypatch.setenv("FIDO2_STATIC_KEY_PATH", str(tmp_path / "static_key.pem"))

    _run_pair(no_qr=True, config_path=str(tmp_path / "pairing.toml"))
    uri = capsys.readouterr().out.strip()

    assert uri.startswith("fidobridge://pair?channel=")
    assert "&pubkey=" in uri
    assert "&token=" not in uri


def test_managed_config_omits_token(monkeypatch, tmp_path, capsys) -> None:
    monkeypatch.setenv("FIDO2_RELAY_URL", MANAGED_URL)
    monkeypatch.setenv("FIDO2_RELAY_TOKEN", TOKEN)
    monkeypatch.setenv("FIDO2_STATIC_KEY_PATH", str(tmp_path / "static_key.pem"))
    config_path = str(tmp_path / "pairing.toml")

    _run_pair(no_qr=True, config_path=config_path)
    doc = load_config_file(config_path)

    assert doc.get("relay_token") is None
    assert "channel_id" in doc


def test_managed_uri_works_without_configured_token(monkeypatch, tmp_path, capsys) -> None:
    monkeypatch.setenv("FIDO2_RELAY_URL", MANAGED_URL)
    monkeypatch.delenv("FIDO2_RELAY_TOKEN", raising=False)
    monkeypatch.setenv("FIDO2_STATIC_KEY_PATH", str(tmp_path / "static_key.pem"))

    _run_pair(no_qr=True, config_path=str(tmp_path / "pairing.toml"))
    uri = capsys.readouterr().out.strip()

    assert uri.startswith("fidobridge://pair?channel=")
    assert "&token=" not in uri
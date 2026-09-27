import os

import pytest

from fido_daemon.config import Config
from fido_daemon.cli import main


def _clear_config_env(monkeypatch: pytest.MonkeyPatch) -> None:
    for name in (
        "FIDO2_REMOTE_SOCKET",
        "FIDO2_RELAY_URL",
        "FIDO2_CHANNEL_ID",
        "FIDO2_SESSION_KEY_B64",
        "FIDO2_RELAY_TOKEN",
        "FIDO2_REQUEST_TIMEOUT",
    ):
        monkeypatch.delenv(name, raising=False)


def test_default_socket_path_uses_uid(monkeypatch: pytest.MonkeyPatch) -> None:
    _clear_config_env(monkeypatch)
    config = Config.from_env()
    assert config.socket_path == f"/run/user/{os.getuid()}/fido2-bridge.sock"


def test_remote_socket_overrides_path(monkeypatch: pytest.MonkeyPatch) -> None:
    _clear_config_env(monkeypatch)
    monkeypatch.setenv("FIDO2_REMOTE_SOCKET", "/tmp/custom.sock")
    config = Config.from_env()
    assert config.socket_path == "/tmp/custom.sock"


def test_main_exits_2_without_session_key(
    monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
) -> None:
    _clear_config_env(monkeypatch)
    assert main([]) == 2
    assert "FIDO2_SESSION_KEY_B64" in caplog.text


def test_invalid_request_timeout_raises(monkeypatch: pytest.MonkeyPatch) -> None:
    _clear_config_env(monkeypatch)
    monkeypatch.setenv("FIDO2_REQUEST_TIMEOUT", "not-a-number")
    with pytest.raises(ValueError):
        Config.from_env()


def test_relay_token_defaults_empty(monkeypatch: pytest.MonkeyPatch) -> None:
    _clear_config_env(monkeypatch)
    assert Config.from_env().relay_token == ""


def test_relay_token_reads_env(monkeypatch: pytest.MonkeyPatch) -> None:
    _clear_config_env(monkeypatch)
    monkeypatch.setenv("FIDO2_RELAY_TOKEN", "jwt-token-value")
    assert Config.from_env().relay_token == "jwt-token-value"

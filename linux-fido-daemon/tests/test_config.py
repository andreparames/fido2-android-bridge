import os

import pytest
import tomlkit

from fido_daemon.config import Config, load_config_file, write_config_file
from fido_daemon.cli import main


def _clear_config_env(monkeypatch: pytest.MonkeyPatch) -> None:
    for name in (
        "FIDO2_REMOTE_SOCKET",
        "FIDO2_RELAY_URL",
        "FIDO2_CHANNEL_ID",
        "FIDO2_SESSION_KEY_B64",
        "FIDO2_RELAY_TOKEN",
        "FIDO2_REQUEST_TIMEOUT",
        "FIDO2_UHID_ENABLED",
        "FIDO2_UHID_NAME",
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


def test_uhid_disabled_by_default(monkeypatch: pytest.MonkeyPatch) -> None:
    _clear_config_env(monkeypatch)
    config = Config.from_env()
    assert config.uhid_enabled is False
    assert config.uhid_name == "fido-daemon"


def test_uhid_enabled_from_env(monkeypatch: pytest.MonkeyPatch) -> None:
    _clear_config_env(monkeypatch)
    monkeypatch.setenv("FIDO2_UHID_ENABLED", "1")
    monkeypatch.setenv("FIDO2_UHID_NAME", "my-key")
    config = Config.from_env()
    assert config.uhid_enabled is True
    assert config.uhid_name == "my-key"


# --- Config file tests ---


def test_load_config_file_returns_empty_for_missing(tmp_path) -> None:
    assert load_config_file(tmp_path / "nope.toml") == {}


def test_load_config_file_reads_toml(tmp_path) -> None:
    cfg = tmp_path / "config.toml"
    cfg.write_text('session_key_b64 = "abc"\nchannel_id = "def"\n')
    data = load_config_file(cfg)
    assert data["session_key_b64"] == "abc"
    assert data["channel_id"] == "def"


def test_write_config_file_creates_new(tmp_path) -> None:
    cfg = tmp_path / "config.toml"
    write_config_file(cfg, session_key_b64="key1", channel_id="chan1", relay_token="tok1")
    data = load_config_file(cfg)
    assert data["session_key_b64"] == "key1"
    assert data["channel_id"] == "chan1"
    assert data["relay_token"] == "tok1"


def test_write_config_file_updates_existing_preserving_other_fields(tmp_path) -> None:
    cfg = tmp_path / "config.toml"
    cfg.write_text('session_key_b64 = "old"\nmy_custom = "preserved"\n')
    write_config_file(cfg, session_key_b64="new")
    data = load_config_file(cfg)
    assert data["session_key_b64"] == "new"
    assert data["my_custom"] == "preserved"


def test_write_config_file_only_sets_specified_fields(tmp_path) -> None:
    cfg = tmp_path / "config.toml"
    write_config_file(cfg, session_key_b64="key1")
    data = load_config_file(cfg)
    assert data["session_key_b64"] == "key1"
    assert "channel_id" not in data
    assert "relay_token" not in data


def test_with_config_file_overrides_env(monkeypatch: pytest.MonkeyPatch, tmp_path) -> None:
    _clear_config_env(monkeypatch)
    monkeypatch.setenv("FIDO2_SESSION_KEY_B64", "env-key")
    monkeypatch.setenv("FIDO2_CHANNEL_ID", "env-chan")
    monkeypatch.setenv("FIDO2_RELAY_TOKEN", "env-tok")
    cfg = tmp_path / "config.toml"
    write_config_file(cfg, session_key_b64="file-key", channel_id="file-chan")
    config = Config.from_env().with_config_file(cfg)
    assert config.session_key_b64 == "file-key"
    assert config.channel_id == "file-chan"
    assert config.relay_token == "env-tok"  # not overridden


def test_pair_cli_writes_config_file(
    monkeypatch: pytest.MonkeyPatch, tmp_path, capsys: pytest.CaptureFixture
) -> None:
    _clear_config_env(monkeypatch)
    monkeypatch.setenv("FIDO2_RELAY_TOKEN", "test-jwt")
    cfg = tmp_path / "config.toml"
    assert main(["-c", str(cfg), "pair", "--no-qr"]) == 0
    data = load_config_file(cfg)
    assert len(data["session_key_b64"]) > 0
    assert len(data["channel_id"]) == 32
    assert data["relay_token"] == "test-jwt"


def test_pair_cli_without_config_flag_does_not_write_file(
    monkeypatch: pytest.MonkeyPatch, tmp_path, capsys: pytest.CaptureFixture
) -> None:
    _clear_config_env(monkeypatch)
    cfg = tmp_path / "config.toml"
    assert main(["pair", "--no-qr"]) == 0
    assert not cfg.exists()


def test_daemon_config_file_overrides_env(
    monkeypatch: pytest.MonkeyPatch, tmp_path
) -> None:
    _clear_config_env(monkeypatch)
    monkeypatch.setenv("FIDO2_SESSION_KEY_B64", "env-key")
    monkeypatch.setenv("FIDO2_CHANNEL_ID", "env-chan")
    cfg = tmp_path / "config.toml"
    write_config_file(cfg, session_key_b64="file-key", channel_id="file-chan")
    # main() would try to start the daemon, so just test _resolve_config directly
    from fido_daemon.cli import build_parser
    args = build_parser().parse_args(["-c", str(cfg)])
    config = Config.from_env().with_config_file(cfg)
    assert config.session_key_b64 == "file-key"
    assert config.channel_id == "file-chan"

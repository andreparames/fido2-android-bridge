import base64
import os

import pytest
import tomlkit

from fido_daemon.config import (
    DEFAULT_RELAY_TOKEN,
    Config,
    clear_phone_pin,
    load_config_file,
    write_config_file,
)
from fido_daemon.cli import main


def _clear_config_env(monkeypatch: pytest.MonkeyPatch) -> None:
    for name in (
        "FIDO2_REMOTE_SOCKET",
        "FIDO2_RELAY_URL",
        "FIDO2_CHANNEL_ID",
        "FIDO2_STATIC_KEY_PATH",
        "FIDO2_PHONE_PUBLIC_KEY",
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


def test_default_static_key_path(monkeypatch: pytest.MonkeyPatch) -> None:
    _clear_config_env(monkeypatch)
    config = Config.from_env()
    assert config.static_key_path == os.path.expanduser("~/.config/fido-daemon/static_key.pem")


def test_static_key_path_reads_env(monkeypatch: pytest.MonkeyPatch, tmp_path) -> None:
    _clear_config_env(monkeypatch)
    path = str(tmp_path / "keys" / "daemon.pem")
    monkeypatch.setenv("FIDO2_STATIC_KEY_PATH", path)
    assert Config.from_env().static_key_path == path


def test_remote_socket_overrides_path(monkeypatch: pytest.MonkeyPatch) -> None:
    _clear_config_env(monkeypatch)
    monkeypatch.setenv("FIDO2_REMOTE_SOCKET", "/tmp/custom.sock")
    config = Config.from_env()
    assert config.socket_path == "/tmp/custom.sock"


def test_main_exits_2_without_channel(
    monkeypatch: pytest.MonkeyPatch, tmp_path, caplog: pytest.LogCaptureFixture
) -> None:
    _clear_config_env(monkeypatch)
    monkeypatch.setenv("FIDO2_STATIC_KEY_PATH", str(tmp_path / "static_key.pem"))
    assert main([]) == 2
    assert "pair" in caplog.text


def test_invalid_request_timeout_raises(monkeypatch: pytest.MonkeyPatch) -> None:
    _clear_config_env(monkeypatch)
    monkeypatch.setenv("FIDO2_REQUEST_TIMEOUT", "not-a-number")
    with pytest.raises(ValueError):
        Config.from_env()


def test_relay_token_defaults_embedded(monkeypatch: pytest.MonkeyPatch) -> None:
    _clear_config_env(monkeypatch)
    assert Config.from_env().relay_token == DEFAULT_RELAY_TOKEN


def test_relay_token_reads_env(monkeypatch: pytest.MonkeyPatch) -> None:
    _clear_config_env(monkeypatch)
    monkeypatch.setenv("FIDO2_RELAY_TOKEN", "jwt-token-value")
    assert Config.from_env().relay_token == "jwt-token-value"


def test_relay_token_env_empty_overrides_embedded(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _clear_config_env(monkeypatch)
    monkeypatch.setenv("FIDO2_RELAY_TOKEN", "")
    assert Config.from_env().relay_token == ""


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
    cfg.write_text('channel_id = "def"\n')
    data = load_config_file(cfg)
    assert data["channel_id"] == "def"


def test_write_config_file_creates_new(tmp_path) -> None:
    cfg = tmp_path / "config.toml"
    write_config_file(cfg, channel_id="chan1", relay_token="tok1")
    data = load_config_file(cfg)
    assert data["channel_id"] == "chan1"
    assert data["relay_token"] == "tok1"


def test_write_config_file_updates_existing_preserving_other_fields(tmp_path) -> None:
    cfg = tmp_path / "config.toml"
    cfg.write_text('channel_id = "old"\nmy_custom = "preserved"\n')
    write_config_file(cfg, channel_id="new")
    data = load_config_file(cfg)
    assert data["channel_id"] == "new"
    assert data["my_custom"] == "preserved"


def test_write_config_file_only_sets_specified_fields(tmp_path) -> None:
    cfg = tmp_path / "config.toml"
    write_config_file(cfg, channel_id="chan1")
    data = load_config_file(cfg)
    assert data["channel_id"] == "chan1"
    assert "relay_token" not in data


def test_with_config_file_overrides_env(monkeypatch: pytest.MonkeyPatch, tmp_path) -> None:
    _clear_config_env(monkeypatch)
    monkeypatch.setenv("FIDO2_CHANNEL_ID", "env-chan")
    monkeypatch.setenv("FIDO2_RELAY_TOKEN", "env-tok")
    cfg = tmp_path / "config.toml"
    write_config_file(cfg, channel_id="file-chan")
    config = Config.from_env().with_config_file(cfg)
    assert config.channel_id == "file-chan"
    assert config.relay_token == "env-tok"  # not overridden


def test_pair_cli_writes_config_file_and_static_key(
    monkeypatch: pytest.MonkeyPatch, tmp_path, capsys: pytest.CaptureFixture
) -> None:
    _clear_config_env(monkeypatch)
    monkeypatch.setenv("FIDO2_RELAY_URL", "ws://localhost:8000/connection/websocket")
    monkeypatch.setenv("FIDO2_RELAY_TOKEN", "test-jwt")
    key_path = tmp_path / "keys" / "static_key.pem"
    monkeypatch.setenv("FIDO2_STATIC_KEY_PATH", str(key_path))
    cfg = tmp_path / "config.toml"
    assert main(["-c", str(cfg), "pair", "--no-qr"]) == 0
    data = load_config_file(cfg)
    assert len(data["channel_id"]) == 32
    assert data["relay_token"] == "test-jwt"
    assert key_path.is_file()
    assert "session_key" not in data


def test_pair_cli_without_config_flag_does_not_write_file(
    monkeypatch: pytest.MonkeyPatch, tmp_path, capsys: pytest.CaptureFixture
) -> None:
    _clear_config_env(monkeypatch)
    monkeypatch.setenv("FIDO2_STATIC_KEY_PATH", str(tmp_path / "static_key.pem"))
    cfg = tmp_path / "config.toml"
    assert main(["pair", "--no-qr"]) == 0
    assert not cfg.exists()


def test_daemon_config_file_overrides_env(
    monkeypatch: pytest.MonkeyPatch, tmp_path
) -> None:
    _clear_config_env(monkeypatch)
    monkeypatch.setenv("FIDO2_CHANNEL_ID", "env-chan")
    cfg = tmp_path / "config.toml"
    write_config_file(cfg, channel_id="file-chan")
    config = Config.from_env().with_config_file(cfg)
    assert config.channel_id == "file-chan"


# --- phone static key pin (TOFU) ---


def test_phone_public_key_from_env(monkeypatch: pytest.MonkeyPatch) -> None:
    _clear_config_env(monkeypatch)
    key = bytes(range(32))
    monkeypatch.setenv("FIDO2_PHONE_PUBLIC_KEY", base64.b64encode(key).decode())
    assert Config.from_env().phone_public_key == key


def test_phone_public_key_defaults_none(monkeypatch: pytest.MonkeyPatch) -> None:
    _clear_config_env(monkeypatch)
    assert Config.from_env().phone_public_key is None


def test_phone_public_key_rejects_bad_base64(monkeypatch: pytest.MonkeyPatch) -> None:
    _clear_config_env(monkeypatch)
    monkeypatch.setenv("FIDO2_PHONE_PUBLIC_KEY", "not base64!!!")
    with pytest.raises(ValueError):
        Config.from_env()


def test_phone_public_key_rejects_wrong_length(monkeypatch: pytest.MonkeyPatch) -> None:
    _clear_config_env(monkeypatch)
    monkeypatch.setenv("FIDO2_PHONE_PUBLIC_KEY", base64.b64encode(b"short").decode())
    with pytest.raises(ValueError):
        Config.from_env()


def test_phone_public_key_roundtrips_through_config_file(
    monkeypatch: pytest.MonkeyPatch, tmp_path
) -> None:
    _clear_config_env(monkeypatch)
    key = bytes(range(32))
    cfg = tmp_path / "config.toml"
    write_config_file(cfg, channel_id="chan1", phone_public_key=base64.b64encode(key).decode())
    config = Config.from_env().with_config_file(cfg)
    assert config.phone_public_key == key
    assert config.config_path == str(cfg)


def test_phone_public_key_env_overrides_config_file(
    monkeypatch: pytest.MonkeyPatch, tmp_path
) -> None:
    _clear_config_env(monkeypatch)
    file_key = bytes(range(32))
    env_key = bytes(range(32, 64))
    monkeypatch.setenv("FIDO2_PHONE_PUBLIC_KEY", base64.b64encode(env_key).decode())
    cfg = tmp_path / "config.toml"
    write_config_file(cfg, phone_public_key=base64.b64encode(file_key).decode())
    assert Config.from_env().with_config_file(cfg).phone_public_key == env_key


def test_clear_phone_pin_removes_only_pin(tmp_path) -> None:
    key = base64.b64encode(bytes(range(32))).decode()
    cfg = tmp_path / "config.toml"
    write_config_file(cfg, channel_id="chan1", relay_token="tok1", phone_public_key=key)
    clear_phone_pin(cfg)
    data = load_config_file(cfg)
    assert "phone_public_key" not in data
    assert data["channel_id"] == "chan1"
    assert data["relay_token"] == "tok1"
    assert Config.from_env().with_config_file(cfg).phone_public_key is None


def test_clear_phone_pin_missing_file_is_noop(tmp_path) -> None:
    clear_phone_pin(tmp_path / "nope.toml")


def test_unpair_cli_clears_pin(
    monkeypatch: pytest.MonkeyPatch, tmp_path, capsys: pytest.CaptureFixture
) -> None:
    _clear_config_env(monkeypatch)
    key = base64.b64encode(bytes(range(32))).decode()
    cfg = tmp_path / "config.toml"
    write_config_file(cfg, channel_id="chan1", phone_public_key=key)
    assert main(["-c", str(cfg), "unpair", "--confirm"]) == 0
    assert "phone_public_key" not in load_config_file(cfg)


def test_unpair_confirms_with_yes(
    monkeypatch: pytest.MonkeyPatch, tmp_path, capsys: pytest.CaptureFixture
) -> None:
    _clear_config_env(monkeypatch)
    key = base64.b64encode(bytes(range(32))).decode()
    cfg = tmp_path / "config.toml"
    write_config_file(cfg, channel_id="chan1", phone_public_key=key)
    monkeypatch.setattr("builtins.input", lambda _prompt: "y")
    assert main(["-c", str(cfg), "unpair"]) == 0
    assert "phone_public_key" not in load_config_file(cfg)


def test_unpair_aborts_on_no(
    monkeypatch: pytest.MonkeyPatch, tmp_path, capsys: pytest.CaptureFixture
) -> None:
    _clear_config_env(monkeypatch)
    key = base64.b64encode(bytes(range(32))).decode()
    cfg = tmp_path / "config.toml"
    write_config_file(cfg, channel_id="chan1", phone_public_key=key)
    monkeypatch.setattr("builtins.input", lambda _prompt: "n")
    assert main(["-c", str(cfg), "unpair"]) == 1
    assert load_config_file(cfg)["phone_public_key"] == key


def test_unpair_aborts_on_eof(
    monkeypatch: pytest.MonkeyPatch, tmp_path, capsys: pytest.CaptureFixture
) -> None:
    _clear_config_env(monkeypatch)
    key = base64.b64encode(bytes(range(32))).decode()
    cfg = tmp_path / "config.toml"
    write_config_file(cfg, phone_public_key=key)

    def _eof(_prompt: str) -> str:
        raise EOFError

    monkeypatch.setattr("builtins.input", _eof)
    assert main(["-c", str(cfg), "unpair"]) == 1
    assert load_config_file(cfg)["phone_public_key"] == key


def test_unpair_cli_requires_config_file(
    monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture
) -> None:
    _clear_config_env(monkeypatch)
    assert main(["unpair", "--confirm"]) == 2
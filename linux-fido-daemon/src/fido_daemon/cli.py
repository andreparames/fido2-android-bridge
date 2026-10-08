"""Daemon entry point.

Supports `python -m fido_daemon.cli` (matches the systemd unit in
systemd/fido-daemon.service) and the console script `fido-daemon`.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import logging
import sys
from pathlib import Path

from fido_daemon.config import Config, clear_phone_pin, write_config_file
from fido_daemon.crypto import b64encode
from fido_daemon.ctap2 import (
    CMD_GET_INFO,
    Ctap2Error,
    decode_request_frame,
    encode_response,
    error_response,
    get_info_response,
)
from fido_daemon.noise import StaticKeyStore
from fido_daemon.pairing import Pairing, derive_channel_id
from fido_daemon.pairing_uri import format_pairing_uri
from fido_daemon.protocol import CTAP2_ERR_INVALID_COMMAND, CTAP2_ERR_OPERATION_DENIED
from fido_daemon.relay import RelayClient
from fido_daemon.relay_mode import is_managed_relay
from fido_daemon.socket_server import SocketServer
from fido_daemon.uhid_device import UhidDevice

logger = logging.getLogger(__name__)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="fido-daemon")
    parser.add_argument("-c", "--config", help="path to TOML config file for session key, channel id, and relay token")
    parser.add_argument("--socket", help="Unix socket path (defaults to FIDO2_REMOTE_SOCKET or /run/user/<UID>/fido2-bridge.sock)")
    parser.add_argument("--uhid", action="store_true", help="expose a virtual FIDO2 HID device (/dev/uhid) for browser WebAuthn")
    parser.add_argument("--verbose", action="store_true")
    subparsers = parser.add_subparsers(dest="command")
    pair_parser = subparsers.add_parser("pair", help="generate a pairing URI for the Android app")
    pair_parser.add_argument("--no-qr", action="store_true", help="skip terminal QR code output")
    pair_parser.add_argument("-c", "--config", help="path to TOML config file for channel id, relay token, and phone pin", default=argparse.SUPPRESS)
    unpair_parser = subparsers.add_parser(
        "unpair",
        help="clear the pinned phone key so a different phone can pair (requires -c)",
    )
    unpair_parser.add_argument("-c", "--config", help="path to TOML config file holding the phone_public_key pin", default=argparse.SUPPRESS)
    unpair_parser.add_argument("--confirm", action="store_true", help="skip the interactive confirmation prompt")
    return parser


def _run_pair(no_qr: bool = False, config_path: str | None = None) -> int:
    config = Config.from_env()
    key_path = Path(config.static_key_path)
    static_private = StaticKeyStore.load_or_create(key_path)
    static_public = StaticKeyStore.public_key(static_private)
    managed = is_managed_relay(config.relay_url)
    # Classic mode carries the relay token so the Android client can connect
    # without a rebuild. Managed mode omits it: the subscribe proxy is the
    # gate, and the token is not handed to the client.
    from fido_daemon.pairing import PairingGenerator

    pairing = PairingGenerator.generate(static_public)
    if config.relay_token and not managed:
        pairing = Pairing(
            static_public=static_public,
            channel_hex=pairing.channel_hex,
            relay_token=config.relay_token,
        )
    uri = format_pairing_uri(pairing)
    print(uri)
    if not no_qr:
        import segno

        qr = segno.make(uri)
        qr.terminal()
    if config_path:
        channel_id = derive_channel_id(pairing.channel_hex)
        write_config_file(
            config_path,
            channel_id=channel_id,
            relay_token=None if managed else config.relay_token or None,
        )
        print(f"\nConfig written to {config_path}", file=sys.stderr)
    return 0


def _run_unpair(config_path: str | None = None, confirm: bool = False) -> int:
    """Clear the pinned phone key (trust-on-first-use reset).

    Prompts for confirmation unless ``--confirm`` is passed (or stdin is not a
    TTY, in which case the prompt is treated as declined).
    """
    if not config_path:
        print("unpair requires a config file: pass -c PATH", file=sys.stderr)
        return 2
    if not confirm:
        try:
            answer = input(
                f"Clear the pinned phone key in {config_path}? "
                "A different phone will be accepted on its next handshake. [y/N] "
            ).strip().lower()
        except EOFError:
            answer = ""
        if answer not in ("y", "yes"):
            print("Aborted — pin not cleared.", file=sys.stderr)
            return 1
    clear_phone_pin(config_path)
    print(f"Cleared pinned phone key from {config_path}", file=sys.stderr)
    return 0


def _resolve_config(args: argparse.Namespace) -> Config:
    config = Config.from_env()
    if args.config:
        config = config.with_config_file(args.config)
    if args.socket or args.uhid:
        config = Config(
            socket_path=args.socket or config.socket_path,
            relay_url=config.relay_url,
            channel_id=config.channel_id,
            static_key_path=config.static_key_path,
            relay_token=config.relay_token,
            request_timeout=config.request_timeout,
            uhid_enabled=config.uhid_enabled or args.uhid,
            uhid_name=config.uhid_name,
        )
    return config


def build_request_handler(relay: RelayClient, config: Config):
    """Build the shared CTAP2 -> phone relay handler used by both transports.

    ``data`` is the full CTAP2 request frame ``[command_byte, cbor...]`` — the
    same shape the Unix socket receives and a CTAPHID MSG/CBOR payload carries.
    """

    async def handle_ctap2_command(data: bytes) -> bytes:
        """CTAP2 request frame -> CTAP2 response bytes, via the phone relay."""
        if data and data[0] == CMD_GET_INFO:
            return get_info_response()
        try:
            message = decode_request_frame(data)
        except Ctap2Error as exc:
            return error_response(exc.code)
        logger.debug("CTAP2 request: type=%s id=%s payload=%s", message.type, message.id, json.dumps(message.payload)[:500])
        plaintext = json.dumps(message.to_dict()).encode("utf-8")
        try:
            response = await relay.request(plaintext, timeout=config.request_timeout)
        except asyncio.TimeoutError:
            logger.warning("relay request timed out")
            return error_response(CTAP2_ERR_OPERATION_DENIED)
        try:
            response_msg = json.loads(response.decode("utf-8"))
            logger.debug("CTAP2 response: type=%s id=%s payload=%s", response_msg.get("type"), response_msg.get("id"), json.dumps(response_msg.get("payload"))[:500])
            return encode_response(response_msg)
        except (ValueError, KeyError) as exc:
            logger.exception("failed to encode relay response: %s", exc)
            return error_response(CTAP2_ERR_INVALID_COMMAND)

    return handle_ctap2_command


def _build_relay(
    config: Config,
    static_private: bytes,
    *,
    on_phone_identified=None,
    client_factory=None,
) -> RelayClient:
    """Construct the relay client with the relay mode matching `config`.

    Managed mode (host ``relay.gatebridge.app``) polls the subscribe until the
    backend proxy allows; classic mode uses the one-shot JWT subscribe.
    """
    return RelayClient(
        config.relay_url,
        config.channel_id,
        static_private,
        token=config.relay_token,
        phone_public_key=config.phone_public_key,
        on_phone_identified=on_phone_identified,
        client_factory=client_factory,
        managed=is_managed_relay(config.relay_url),
    )


async def _run(config: Config, *, client_factory=None) -> None:
    static_private = StaticKeyStore.load(Path(config.static_key_path))

    def _persist_phone_key(phone_public: bytes) -> None:
        if config.config_path:
            write_config_file(config.config_path, phone_public_key=b64encode(phone_public))
            logger.info("pinned phone static key to %s", config.config_path)
        else:
            logger.warning(
                "no config file set; phone static key pin lasts only for this session"
            )

    relay = _build_relay(
        config,
        static_private,
        on_phone_identified=_persist_phone_key,
        client_factory=client_factory,
    )
    await relay.connect()

    handle_ctap2_command = build_request_handler(relay, config)

    async def handle(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        request = await reader.read(4096)
        if not request:
            return
        reply = await handle_ctap2_command(request)
        writer.write(reply)
        await writer.drain()

    server = SocketServer(config.socket_path, handle)
    uhid: UhidDevice | None = None
    if config.uhid_enabled:
        uhid = UhidDevice(name=config.uhid_name, on_message=handle_ctap2_command)
    try:
        await server.start()
        if uhid is not None:
            await uhid.start()
        await asyncio.Event().wait()
    finally:
        if uhid is not None:
            await uhid.close()
        await server.close()
        await relay.close()


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if args.command == "pair":
        return _run_pair(no_qr=args.no_qr, config_path=args.config)
    if args.command == "unpair":
        return _run_unpair(config_path=args.config, confirm=args.confirm)
    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )
    config = _resolve_config(args)
    if not config.channel_id:
        logger.error("not paired: run `fido-daemon pair` and configure FIDO2_CHANNEL_ID (or a config file) first")
        return 2
    try:
        asyncio.run(_run(config))
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())

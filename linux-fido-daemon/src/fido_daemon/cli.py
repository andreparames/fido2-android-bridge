"""Daemon entry point.

Supports `python -m fido_daemon.cli` (matches the systemd unit in
systemd/fido-daemon.service) and the console script `fido-daemon`.
"""

from __future__ import annotations

import argparse
import asyncio
import base64
import json
import logging
import sys

from fido_daemon.config import Config
from fido_daemon.crypto import AesGcmCipher, SecretKey
from fido_daemon.ctap2 import (
    Ctap2Error,
    decode_request_frame,
    encode_response,
    error_response,
)
from fido_daemon.pairing import PairingGenerator
from fido_daemon.pairing_uri import format_pairing_uri
from fido_daemon.protocol import CTAP2_ERR_INVALID_COMMAND, CTAP2_ERR_OPERATION_DENIED
from fido_daemon.relay import RelayClient
from fido_daemon.socket_server import SocketServer

logger = logging.getLogger(__name__)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="fido-daemon")
    parser.add_argument(
        "--socket",
        help="Unix socket path (defaults to FIDO2_REMOTE_SOCKET or /run/user/<UID>/fido2-bridge.sock)",
    )
    parser.add_argument("--verbose", action="store_true")
    subparsers = parser.add_subparsers(dest="command")
    subparsers.add_parser("pair", help="generate a pairing URI for the Android app")
    return parser


def _run_pair() -> int:
    print(format_pairing_uri(PairingGenerator.generate()))
    return 0


def _resolve_config(args: argparse.Namespace) -> Config:
    config = Config.from_env()
    if args.socket:
        config = Config(
            socket_path=args.socket,
            relay_url=config.relay_url,
            channel_id=config.channel_id,
            session_key_b64=config.session_key_b64,
            relay_token=config.relay_token,
            request_timeout=config.request_timeout,
        )
    return config


async def _run(config: Config, *, client_factory=None) -> None:
    cipher = AesGcmCipher(SecretKey(base64.b64decode(config.session_key_b64)))
    relay = RelayClient(
        config.relay_url,
        config.channel_id,
        cipher,
        token=config.relay_token,
        client_factory=client_factory,
    )
    await relay.connect()

    async def handle(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        request = await reader.read(4096)
        if not request:
            return
        try:
            message = decode_request_frame(request)
        except Ctap2Error as exc:
            writer.write(error_response(exc.code))
            await writer.drain()
            return
        plaintext = json.dumps(message.to_dict()).encode("utf-8")
        try:
            response = await relay.request(plaintext, timeout=config.request_timeout)
        except asyncio.TimeoutError:
            logger.warning("relay request timed out")
            writer.write(error_response(CTAP2_ERR_OPERATION_DENIED))
            await writer.drain()
            return
        try:
            reply = encode_response(json.loads(response.decode("utf-8")))
        except (ValueError, KeyError) as exc:
            logger.exception("failed to encode relay response: %s", exc)
            writer.write(error_response(CTAP2_ERR_INVALID_COMMAND))
            await writer.drain()
            return
        writer.write(reply)
        await writer.drain()

    server = SocketServer(config.socket_path, handle)
    try:
        await server.start()
        await asyncio.Event().wait()
    finally:
        await server.close()
        await relay.close()


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if args.command == "pair":
        return _run_pair()
    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )
    config = _resolve_config(args)
    if not config.session_key_b64:
        logger.error("FIDO2_SESSION_KEY_B64 must be set (pair the device first)")
        return 2
    try:
        asyncio.run(_run(config))
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())

"""Integration harness CLI — start the daemon and run mock scenarios.

Usage::

    python -m fido_daemon.harness get-assertion
    python -m fido_daemon.harness make-credential
    python -m fido_daemon.harness all

Requires ``FIDO2_CHANNEL_ID``/``FIDO2_STATIC_KEY_PATH`` and ``FIDO2_RELAY_URL``
in the environment (same as the daemon itself).  Generates a temporary socket
path and a random daemon static key if not provided.
"""

from __future__ import annotations

import argparse
import asyncio
import base64
import logging
import os
import secrets
import sys
import tempfile
from pathlib import Path

from fido_daemon.cli import _run
from fido_daemon.config import (
    Config,
    default_relay_token,
)
from fido_daemon.ctap2 import CMD_GET_ASSERTION, CMD_MAKE_CREDENTIAL
from fido_daemon.noise import StaticKeyStore
from fido_daemon.pairing import derive_channel_id
from tests.fakes import FakeBroker
from tests.harness import MockBrowser, MockPhone

logger = logging.getLogger(__name__)

CLIENT_DATA_HASH = b"\x11" * 32


def _assertion_responder(request: dict) -> dict:
    auth_data = b"\x00" * 32 + b"\x05" + b"\x00\x00\x00\x00"
    return {
        "version": 3,
        "type": "assertionResult",
        "id": request["id"],
        "payload": {
            "credentialId": base64.b64encode(b"cred-1").decode().rstrip("="),
            "authenticatorData": base64.b64encode(auth_data).decode().rstrip("="),
            "signature": base64.b64encode(b"sig").decode().rstrip("="),
        },
    }


def _make_credential_responder(request: dict) -> dict:
    import fido2.cbor as cbor

    att_obj = cbor.encode(
        {
            "fmt": "packed",
            "authData": b"\x00" * 32 + b"\x01" + b"\x00\x00\x00\x00",
            "attStmt": {},
        }
    )
    return {
        "version": 3,
        "type": "makeCredentialResult",
        "id": request["id"],
        "payload": {
            "attestationObject": base64.b64encode(att_obj).decode().rstrip("="),
        },
    }


def _build_config() -> Config:
    relay_url = os.environ.get(
        "FIDO2_RELAY_URL", "ws://localhost:8000/connection/websocket"
    )

    channel_hex = os.environ.get("FIDO2_CHANNEL_ID", "")
    if not channel_hex:
        channel_hex = secrets.token_hex(16)
        logger.info("generated random channel hex: %s", channel_hex)
    channel_id = derive_channel_id(channel_hex)

    static_key_path = os.path.expanduser(
        os.environ.get(
            "FIDO2_STATIC_KEY_PATH",
            os.path.join(tempfile.gettempdir(), f"fido-harness-{os.getpid()}-static.pem"),
        )
    )
    StaticKeyStore.load_or_create(Path(static_key_path))

    socket_path = os.environ.get(
        "FIDO2_REMOTE_SOCKET",
        os.path.join(tempfile.gettempdir(), f"fido-harness-{os.getpid()}.sock"),
    )

    return Config(
        socket_path=socket_path,
        relay_url=relay_url,
        channel_id=channel_id,
        static_key_path=static_key_path,
        relay_token=os.environ.get("FIDO2_RELAY_TOKEN") or default_relay_token(),
        request_timeout=float(os.environ.get("FIDO2_REQUEST_TIMEOUT", "5.0")),
        uhid_enabled=False,
        uhid_name="fido-daemon",
    )


async def _run_scenario(
    config: Config, broker: FakeBroker, scenario: str
) -> bool:
    task = asyncio.create_task(_run(config, client_factory=broker.new_client))

    for _ in range(200):
        if os.path.exists(config.socket_path):
            break
        await asyncio.sleep(0.01)

    if not os.path.exists(config.socket_path):
        logger.error("socket did not appear at %s", config.socket_path)
        task.cancel()
        return False

    phone = MockPhone(broker, config, _assertion_responder)
    await phone.start()

    browser = MockBrowser(config.socket_path)
    ok = True

    try:
        if scenario in ("get-assertion", "all"):
            logger.info("scenario: get-assertion")
            response = await browser.send_get_assertion("example.com", CLIENT_DATA_HASH)
            if response and response[0] == 0x00:
                logger.info("get-assertion: OK (status 0x00)")
            else:
                logger.error("get-assertion: FAILED (response=%r)", response)
                ok = False

        if scenario in ("make-credential", "all"):
            logger.info("scenario: make-credential")
            response = await browser.send_make_credential(
                rp_id="example.com",
                client_data_hash=CLIENT_DATA_HASH,
                user_id=b"user-1",
                user_name="alice@example.com",
            )
            if response and response[0] == 0x00:
                logger.info("make-credential: OK (status 0x00)")
            else:
                logger.error("make-credential: FAILED (response=%r)", response)
                ok = False
    finally:
        task.cancel()
        try:
            await task
        except asyncio.CancelledError:
            pass

    return ok


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="fido-daemon-harness",
        description="Integration harness: mocks browser + phone, tests real daemon path",
    )
    parser.add_argument(
        "scenario",
        choices=["get-assertion", "make-credential", "all"],
        help="which scenario to run",
    )
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args(argv)

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )

    config = _build_config()
    broker = FakeBroker()

    logger.info("channel_id: %s", config.channel_id)
    logger.info("socket: %s", config.socket_path)
    logger.info("relay: %s", config.relay_url)

    ok = asyncio.run(_run_scenario(config, broker, args.scenario))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())

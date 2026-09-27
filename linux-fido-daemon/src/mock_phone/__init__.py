"""MockPhone — subscribe to Centrifugo, respond to daemon requests.

This module acts as a stand-in for the Android app when testing the daemon
end-to-end through a real Centrifugo broker.  It subscribes to
``fidobridge.<channel_id>``, opens incoming sealed requests, signs them with
a canned responder, and publishes the sealed response.
"""

from __future__ import annotations

import argparse
import asyncio
import base64
import json
import logging
import sys

import centrifuge
import fido2.cbor as cbor

from fido_daemon.crypto import (
    AesGcmCipher,
    SecretKey,
    TagMismatchError,
    message_from_json,
    message_to_json,
)
from fido_daemon.protocol import (
    TYPE_ASSERTION_RESULT,
    TYPE_MAKE_CREDENTIAL_RESULT,
    TYPE_ERROR,
    CTAP2_ERR_OPERATION_DENIED,
)
from fido_daemon.relay import RelayClient
from harness_common.config import HarnessConfig

logger = logging.getLogger(__name__)


def _assertion_responder(request: dict) -> dict:
    """Build a PROTOCOL.md §5.3 assertionResult."""
    auth_data = b"\x00" * 32 + b"\x05" + b"\x00\x00\x00\x00"
    return {
        "version": 1,
        "type": TYPE_ASSERTION_RESULT,
        "id": request["id"],
        "payload": {
            "credentialId": base64.b64encode(b"cred-1").decode().rstrip("="),
            "authenticatorData": base64.b64encode(auth_data).decode().rstrip("="),
            "signature": base64.b64encode(b"sig").decode().rstrip("="),
        },
    }


def _make_credential_responder(request: dict) -> dict:
    """Build a PROTOCOL.md §5.4 makeCredentialResult."""
    att_obj = cbor.encode(
        {
            "fmt": "none",
            "authData": b"\x00" * 32 + b"\x45" + b"\x00\x00\x00\x00",
            "attStmt": {},
        }
    )
    return {
        "version": 1,
        "type": TYPE_MAKE_CREDENTIAL_RESULT,
        "id": request["id"],
        "payload": {
            "credentialId": base64.b64encode(b"cred-1").decode().rstrip("="),
            "authenticatorData": base64.b64encode(
                b"\x00" * 32 + b"\x45" + b"\x00\x00\x00\x00"
            ).decode().rstrip("="),
            "attestationObject": base64.b64encode(att_obj).decode().rstrip("="),
        },
    }


RESPONDERS = {
    "getAssertion": _assertion_responder,
    "makeCredential": _make_credential_responder,
}


class MockPhone(RelayClient):
    """Subscribes to the Centrifugo channel, decrypts incoming requests,
    calls a responder, and publishes the sealed reply."""

    def __init__(self, config: HarnessConfig) -> None:
        cipher = AesGcmCipher(SecretKey(config.session_key_bytes))
        super().__init__(
            url=config.relay_url,
            channel_id=config.channel_id,
            cipher=cipher,
            token=config.relay_token or "",
        )
        self._config = config
        self.received: list[dict] = []
        self._seen_ids: set[str] = set()

    async def _handle_publication(self, ctx: centrifuge.PublicationContext) -> None:
        raw = ctx.pub.data
        if isinstance(raw, (bytes, bytearray)):
            raw = bytes(raw).decode("utf-8")
        if isinstance(raw, dict):
            raw = json.dumps(raw)
        try:
            wire = message_from_json(raw)
            plaintext = self._cipher.open(wire)
            request = json.loads(plaintext.decode("utf-8"))
        except TagMismatchError:
            logger.error("SECURITY ALERT: GCM tag verification failed; dropping message")
            return
        except Exception as e:
            logger.warning("dropping malformed request: %s", e)
            return

        self.received.append(request)
        msg_type = request.get("type")
        msg_id = request.get("id")
        logger.info("received %s (id=%s)", msg_type, msg_id)

        responder = RESPONDERS.get(msg_type)
        if responder is None:
            logger.debug("ignoring non-request message type: %s", msg_type)
            return

        # Skip our own echo (same ID but wire data matches what we published)
        if self._pending_wire.get(msg_id) == raw:
            logger.debug("skipping own echo (id=%s)", msg_id)
            return
        # Also skip if we've already seen this ID (belt-and-suspenders)
        if msg_id in self._seen_ids:
            logger.debug("skipping duplicate (id=%s)", msg_id)
            return
        self._seen_ids.add(msg_id)

        response = responder(request)
        reply = json.dumps(response).encode("utf-8")
        sealed = self._cipher.seal(self._config.channel_id, reply)
        # Fire-and-forget: don't await publish to avoid blocking the event loop
        asyncio.create_task(self._publish_response(message_to_json(sealed)))
        logger.info("sent %s (id=%s)", response["type"], response.get("id"))

    async def _publish_response(self, data: str) -> None:
        """Publish response data, logging any errors."""
        try:
            await self._sub.publish(data)
        except Exception as e:
            logger.warning("failed to publish response: %s", e)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="mock-phone",
        description="Mock phone: subscribes to Centrifugo and responds to daemon requests",
    )
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args(argv)

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )

    config = HarnessConfig.from_env()
    logger.info("channel_id: %s", config.channel_id)
    logger.info("relay: %s", config.relay_url)
    logger.info("token: %s", "set" if config.relay_token else "anonymous")

    async def _run() -> None:
        phone = MockPhone(config)
        try:
            await phone.connect()
            logger.info("mock phone running — press Ctrl+C to stop")
            await asyncio.Event().wait()  # block forever
        except asyncio.CancelledError:
            pass
        finally:
            await phone.close()

    try:
        asyncio.run(_run())
    except KeyboardInterrupt:
        logger.info("interrupted")

    return 0


if __name__ == "__main__":
    sys.exit(main())

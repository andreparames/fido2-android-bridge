"""MockPhone — subscribe to Centrifugo, respond to daemon requests.

This module acts as a stand-in for the Android app when testing the daemon
end-to-end through a real Centrifugo broker.  It subscribes to
``fidobridge:<channel_id>``, opens the Noise IK handshake as initiator,
decrypts incoming requests, signs them with a canned responder, and publishes
the encrypted response.
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

from fido_daemon.noise import (
    KIND_DATA,
    KIND_IK1,
    KIND_IK2,
    NoiseInitiatorSession,
    WireEnvelope,
    envelope_from_json,
    envelope_to_json,
)
from fido_daemon.protocol import (
    RELAY_CHANNEL_PREFIX,
    TYPE_ASSERTION_RESULT,
    TYPE_MAKE_CREDENTIAL_RESULT,
    TYPE_ERROR,
    CTAP2_ERR_OPERATION_DENIED,
)
from harness_common.config import HarnessConfig

logger = logging.getLogger(__name__)


def _assertion_responder(request: dict) -> dict:
    """Build a PROTOCOL.md §5.3 assertionResult."""
    auth_data = b"\x00" * 32 + b"\x05" + b"\x00\x00\x00\x00"
    return {
        "version": 3,
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
        "version": 3,
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


class _PhoneHandler(centrifuge.SubscriptionEventHandler):
    def __init__(self, phone: "MockPhone") -> None:
        self._phone = phone

    async def on_publication(self, ctx: centrifuge.PublicationContext) -> None:
        await self._phone._handle_publication(ctx)


class MockPhone:
    """Subscribes to the Centrifugo channel, opens the Noise IK handshake,
    decrypts incoming requests, calls a responder, and publishes the reply."""

    def __init__(self, config: HarnessConfig) -> None:
        self._config = config
        self._channel_id = config.channel_id
        self._channel = f"{RELAY_CHANNEL_PREFIX}{config.channel_id}"
        self._session = NoiseInitiatorSession(
            config.phone_static_private(), config.daemon_static_public()
        )
        self._client: centrifuge.Client | None = None
        self._sub: centrifuge.Subscription | None = None
        self.received: list[dict] = []
        self._seen_ids: set[str] = set()

    async def _get_token(self) -> str:
        return self._config.relay_token or ""

    async def connect(self) -> None:
        self._client = centrifuge.Client(
            self._config.relay_url,
            token=self._config.relay_token or "",
            get_token=self._get_token,
        )
        await self._client.connect()
        self._sub = self._client.new_subscription(
            self._channel, events=_PhoneHandler(self)
        )
        await self._sub.subscribe()
        ik1 = self._session.create_ik1()
        await self._sub.publish(
            envelope_to_json(WireEnvelope(self._channel_id, KIND_IK1, ik1))
        )
        logger.info("mock phone listening on %s", self._channel)

    async def _handle_publication(self, ctx: centrifuge.PublicationContext) -> None:
        raw = ctx.pub.data
        if isinstance(raw, (bytes, bytearray)):
            raw = bytes(raw).decode("utf-8")
        if isinstance(raw, dict):
            raw = json.dumps(raw)
        try:
            envelope = envelope_from_json(raw)
        except ValueError as exc:
            logger.warning("dropping malformed envelope: %s", exc)
            return

        if envelope.kind == KIND_IK2:
            self._session.receive_ik2(envelope.payload)
            return
        if envelope.kind != KIND_DATA:
            return

        plaintext = self._session.decrypt(envelope.payload)
        request = json.loads(plaintext.decode("utf-8"))
        self.received.append(request)
        msg_type = request.get("type")
        msg_id = request.get("id")
        logger.info("received %s (id=%s)", msg_type, msg_id)

        responder = RESPONDERS.get(msg_type)
        if responder is None:
            return
        if msg_id in self._seen_ids:
            logger.debug("skipping duplicate (id=%s)", msg_id)
            return
        self._seen_ids.add(msg_id)

        response = responder(request)
        reply = json.dumps(response).encode("utf-8")
        ciphertext = self._session.encrypt(reply)
        # Fire-and-forget: don't await publish to avoid blocking the event loop
        asyncio.create_task(
            self._publish_response(
                envelope_to_json(WireEnvelope(self._channel_id, KIND_DATA, ciphertext))
            )
        )
        logger.info("sent %s (id=%s)", response["type"], response.get("id"))

    async def _publish_response(self, data: str) -> None:
        """Publish response data, logging any errors."""
        try:
            await self._sub.publish(data)
        except Exception as e:
            logger.warning("failed to publish response: %s", e)

    async def close(self) -> None:
        if self._client is not None:
            await self._client.disconnect()
            self._client = None
            self._sub = None


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
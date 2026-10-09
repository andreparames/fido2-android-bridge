"""Centrifugo relay client with Noise IK transport (PROTOCOL.md v3).

The relay is an untrusted Centrifugo broker. Both peers subscribe and publish
to the channel ``fidobridge:<channel_id>``; the PROTOCOL.md §3 ``WireEnvelope``
is the publication payload.

The daemon is the Noise **responder**. On every (re)connect the phone
(initiator) publishes an ``ik1`` handshake message; the daemon replies with
``ik2`` and both call ``split()``. Thereafter ``data`` envelopes carry Noise
transport ciphertext. Any authentication failure (handshake or transport tag)
is a security alert: the message is dropped and the connection flagged.

Responses are correlated to requests by the plaintext ``id`` (PROTOCOL.md §4):
`request()` encrypts + publishes a message and awaits the publication whose
opened plaintext echoes the same ``id``.

Auth: a Centrifugo connection JWT is supplied via ``FIDO2_RELAY_TOKEN``. It is
attached on startup and on every reconnect (via ``get_token``), and is never
logged or leaked into the connection URL.
"""

from __future__ import annotations

import asyncio
import json
import logging
from collections.abc import Callable

import centrifuge
from centrifuge import Client, SubscriptionEventHandler

from fido_daemon.noise import (
    KIND_DATA,
    KIND_IK1,
    KIND_IK2,
    NoiseAuthenticationError,
    NoiseResponderSession,
    WireEnvelope,
    envelope_from_json,
    envelope_to_json,
)
from fido_daemon.pairing import derive_channel_id
from fido_daemon.protocol import RELAY_CHANNEL_PREFIX

logger = logging.getLogger(__name__)


class _RelaySubscriptionHandler(SubscriptionEventHandler):
    def __init__(self, relay: "RelayClient") -> None:
        self._relay = relay

    async def on_publication(self, ctx: centrifuge.PublicationContext) -> None:
        await self._relay._handle_publication(ctx)


class RelayClient:
    def __init__(
        self,
        url: str,
        channel_id: str,
        static_private: bytes,
        token: str = "",
        *,
        client_factory: Callable[[], Client] | None = None,
        phone_public_key: bytes | None = None,
        on_phone_identified: Callable[[bytes], None] | None = None,
    ) -> None:
        self._url = url
        self._channel_id = channel_id
        self._static_private = static_private
        self._phone_public_key = phone_public_key
        self._on_phone_identified = on_phone_identified
        self._token = token
        self._client_factory = client_factory or self._build_client
        self._client: Client | None = None
        self._sub: centrifuge.Subscription | None = None
        self._pending: dict[str, asyncio.Future] = {}
        self._pending_wire: dict[str, str] = {}
        self._tampered = False
        self._session: NoiseResponderSession | None = None
        self._handshake_done = asyncio.Event()
        self._learned_phone_key: bytes | None = None

    @property
    def channel(self) -> str:
        return f"{RELAY_CHANNEL_PREFIX}{self._channel_id}"

    @property
    def tampered(self) -> bool:
        """True after a Noise authentication failure was detected."""
        return self._tampered

    @property
    def phone_public_key(self) -> bytes | None:
        """The phone static key being enforced (explicit pin or first-learned)."""
        return self._phone_public_key or self._learned_phone_key

    async def _get_token(self) -> str:
        return self._token

    def _build_client(self) -> Client:
        return Client(
            self._url,
            token=self._token or "",
            get_token=self._get_token,
        )

    async def connect(self) -> None:
        self._client = self._client_factory()
        await self._client.connect()
        logger.info("connected to relay channel %s", self.channel)
        self._sub = self._client.new_subscription(
            self.channel, events=_RelaySubscriptionHandler(self)
        )
        await self._sub.subscribe()

    async def rotate_channel(self, channel_hex: str) -> None:
        """Tear down and (re)subscribe to the channel derived from `channel_hex`.

        Used by the control plane (`fido-daemon pair`): disconnect the current
        client, cancel in-flight requests, clear the Noise session, tamper
        flag, and any trust-on-first-use learned key, then connect and
        subscribe to ``fidobridge:<derive_channel_id(channel_hex)>``. The
        *configured* phone pin (``phone_public_key``) is preserved — re-pairing
        does not reset TOFU.
        """
        if self._client is not None:
            await self._client.disconnect()
            self._client = None
            self._sub = None
        for future in self._pending.values():
            future.cancel()
        self._pending.clear()
        self._pending_wire.clear()
        self._session = None
        self._tampered = False
        self._learned_phone_key = None
        self._handshake_done = asyncio.Event()
        self._channel_id = derive_channel_id(channel_hex)
        await self.connect()

    async def wait_handshake(self) -> None:
        """Block until the Noise handshake with the phone completes.

        Returns once the daemon has received the phone's ``ik1`` and published
        ``ik2``; used by callers (e.g. the review web app) that need to know the
        phone has paired before issuing requests.
        """
        await self._handshake_done.wait()

    async def request(self, plaintext: bytes, timeout: float | None = None) -> bytes:
        """Encrypt + publish `plaintext` and await the response echoing its id.

        The overall `timeout` (if given) bounds the handshake wait, the
        publish, and the response wait.
        """

        async def _do() -> bytes:
            if self._sub is None:
                raise RuntimeError("relay not connected")
            handshake_done = self._handshake_done
            await handshake_done.wait()
            # A waiter that resumed on a retired handshake must not encrypt with
            # a replacement session: the channel may have been rotated while we
            # waited (control-plane pair). Reject instead.
            if handshake_done is not self._handshake_done or self._session is None:
                raise RuntimeError("relay handshake was reset (channel rotated)")
            message_id = json.loads(plaintext.decode("utf-8"))["id"]
            future: asyncio.Future = asyncio.get_running_loop().create_future()
            self._pending[message_id] = future
            try:
                ciphertext = self._session.encrypt(plaintext)
                wire_data = envelope_to_json(
                    WireEnvelope(self._channel_id, KIND_DATA, ciphertext)
                )
                self._pending_wire[message_id] = wire_data
                await self._sub.publish(wire_data)
                return await future
            finally:
                self._pending.pop(message_id, None)
                self._pending_wire.pop(message_id, None)

        if timeout is None:
            return await _do()
        return await asyncio.wait_for(_do(), timeout)

    async def _handle_publication(self, ctx: centrifuge.PublicationContext) -> None:
        raw = ctx.pub.data
        if isinstance(raw, (bytes, bytearray)):
            raw = bytes(raw).decode("utf-8")
        if isinstance(raw, dict):
            raw = json.dumps(raw)
        try:
            envelope = envelope_from_json(raw)
        except ValueError as exc:
            logger.warning("dropping malformed relay message: %s", exc)
            return
        if envelope.channel_id != self._channel_id:
            logger.warning("dropping relay message for wrong channel")
            return

        if envelope.kind == KIND_IK1:
            await self._handle_ik1(envelope)
            return
        if envelope.kind == KIND_IK2:
            # Only the responder publishes ik2; any ik2 we receive is our own
            # relay echo — ignore it.
            return
        if envelope.kind == KIND_DATA:
            await self._handle_data(envelope, raw)

    async def _handle_ik1(self, envelope: WireEnvelope) -> None:
        session = NoiseResponderSession(self._static_private)
        try:
            ik2 = session.receive_ik1(envelope.payload)
        except NoiseAuthenticationError:
            self._tampered = True
            logger.error(
                "SECURITY ALERT: Noise handshake (ik1) authentication failed; dropping"
            )
            return
        # The phone static key is pinned either explicitly (env/config file) or
        # via trust-on-first-use: the first phone that completes a valid
        # handshake becomes the pinned identity for this daemon.
        effective_pin = self.phone_public_key
        if effective_pin is not None and session.remote_static != effective_pin:
            self._tampered = True
            logger.error(
                "SECURITY ALERT: handshake from unknown phone static key; dropping"
            )
            return
        if effective_pin is None:
            self._learned_phone_key = session.remote_static
            if self._on_phone_identified is not None:
                try:
                    self._on_phone_identified(session.remote_static)
                except Exception:
                    logger.exception("failed to persist phone static key pin")
        self._session = session
        # Publish ik2 from a background task: awaiting the publish ack inside
        # a publication handler blocks the client's reply loop and times out
        # (centrifuge-python re-entrancy). handshake_done is set only once ik2
        # is on the wire, so requests never overtake it.
        async def _send_ik2() -> None:
            try:
                await self._sub.publish(
                    envelope_to_json(WireEnvelope(self._channel_id, KIND_IK2, ik2))
                )
                self._handshake_done.set()
            except Exception:
                logger.exception("SECURITY/failure publishing ik2; awaiting phone retry")
        asyncio.create_task(_send_ik2())
        logger.info("Noise handshake established (remote static=%s)", session.remote_static[:8].hex())

    async def _handle_data(self, envelope: WireEnvelope, raw: str) -> None:
        if self._session is None or not self._handshake_done.is_set():
            logger.warning("dropping data before handshake complete")
            return
        # Skip our own relay echo BEFORE attempting to decrypt: our own
        # publications are sealed with the sender key and cannot be opened
        # with the receiver key (unlike the legacy symmetric AES-GCM).
        if raw in self._pending_wire.values():
            logger.debug("skipping own echo")
            return
        try:
            plaintext = self._session.decrypt(envelope.payload)
        except NoiseAuthenticationError:
            self._tampered = True
            logger.error(
                "SECURITY ALERT: Noise transport authentication failed; dropping message"
            )
            return
        try:
            message_id = json.loads(plaintext.decode("utf-8"))["id"]
        except (ValueError, KeyError, json.JSONDecodeError) as exc:
            logger.warning("dropping malformed relay message: %s", exc)
            return
        future = self._pending.get(message_id)
        if future is None or future.done():
            logger.warning("dropping unsolicited relay message with id %s", message_id)
            return
        future.set_result(plaintext)

    async def close(self) -> None:
        if self._client is not None:
            await self._client.disconnect()
            self._client = None
            self._sub = None
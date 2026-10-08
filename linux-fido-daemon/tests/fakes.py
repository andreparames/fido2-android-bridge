"""Shared in-memory Centrifugo fakes and a Noise IK initiator phone peer.

``NoisePhonePeer`` simulates the Android client: it is the Noise handshake
*initiator* (per PROTOCOL.md §6), initiating a fresh ``ik1`` handshake on
``start()``, then decrypting daemon requests, calling a canned responder, and
publishing the encrypted reply.
"""

import asyncio
import json
import secrets

from centrifuge import Publication, PublicationContext, SubscriptionEventHandler

from fido_daemon.noise import (
    KIND_DATA,
    KIND_IK1,
    KIND_IK2,
    NoiseError,
    NoiseInitiatorSession,
    WireEnvelope,
    envelope_from_json,
    envelope_to_json,
)
from fido_daemon.protocol import RELAY_CHANNEL_PREFIX
from fido_daemon.relay import SubscribeDeniedError

CHANNEL_ID = "0123456789abcdef0123456789abcdef"
RELAY_URL = "ws://localhost:8000/connection/websocket"


class FakeBroker:
    """In-memory stand-in for a Centrifugo broker.

    Subscribers to the same channel receive each other's publications (no echo
    back to the publisher, matching Centrifugo's default `echo=False`).
    """

    def __init__(self) -> None:
        self._subscribers: dict[str, list["FakeSubscription"]] = {}
        self.deny_subscribe = False

    def new_client(self) -> "FakeClient":
        return FakeClient(self)

    def add(self, sub: "FakeSubscription") -> None:
        self._subscribers.setdefault(sub.channel, []).append(sub)

    async def route(self, channel: str, data: str, source: "FakeSubscription") -> None:
        for sub in self._subscribers.get(channel, []):
            if sub is not source:
                await sub.deliver(data)


class FakeSubscription:
    def __init__(self, broker: FakeBroker, channel: str) -> None:
        self._broker = broker
        self.channel = channel
        self.events = None
        self.published: list[str] = []
        self.subscribed = False
        self.subscribe_attempts = 0

    async def subscribe(self) -> None:
        self.subscribe_attempts += 1
        if self._broker.deny_subscribe:
            raise SubscribeDeniedError(f"subscription denied for {self.channel}")
        self.subscribed = True
        self._broker.add(self)

    async def publish(self, data: str) -> None:
        self.published.append(data)
        await self._broker.route(self.channel, data, self)

    async def deliver(self, data: str) -> None:
        ctx = PublicationContext(
            pub=Publication(offset=0, data=data, info=None, tags={}, delta=False)
        )
        await self.events.on_publication(ctx)


class FakeClient:
    def __init__(self, broker: FakeBroker) -> None:
        self._broker = broker
        self.connected = False
        self.disconnected = False
        self.created_channel: str | None = None
        self.subscription: FakeSubscription | None = None

    async def connect(self) -> None:
        self.connected = True

    def new_subscription(self, channel: str, events=None) -> FakeSubscription:
        self.created_channel = channel
        self.subscription = FakeSubscription(self._broker, channel)
        self.subscription.events = events
        return self.subscription

    async def disconnect(self) -> None:
        self.disconnected = True


class _PhoneHandler(SubscriptionEventHandler):
    def __init__(self, phone: "NoisePhonePeer") -> None:
        self._phone = phone

    async def on_publication(self, ctx: PublicationContext) -> None:
        await self._phone._handle(ctx)


class NoisePhonePeer:
    """A stand-in phone peer (Noise IK initiator) that opens the handshake and
    replies to daemon requests with a canned response produced by
    `responder(request_dict) -> response_dict`."""

    def __init__(
        self,
        broker: FakeBroker,
        channel_id: str,
        daemon_static_public: bytes,
        responder,
        *,
        static_private: bytes | None = None,
    ) -> None:
        self._broker = broker
        self._channel_id = channel_id
        self._channel = f"{RELAY_CHANNEL_PREFIX}{channel_id}"
        self._daemon_static_public = daemon_static_public
        self._static_private = static_private or secrets.token_bytes(32)
        self._responder = responder
        self.client: FakeClient | None = None
        self.sub: FakeSubscription | None = None
        self.received: list[dict] = []
        self._session: NoiseInitiatorSession | None = None

    async def start(self) -> None:
        self.client = self._broker.new_client()
        await self.client.connect()
        self.sub = self.client.new_subscription(self._channel, events=_PhoneHandler(self))
        await self.sub.subscribe()
        self._session = NoiseInitiatorSession(
            self._static_private, self._daemon_static_public
        )
        ik1 = self._session.create_ik1()
        await self.sub.publish(envelope_to_json(WireEnvelope(self._channel_id, KIND_IK1, ik1)))

    async def wait_ready(self, timeout: float = 2.0) -> None:
        """Wait until this peer's handshake completes (ik2 received)."""
        deadline = asyncio.get_event_loop().time() + timeout
        while self._session is None or not self._session.handshake_finished:
            if asyncio.get_event_loop().time() > deadline:
                raise TimeoutError("phone handshake did not complete")
            await asyncio.sleep(0.01)

    async def _handle(self, ctx: PublicationContext) -> None:
        envelope = envelope_from_json(ctx.pub.data)
        if envelope.kind == KIND_IK2:
            try:
                self._session.receive_ik2(envelope.payload)
            except NoiseError:
                pass
            return
        if envelope.kind != KIND_DATA:
            return
        try:
            plaintext = self._session.decrypt(envelope.payload)
        except NoiseError:
            # Not ours (e.g. a half-open session that never completed the
            # handshake); ignore without crashing the broker loop.
            return
        request = json.loads(plaintext.decode("utf-8"))
        self.received.append(request)
        response = self._responder(request)
        if asyncio.iscoroutine(response):
            response = await response
        if response is None:
            return
        reply = json.dumps(response).encode("utf-8")
        ciphertext = self._session.encrypt(reply)
        await self.sub.publish(envelope_to_json(WireEnvelope(self._channel_id, KIND_DATA, ciphertext)))
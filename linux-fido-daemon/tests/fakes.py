"""Shared in-memory Centrifugo fakes and a stub phone peer for tests."""

import json

from centrifuge import Publication, PublicationContext, SubscriptionEventHandler

from fido_daemon.crypto import AesGcmCipher, message_from_json, message_to_json

CHANNEL_ID = "0123456789abcdef0123456789abcdef"
RELAY_URL = "ws://localhost:8000/connection/websocket"


class FakeBroker:
    """In-memory stand-in for a Centrifugo broker.

    Subscribers to the same channel receive each other's publications (no echo
    back to the publisher, matching Centrifugo's default `echo=False`).
    """

    def __init__(self) -> None:
        self._subscribers: dict[str, list["FakeSubscription"]] = {}

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

    async def subscribe(self) -> None:
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
    def __init__(self, phone: "StubPhone") -> None:
        self._phone = phone

    async def on_publication(self, ctx: PublicationContext) -> None:
        await self._phone._handle(ctx)


class StubPhone:
    """A stand-in phone peer that opens requests and replies with a canned
    response produced by `responder(request_dict) -> response_dict`."""

    def __init__(
        self, broker: FakeBroker, channel_id: str, cipher: AesGcmCipher, responder
    ) -> None:
        self._broker = broker
        self._channel_id = channel_id
        self._channel = f"fidobridge.{channel_id}"
        self._cipher = cipher
        self._responder = responder
        self.client: FakeClient | None = None
        self.sub: FakeSubscription | None = None
        self.received: list[dict] = []

    async def start(self) -> None:
        self.client = self._broker.new_client()
        await self.client.connect()
        self.sub = self.client.new_subscription(self._channel, events=_PhoneHandler(self))
        await self.sub.subscribe()

    async def _handle(self, ctx: PublicationContext) -> None:
        wire = message_from_json(ctx.pub.data)
        plaintext = self._cipher.open(wire)
        request = json.loads(plaintext.decode("utf-8"))
        self.received.append(request)
        response = self._responder(request)
        if response is None:
            return
        reply = json.dumps(response).encode("utf-8")
        sealed = self._cipher.seal(self._channel_id, reply)
        await self.sub.publish(message_to_json(sealed))

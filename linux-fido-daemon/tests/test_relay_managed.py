"""Managed relay subscribe-polling tests (managed-relay plan, phase C).

In managed mode the daemon connects low-priv and polls ``subscribe()`` with
backoff until the backend subscribe proxy allows it. Classic mode keeps the
current one-shot subscribe (fail closed on denial).
"""

import asyncio
import json

import pytest

from fido_daemon.noise import StaticKeyStore
from fido_daemon.relay import RelayClient, SubscribeDeniedError
from tests.fakes import CHANNEL_ID, NoisePhonePeer

RELAY_URL = "ws://localhost:8000/connection/websocket"
DAEMON_PRIVATE = bytes(range(32))
PHONE_PRIVATE = bytes(range(32, 64))
DAEMON_PUBLIC = StaticKeyStore.public_key(DAEMON_PRIVATE)
FAST_BACKOFF = (0.01, 0.01, 0.01)


def _request(message_id: str) -> bytes:
    return json.dumps(
        {"version": 3, "type": "getAssertion", "id": message_id, "payload": {}}
    ).encode()


def _echo_responder(request: dict) -> dict:
    return {
        "version": 3,
        "type": "assertionResult",
        "id": request["id"],
        "payload": {"credentialId": "Y3JlZA", "authenticatorData": "", "signature": ""},
    }


async def _wait_until(predicate, timeout: float = 2.0) -> None:
    deadline = asyncio.get_event_loop().time() + timeout
    while not predicate():
        if asyncio.get_event_loop().time() > deadline:
            raise TimeoutError("condition not met in time")
        await asyncio.sleep(0.005)


async def test_managed_polls_until_subscribe_allowed(broker) -> None:
    broker.deny_subscribe = True
    relay = RelayClient(
        RELAY_URL,
        CHANNEL_ID,
        DAEMON_PRIVATE,
        managed=True,
        subscribe_backoff=FAST_BACKOFF,
        client_factory=broker.new_client,
    )
    connect = asyncio.create_task(relay.connect())

    await _wait_until(lambda: relay._sub is not None and relay._sub.subscribe_attempts >= 1)
    broker.deny_subscribe = False
    await connect

    assert relay._sub.subscribe_attempts >= 2
    assert relay._sub.subscribed is True

    phone = NoisePhonePeer(
        broker, CHANNEL_ID, DAEMON_PUBLIC, lambda r: None, static_private=PHONE_PRIVATE
    )
    await phone.start()
    assert relay._session is not None


async def test_managed_keeps_polling_while_denied(broker) -> None:
    broker.deny_subscribe = True
    relay = RelayClient(
        RELAY_URL,
        CHANNEL_ID,
        DAEMON_PRIVATE,
        managed=True,
        subscribe_backoff=FAST_BACKOFF,
        client_factory=broker.new_client,
    )
    connect = asyncio.create_task(relay.connect())

    await _wait_until(lambda: relay._sub is not None and relay._sub.subscribe_attempts >= 1)
    await asyncio.sleep(0.05)
    assert relay._sub.subscribe_attempts >= 2

    connect.cancel()
    with pytest.raises(asyncio.CancelledError):
        await connect


async def test_managed_connects_single_attempt_when_allowed(broker) -> None:
    relay = RelayClient(
        RELAY_URL,
        CHANNEL_ID,
        DAEMON_PRIVATE,
        managed=True,
        client_factory=broker.new_client,
    )
    await relay.connect()

    assert relay._sub.subscribe_attempts == 1
    assert relay._sub.subscribed is True


async def test_classic_single_subscribe_fails_closed_on_denial(broker) -> None:
    broker.deny_subscribe = True
    relay = RelayClient(
        RELAY_URL,
        CHANNEL_ID,
        DAEMON_PRIVATE,
        client_factory=broker.new_client,
    )
    with pytest.raises(SubscribeDeniedError):
        await relay.connect()

    assert relay._sub.subscribe_attempts == 1


async def test_managed_request_round_trips_after_polled_subscribe(broker) -> None:
    broker.deny_subscribe = True
    relay = RelayClient(
        RELAY_URL,
        CHANNEL_ID,
        DAEMON_PRIVATE,
        managed=True,
        subscribe_backoff=FAST_BACKOFF,
        client_factory=broker.new_client,
    )
    connect = asyncio.create_task(relay.connect())

    await _wait_until(lambda: relay._sub is not None and relay._sub.subscribe_attempts >= 1)
    broker.deny_subscribe = False
    await connect

    phone = NoisePhonePeer(
        broker, CHANNEL_ID, DAEMON_PUBLIC, _echo_responder, static_private=PHONE_PRIVATE
    )
    await phone.start()
    await phone.wait_ready()

    response = await relay.request(_request("managed-1"))
    assert json.loads(response.decode())["id"] == "managed-1"
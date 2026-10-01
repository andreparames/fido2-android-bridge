import asyncio
import json

import pytest

from fido_daemon.noise import (
    KIND_DATA,
    KIND_IK2,
    StaticKeyStore,
    WireEnvelope,
    envelope_to_json,
)
from fido_daemon.relay import RelayClient
from tests.fakes import CHANNEL_ID, RELAY_URL, NoisePhonePeer

DAEMON_PRIVATE = bytes(range(32))
PHONE_PRIVATE = bytes(range(32, 64))
DAEMON_PUBLIC = StaticKeyStore.public_key(DAEMON_PRIVATE)
PHONE_PUBLIC = StaticKeyStore.public_key(PHONE_PRIVATE)


def _request(message_id: str = "req-1") -> bytes:
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


def _relay(broker, *, phone_public_key=None) -> RelayClient:
    return RelayClient(
        RELAY_URL,
        CHANNEL_ID,
        DAEMON_PRIVATE,
        phone_public_key=phone_public_key,
        client_factory=broker.new_client,
    )


async def test_request_roundtrip(broker) -> None:
    relay = _relay(broker)
    await relay.connect()
    phone = NoisePhonePeer(
        broker, CHANNEL_ID, DAEMON_PUBLIC, _echo_responder, static_private=PHONE_PRIVATE
    )
    await phone.start()

    response = await relay.request(_request("req-1"))
    assert json.loads(response.decode())["id"] == "req-1"
    assert phone.received[0]["id"] == "req-1"
    assert phone.received[0]["type"] == "getAssertion"


async def test_request_publishes_to_channel(broker) -> None:
    relay = _relay(broker)
    await relay.connect()
    phone = NoisePhonePeer(
        broker, CHANNEL_ID, DAEMON_PUBLIC, _echo_responder, static_private=PHONE_PRIVATE
    )
    await phone.start()

    await relay.request(_request("req-2"))
    assert relay.channel == f"fidobridge.{CHANNEL_ID}"
    assert phone.received[0]["id"] == "req-2"


async def test_relay_learns_phone_static_key(broker) -> None:
    relay = _relay(broker)
    await relay.connect()
    phone = NoisePhonePeer(
        broker, CHANNEL_ID, DAEMON_PUBLIC, _echo_responder, static_private=PHONE_PRIVATE
    )
    await phone.start()

    assert relay._session.remote_static == PHONE_PUBLIC


async def test_tampered_publication_flagged(broker) -> None:
    relay = _relay(broker)
    await relay.connect()
    phone = NoisePhonePeer(
        broker, CHANNEL_ID, DAEMON_PUBLIC, _echo_responder, static_private=PHONE_PRIVATE
    )
    await phone.start()
    await phone.wait_ready()

    ct = phone._session.encrypt(b"payload")
    tampered = WireEnvelope(CHANNEL_ID, KIND_DATA, b"\xff" + ct[1:])
    await relay._sub.deliver(envelope_to_json(tampered))

    assert relay.tampered is True


async def test_ignores_own_ik2_kind(broker) -> None:
    relay = _relay(broker)
    await relay.connect()
    await relay._sub.deliver(envelope_to_json(WireEnvelope(CHANNEL_ID, KIND_IK2, b"\x00" * 48)))

    assert relay.tampered is False
    assert relay._session is None


async def test_request_times_out_without_phone(broker) -> None:
    relay = _relay(broker)
    await relay.connect()
    with pytest.raises(asyncio.TimeoutError):
        await relay.request(_request("req-timeout"), timeout=0.05)


async def test_rejects_handshake_from_unpinned_phone(broker) -> None:
    relay = _relay(broker, phone_public_key=PHONE_PUBLIC)
    await relay.connect()
    other_private = bytes(range(100, 132))
    phone = NoisePhonePeer(
        broker, CHANNEL_ID, DAEMON_PUBLIC, _echo_responder, static_private=other_private
    )
    await phone.start()

    assert relay.tampered is True
    assert relay._session is None
    with pytest.raises(asyncio.TimeoutError):
        await relay.request(_request("req-x"), timeout=0.05)


async def test_tofu_learns_and_pins_phone_key(broker) -> None:
    relay = _relay(broker)  # no explicit pin: trust-on-first-use
    await relay.connect()
    phone = NoisePhonePeer(
        broker, CHANNEL_ID, DAEMON_PUBLIC, _echo_responder, static_private=PHONE_PRIVATE
    )
    await phone.start()

    assert relay.phone_public_key == PHONE_PUBLIC

    # A different phone is now rejected.
    other_private = bytes(range(100, 132))
    other = NoisePhonePeer(
        broker, CHANNEL_ID, DAEMON_PUBLIC, _echo_responder, static_private=other_private
    )
    await other.start()
    assert relay.tampered is True

    # The original phone still works.
    response = await relay.request(_request("req-1"))
    assert json.loads(response.decode())["id"] == "req-1"


async def test_tofu_callback_reports_learned_key(broker) -> None:
    captured: list[bytes] = []
    relay = RelayClient(
        RELAY_URL,
        CHANNEL_ID,
        DAEMON_PRIVATE,
        on_phone_identified=captured.append,
        client_factory=broker.new_client,
    )
    await relay.connect()
    phone = NoisePhonePeer(
        broker, CHANNEL_ID, DAEMON_PUBLIC, _echo_responder, static_private=PHONE_PRIVATE
    )
    await phone.start()

    assert captured == [PHONE_PUBLIC]
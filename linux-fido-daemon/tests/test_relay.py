import asyncio
import json

import pytest

from fido_daemon.crypto import AesGcmCipher, WireMessage, message_to_json
from fido_daemon.relay import RelayClient
from tests.fakes import CHANNEL_ID, RELAY_URL, StubPhone


def _request(message_id: str = "req-1") -> bytes:
    return json.dumps(
        {"version": 1, "type": "getAssertion", "id": message_id, "payload": {}}
    ).encode()


def _echo_responder(request: dict) -> dict:
    return {
        "version": 1,
        "type": "assertionResult",
        "id": request["id"],
        "payload": {"credentialId": "Y3JlZA", "authenticatorData": "", "signature": ""},
    }


async def test_request_roundtrip(cipher: AesGcmCipher, broker) -> None:
    phone = StubPhone(broker, CHANNEL_ID, cipher, _echo_responder)
    await phone.start()
    relay = RelayClient(RELAY_URL, CHANNEL_ID, cipher, client_factory=broker.new_client)
    await relay.connect()

    response = await relay.request(_request("req-1"))
    assert json.loads(response.decode())["id"] == "req-1"
    assert phone.received[0]["id"] == "req-1"
    assert phone.received[0]["type"] == "getAssertion"


async def test_request_publishes_sealed_to_channel(cipher: AesGcmCipher, broker) -> None:
    phone = StubPhone(broker, CHANNEL_ID, cipher, _echo_responder)
    await phone.start()
    relay = RelayClient(RELAY_URL, CHANNEL_ID, cipher, client_factory=broker.new_client)
    await relay.connect()

    await relay.request(_request("req-2"))
    assert relay.channel == f"fidobridge.{CHANNEL_ID}"
    assert phone.received[0]["id"] == "req-2"


async def test_tampered_publication_flagged(cipher: AesGcmCipher, broker) -> None:
    relay = RelayClient(RELAY_URL, CHANNEL_ID, cipher, client_factory=broker.new_client)
    await relay.connect()

    sealed = cipher.seal(CHANNEL_ID, _request("req-t"))
    tampered = WireMessage(sealed.channel_id, sealed.nonce, b"evil", sealed.tag)
    await relay._sub.deliver(message_to_json(tampered))

    assert relay.tampered is True


async def test_request_times_out(cipher: AesGcmCipher, broker) -> None:
    relay = RelayClient(RELAY_URL, CHANNEL_ID, cipher, client_factory=broker.new_client)
    await relay.connect()
    with pytest.raises(asyncio.TimeoutError):
        await relay.request(_request("req-timeout"), timeout=0.01)

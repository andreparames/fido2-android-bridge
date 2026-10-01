import pytest

from fido_daemon.noise import StaticKeyStore
from tests.fakes import FakeBroker, FakeClient

DAEMON_STATIC_PRIVATE = bytes(range(32))
PHONE_STATIC_PRIVATE = bytes(range(32, 64))


@pytest.fixture
def daemon_static_private() -> bytes:
    return DAEMON_STATIC_PRIVATE


@pytest.fixture
def daemon_static_public() -> bytes:
    return StaticKeyStore.public_key(DAEMON_STATIC_PRIVATE)


@pytest.fixture
def phone_static_private() -> bytes:
    return PHONE_STATIC_PRIVATE


@pytest.fixture
def broker() -> FakeBroker:
    return FakeBroker()


@pytest.fixture
def fake_client(broker: FakeBroker) -> FakeClient:
    return broker.new_client()
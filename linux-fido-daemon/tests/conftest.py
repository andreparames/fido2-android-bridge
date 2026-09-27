import pytest

from fido_daemon.crypto import AesGcmCipher, SecretKey
from tests.fakes import FakeBroker, FakeClient


@pytest.fixture
def cipher() -> AesGcmCipher:
    return AesGcmCipher(SecretKey(bytes(range(32))))


@pytest.fixture
def broker() -> FakeBroker:
    return FakeBroker()


@pytest.fixture
def fake_client(broker: FakeBroker) -> FakeClient:
    return broker.new_client()

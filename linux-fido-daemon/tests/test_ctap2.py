import base64

import fido2.cbor as cbor
import pytest

from fido_daemon.ctap2 import (
    CMD_GET_ASSERTION,
    CMD_MAKE_CREDENTIAL,
    Ctap2Error,
    decode_request_frame,
    encode_response,
    error_response,
    parse_request,
)
from fido_daemon.protocol import (
    COSE_ES256,
    CTAP2_ERR_INVALID_COMMAND,
    CTAP2_ERR_OPERATION_DENIED,
    CTAP2_ERR_UNSUPPORTED_ALGORITHM,
)

CLIENT_DATA_HASH = b"\x11" * 32


def _b64(data: bytes) -> str:
    return base64.b64encode(data).decode().rstrip("=")


def test_parse_get_assertion() -> None:
    allow_list = [
        {"id": b"cred1", "type": "public-key"},
        {"id": b"cred2", "type": "public-key"},
    ]
    data = cbor.encode(
        {
            1: "example.com",
            2: CLIENT_DATA_HASH,
            3: allow_list,
            5: {"up": True, "uv": False},
        }
    )
    msg = parse_request(CMD_GET_ASSERTION, data, request_id="test-id")
    assert msg.to_dict()["type"] == "getAssertion"
    assert msg.to_dict()["version"] == 2
    assert msg.id == "test-id"
    payload = msg.to_dict()["payload"]
    assert payload["clientDataHash"] == _b64(CLIENT_DATA_HASH)
    assert payload["rpId"] == "example.com"
    assert payload["allowCredentials"] == [_b64(b"cred1"), _b64(b"cred2")]
    assert payload["option"] == {"up": True, "uv": False}


def test_parse_get_assertion_without_optional_fields() -> None:
    data = cbor.encode({1: "example.com", 2: CLIENT_DATA_HASH})
    msg = parse_request(CMD_GET_ASSERTION, data)
    payload = msg.to_dict()["payload"]
    assert payload["allowCredentials"] == []
    assert "option" not in payload


def test_parse_make_credential() -> None:
    user = {"id": b"user-id", "name": "alice@example.com", "displayName": "Alice"}
    rp = {"id": "example.com", "name": "Example"}
    pubkey = [{"alg": -7, "type": "public-key"}, {"alg": -257, "type": "public-key"}]
    exclude = [{"id": b"excl", "type": "public-key"}]
    data = cbor.encode({1: CLIENT_DATA_HASH, 2: rp, 3: user, 4: pubkey, 5: exclude})
    msg = parse_request(CMD_MAKE_CREDENTIAL, data, request_id="id2")
    assert msg.to_dict()["type"] == "makeCredential"
    payload = msg.to_dict()["payload"]
    assert payload["clientDataHash"] == _b64(CLIENT_DATA_HASH)
    assert payload["rpId"] == "example.com"
    assert payload["user"] == {
        "id": _b64(b"user-id"),
        "name": "alice@example.com",
        "displayName": "Alice",
    }
    assert payload["pubKeyCredParams"] == [{"alg": -7}, {"alg": -257}]
    assert payload["excludeCredentials"] == [_b64(b"excl")]


def test_default_pub_key_cred_params() -> None:
    data = cbor.encode(
        {1: CLIENT_DATA_HASH, 2: {"id": "example.com"}, 3: {"id": b"u", "name": "n", "displayName": "d"}}
    )
    msg = parse_request(CMD_MAKE_CREDENTIAL, data)
    assert msg.to_dict()["payload"]["pubKeyCredParams"] == [{"alg": COSE_ES256}]


def test_unsupported_algorithm_rejected() -> None:
    data = cbor.encode(
        {
            1: CLIENT_DATA_HASH,
            2: {"id": "example.com"},
            3: {"id": b"u", "name": "n", "displayName": "d"},
            4: [{"alg": -257, "type": "public-key"}],
        }
    )
    with pytest.raises(Ctap2Error) as excinfo:
        parse_request(CMD_MAKE_CREDENTIAL, data)
    assert excinfo.value.code == CTAP2_ERR_UNSUPPORTED_ALGORITHM == 0x26


def test_unknown_command_raises_invalid_command() -> None:
    with pytest.raises(Ctap2Error) as excinfo:
        parse_request(0x99, b"")
    assert excinfo.value.code == CTAP2_ERR_INVALID_COMMAND == 0x01


def test_malformed_cbor_raises_invalid_command() -> None:
    with pytest.raises(Ctap2Error) as excinfo:
        parse_request(CMD_GET_ASSERTION, b"\xff\xff\xff")
    assert excinfo.value.code == CTAP2_ERR_INVALID_COMMAND


def test_error_response_invalid_command_byte() -> None:
    assert error_response(CTAP2_ERR_INVALID_COMMAND) == b"\x01"


def test_error_response_operation_denied_byte() -> None:
    assert CTAP2_ERR_OPERATION_DENIED == 0x27
    assert error_response(CTAP2_ERR_OPERATION_DENIED) == b"\x27"


def test_decode_request_frame() -> None:
    data = bytes([CMD_GET_ASSERTION]) + cbor.encode(
        {1: "example.com", 2: CLIENT_DATA_HASH}
    )
    message = decode_request_frame(data)
    assert message.type == "getAssertion"
    assert message.payload["rpId"] == "example.com"


def test_decode_empty_request_frame_raises() -> None:
    with pytest.raises(Ctap2Error) as excinfo:
        decode_request_frame(b"")
    assert excinfo.value.code == CTAP2_ERR_INVALID_COMMAND


def test_encode_assertion_response() -> None:
    auth_data = b"\x00" * 37
    reply = encode_response(
        {
            "type": "assertionResult",
            "payload": {
                "credentialId": _b64(b"cred"),
                "authenticatorData": _b64(auth_data),
                "signature": _b64(b"sig"),
            },
        }
    )
    assert reply[0] == 0x00
    decoded = cbor.decode(reply[1:])
    assert decoded[1] == {"id": b"cred", "type": "public-key"}
    assert decoded[2] == auth_data
    assert decoded[3] == b"sig"


def test_encode_make_credential_response() -> None:
    auth_data = b"\x00" * 37
    attestation = {"fmt": "none", "attStmt": {}, "authData": auth_data}
    reply = encode_response(
        {
            "type": "makeCredentialResult",
            "payload": {
                "credentialId": _b64(b"cred"),
                "authenticatorData": _b64(auth_data),
                "attestationObject": _b64(cbor.encode(attestation)),
                "signature": _b64(b"sig"),
            },
        }
    )
    assert reply[0] == 0x00
    decoded = cbor.decode(reply[1:])
    assert decoded[1] == "none"
    assert decoded[2] == auth_data
    assert decoded[3] == {}


def test_encode_error_response() -> None:
    reply = encode_response({"type": "error", "payload": {"code": 0x27}})
    assert reply == b"\x27"

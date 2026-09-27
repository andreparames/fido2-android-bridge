"""CTAP2 request interception.

Translates raw `authenticatorGetAssertion` / `authenticatorMakeCredential`
CBOR requests into the daemon <-> phone plaintext message schema (PROTOCOL.md
§4/§5), and encodes CTAP2 status-byte responses for the local client.
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass

import fido2.cbor as cbor

from fido_daemon.crypto import b64decode, b64encode
from fido_daemon.protocol import (
    COSE_ES256,
    CTAP2_ERR_INVALID_COMMAND,
    CTAP2_ERR_UNSUPPORTED_ALGORITHM,
    CTAP2_OK,
    PROTOCOL_VERSION,
    TYPE_ASSERTION_RESULT,
    TYPE_ERROR,
    TYPE_GET_ASSERTION,
    TYPE_MAKE_CREDENTIAL,
    TYPE_MAKE_CREDENTIAL_RESULT,
)

CMD_MAKE_CREDENTIAL = 0x01
CMD_GET_ASSERTION = 0x02

# CTAP2 request CBOR integer keys (CTAP2 spec §6).
_GA_RPID = 0x01
_GA_CLIENT_DATA_HASH = 0x02
_GA_ALLOW_LIST = 0x03
_GA_OPTIONS = 0x05

_MC_CLIENT_DATA_HASH = 0x01
_MC_RP = 0x02
_MC_USER = 0x03
_MC_PUB_KEY_CRED_PARAMS = 0x04
_MC_EXCLUDE_LIST = 0x05


class Ctap2Error(Exception):
    """A CTAP2 status code to return to the local client."""

    def __init__(self, code: int, message: str = "") -> None:
        self.code = code
        super().__init__(message or f"CTAP2 error 0x{code:02x}")


@dataclass(frozen=True)
class Message:
    type: str
    payload: dict
    id: str

    def to_dict(self) -> dict:
        return {
            "version": PROTOCOL_VERSION,
            "type": self.type,
            "id": self.id,
            "payload": self.payload,
        }


def parse_request(command: int, data: bytes, request_id: str | None = None) -> Message:
    if command == CMD_GET_ASSERTION:
        return _parse_get_assertion(data, request_id or str(uuid.uuid4()))
    if command == CMD_MAKE_CREDENTIAL:
        return _parse_make_credential(data, request_id or str(uuid.uuid4()))
    raise Ctap2Error(CTAP2_ERR_INVALID_COMMAND, f"unsupported command 0x{command:02x}")


def error_response(code: int) -> bytes:
    """Encode a CTAP2 status code as the single-byte error response."""
    return bytes([code])


def _decode_cbor(data: bytes) -> dict:
    try:
        decoded = cbor.decode(data)
    except Exception as exc:
        raise Ctap2Error(CTAP2_ERR_INVALID_COMMAND, "malformed CBOR request") from exc
    if not isinstance(decoded, dict):
        raise Ctap2Error(CTAP2_ERR_INVALID_COMMAND, "request must be a CBOR map")
    return decoded


def _credential_id(entry) -> str:
    return b64encode(entry["id"])


def _normalize_options(options: dict) -> dict:
    result: dict = {}
    for key in ("up", "uv"):
        if key in options:
            result[key] = bool(options[key])
    return result


def _parse_get_assertion(data: bytes, request_id: str) -> Message:
    req = _decode_cbor(data)
    rp_id = req.get(_GA_RPID)
    client_data_hash = req.get(_GA_CLIENT_DATA_HASH)
    if not isinstance(rp_id, str) or not isinstance(client_data_hash, bytes):
        raise Ctap2Error(CTAP2_ERR_INVALID_COMMAND, "getAssertion: missing rpId/clientDataHash")
    if len(client_data_hash) != 32:
        raise Ctap2Error(CTAP2_ERR_INVALID_COMMAND, "getAssertion: clientDataHash must be 32 bytes")

    allow_credentials = [_credential_id(entry) for entry in req.get(_GA_ALLOW_LIST, [])]
    payload = {
        "clientDataHash": b64encode(client_data_hash),
        "rpId": rp_id,
        "allowCredentials": allow_credentials,
    }
    options = req.get(_GA_OPTIONS)
    if options is not None:
        payload["option"] = _normalize_options(options)
    return Message(type=TYPE_GET_ASSERTION, payload=payload, id=request_id)


def _pub_key_cred_params(params) -> list[dict]:
    if not params:
        return [{"alg": COSE_ES256}]
    algs = [entry["alg"] for entry in params]
    if COSE_ES256 not in algs:
        raise Ctap2Error(
            CTAP2_ERR_UNSUPPORTED_ALGORITHM, "only ES256 (-7) is supported"
        )
    return [{"alg": alg} for alg in algs]


def _parse_make_credential(data: bytes, request_id: str) -> Message:
    req = _decode_cbor(data)
    client_data_hash = req.get(_MC_CLIENT_DATA_HASH)
    rp = req.get(_MC_RP)
    user = req.get(_MC_USER)
    if not isinstance(client_data_hash, bytes) or len(client_data_hash) != 32:
        raise Ctap2Error(
            CTAP2_ERR_INVALID_COMMAND, "makeCredential: clientDataHash must be 32 bytes"
        )
    if not isinstance(rp, dict) or not isinstance(rp.get("id"), str):
        raise Ctap2Error(CTAP2_ERR_INVALID_COMMAND, "makeCredential: missing rp.id")
    if not isinstance(user, dict) or not isinstance(user.get("id"), bytes):
        raise Ctap2Error(CTAP2_ERR_INVALID_COMMAND, "makeCredential: missing user.id")

    pub_key_cred_params = _pub_key_cred_params(req.get(_MC_PUB_KEY_CRED_PARAMS))
    exclude_credentials = [
        _credential_id(entry) for entry in req.get(_MC_EXCLUDE_LIST, [])
    ]

    payload = {
        "clientDataHash": b64encode(client_data_hash),
        "rpId": rp["id"],
        "user": {
            "id": b64encode(user["id"]),
            "name": user.get("name", ""),
            "displayName": user.get("displayName", ""),
        },
        "pubKeyCredParams": pub_key_cred_params,
        "excludeCredentials": exclude_credentials,
    }
    return Message(type=TYPE_MAKE_CREDENTIAL, payload=payload, id=request_id)


def decode_request_frame(data: bytes) -> Message:
    """Decode a socket frame: one command byte followed by CBOR request data."""
    if not data:
        raise Ctap2Error(CTAP2_ERR_INVALID_COMMAND, "empty request")
    return parse_request(data[0], data[1:])


def encode_response(message: dict) -> bytes:
    """Encode a phone plaintext message (PROTOCOL.md §4) into a CTAP2 reply.

    Success replies are a leading status byte ``0x00`` followed by CBOR; error
    replies are a single status byte.
    """
    msg_type = message["type"]
    if msg_type == TYPE_ASSERTION_RESULT:
        body = _encode_assertion_response(message["payload"])
    elif msg_type == TYPE_MAKE_CREDENTIAL_RESULT:
        body = _encode_make_credential_response(message["payload"])
    elif msg_type == TYPE_ERROR:
        return error_response(message["payload"]["code"])
    else:
        raise ValueError(f"cannot encode a response of type {msg_type!r}")
    return bytes([CTAP2_OK]) + cbor.encode(body)


def _encode_assertion_response(payload: dict) -> dict:
    response = {
        1: {"id": b64decode(payload["credentialId"]), "type": "public-key"},
        2: b64decode(payload["authenticatorData"]),
        3: b64decode(payload["signature"]),
    }
    if payload.get("userHandle") is not None:
        response[4] = {"id": b64decode(payload["userHandle"])}
    return response


def _encode_make_credential_response(payload: dict) -> dict:
    attestation = cbor.decode(b64decode(payload["attestationObject"]))
    return {
        1: attestation["fmt"],
        2: attestation["authData"],
        3: attestation["attStmt"],
    }

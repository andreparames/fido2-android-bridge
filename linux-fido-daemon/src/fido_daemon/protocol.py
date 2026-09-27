"""Versioned protocol constants for the daemon <-> phone schema (PROTOCOL.md).

These are frozen at PROTOCOL_VERSION and mirrored by the Android peer's
`Protocol.kt`. Do not mutate a published value; bump PROTOCOL_VERSION for
breaking changes.
"""

from __future__ import annotations

PROTOCOL_VERSION = 1

# Message types (PROTOCOL.md §4.1 / §5).
TYPE_GET_ASSERTION = "getAssertion"
TYPE_MAKE_CREDENTIAL = "makeCredential"
TYPE_ASSERTION_RESULT = "assertionResult"
TYPE_MAKE_CREDENTIAL_RESULT = "makeCredentialResult"
TYPE_ERROR = "error"
TYPE_PING = "ping"

MESSAGE_TYPES = frozenset(
    {
        TYPE_GET_ASSERTION,
        TYPE_MAKE_CREDENTIAL,
        TYPE_ASSERTION_RESULT,
        TYPE_MAKE_CREDENTIAL_RESULT,
        TYPE_ERROR,
        TYPE_PING,
    }
)

# CTAP2 status codes (PROTOCOL.md §5.5, aligned with the CTAP2 spec).
CTAP2_OK = 0x00
CTAP2_ERR_INVALID_COMMAND = 0x01
CTAP2_ERR_UNSUPPORTED_ALGORITHM = 0x26
CTAP2_ERR_OPERATION_DENIED = 0x27
CTAP2_ERR_INVALID_OPTION = 0x2C
VERSION_MISMATCH = 0x7F

# COSE algorithm identifier for ES256 / P-256 (the only supported algorithm).
COSE_ES256 = -7

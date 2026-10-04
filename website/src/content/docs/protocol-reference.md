---
title: "Protocol reference"
description: "Wire format, pairing link, encryption envelope, and message schemas."
order: 3
---

Gatebridge's message protocol is specified in full in
[`PROTOCOL.md`](https://github.com/andreparames/fido2-android-bridge/blob/main/PROTOCOL.md)
in the repository. This page is a summary.

## Pairing link

```
fidobridge://pair?channel=<hex>&pubkey=<base64url>
```

| Field | Description |
|---|---|
| `channel` | 128-bit channel ID (hex) |
| `pubkey` | Program's static public key (base64url) |

## Encryption

- **Protocol:** Noise (Noise_IK_25519_AESGCM_SHA256), an open standard for authenticated encryption
- **Session keys:** Derived per connection from ephemeral keys (perfect forward secrecy)
- **Relay payload:** Opaque encrypted frames only. The relay never sees plaintext.

## Message types

| Type | Direction | Purpose |
|---|---|---|
| `getAssertion` | Server to phone | Sign-in request |
| `makeCredential` | Server to phone | Create a new credential |
| `assertionResult` | Phone to server | Signed sign-in response |
| `credentialResult` | Phone to server | Created credential |
| `error` | Either | Error codes |
| `ping` | Either | Liveness check |

## Error codes

Standard error codes are used. Notable cases:

- `CTAP2_ERR_OPERATION_DENIED`: biometric check failed or user cancelled
- `CTAP2_ERR_INVALID_CREDENTIAL`: credential ID not found
- `CTAP2_ERR_PIN_AUTH_INVALID`: pairing or handshake failure

## Authenticator data

Authenticator data follows the open web authentication standard:
- `rpIdHash` (32 bytes)
- `flags` (user present, user verified, attested, etc.)
- `counter` (4 bytes)
- `attestedCredentialData` (on makeCredential)

See `PROTOCOL.md` for the canonical JSON Schemas and full grammar.

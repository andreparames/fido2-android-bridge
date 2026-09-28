# PROTOCOL.md — Daemon ↔ Android Bridge Protocol (v1)

This document is the single source of truth for the communication formats
between the **Linux daemon** (`linux-fido-daemon`) and the **Android client**
(`android-fido-client`). It covers only the two interactions those peers have
with each other:

1. **Pairing** — one-time session-key exchange (daemon → phone).
2. **Request/Response relay** — sealed CTAP2-style messages over the
   WebSocket relay (bidirectional).

Out of scope: the local Unix socket, CTAP2 CBOR decoding, and any
daemon-internal detail. See `ANDROID_PLAN.md` and `DAEMON_PLAN.md` for those.

Security posture (from `agents.md`): the relay is **untrusted**; every payload
is AES-256-GCM sealed before touching the network; private keys never leave the
phone's TEE/StrongBox.

---

## 1. Versioning

The protocol is versioned as a whole (`PROTOCOL_VERSION = 2`). Both peers must
agree on the version or refuse to communicate. The version travels in the
plaintext header (Section 4) and is also embedded in the pairing URI
(optional, `v` parameter).

Schema constants are frozen at the emitted version; any breaking change bumps
the version rather than mutating a published field.

Version 2 adds the optional `token` parameter to the pairing URI, carrying a
Centrifugo connection JWT so the relay token can rotate without rebuilding
clients.

---

## 2. Pairing URI

Generated once by the daemon (`fido-daemon pair`), displayed as a QR code or
terminal string, scanned/pasted into the Android app.

### 2.1 ABNF

```abnf
pairing-uri = "fidobridge://pair" "?" pair-params
pair-params = "channel=" channel-id "&" "key=" b64-key ["&" "token=" token] ["&" "v=" version]
channel-id  = 32 LCHEXDIG      ; 16 bytes, 128-bit channel identifier
b64-key     = 43 BASE64URL     ; 32 bytes of key material, base64url, no padding
token       = 1*( ALPHA / DIGIT / "-" / "_" / "." ) ; Centrifugo connection JWT, compact serialization
version     = "1" / "2"
LCHEXDIG    = %x30-39 / %x61-66 ; lowercase hex digit: 0-9 a-f
```

`BASE64URL` means standard base64 with URL-safe alphabet (`-`/`_`) and **no
padding**, per the "no URL-safe vs standard mixing" rule in both plans.

### 2.2 Parameters

| Parameter | Encoding | Length (bytes) | Purpose |
|-----------|----------|----------------|---------|
| `channel` | hex (lowercase) | 16 | Channel identifier |
| `key`     | base64url, unpadded | 32 | AES-256 session key `K_session` |
| `token`   | JWT compact serialization | variable | Optional Centrifugo connection token |
| `v`       | decimal string | – | Protocol version (optional, default `1`) |

### 2.3 Validation rules

- Missing `channel` or `key` → reject.
- `key` must decode to exactly 32 bytes.
- `channel` must be exactly 32 **lowercase** hex chars (`[0-9a-f]{32}`); uppercase
  hex is rejected, per the `channel_id` derivation in §3.2.
- `token` is optional; if present, must be a non-empty string (a Centrifugo
  connection JWT signed with the relay's `hmac_secret_key`).
- If `v` is present and not equal to `PROTOCOL_VERSION` (`2`) → reject with
  `VERSION_MISMATCH` (0x7F), mirroring the version check in §4.1. Absent `v`
  defaults to `1`.
- Unknown/duplicate parameters → reject (fail-safe, no silent nulls).

The phone stores `K_session` in `EncryptedSharedPreferences`. The daemon keeps
`K_session` in memory only.

---

## 3. Wire Envelope (on the relay)

Every message sent over the WebSocket — either direction — is exactly this JSON
object. The relay only sees this envelope.

```json
{
  "channel_id": "HASHED_CHANNEL_ID_STRING",
  "nonce": "BASE64_12BYTE_GCM_NONCE",
  "ciphertext": "BASE64_AES_256_GCM_PAYLOAD",
  "tag": "BASE64_16BYTE_AUTH_TAG"
}
```

### 3.1 JSON Schema (2020-12)

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://fidobridge.dev/schema/wire-message/v1.schema.json",
  "title": "WireMessage",
  "type": "object",
  "additionalProperties": false,
  "required": ["channel_id", "nonce", "ciphertext", "tag"],
  "properties": {
    "channel_id": {
      "type": "string",
      "description": "Channel identifier. NOT the raw 128-bit id from the URI: it is a stable hash/digest of it so the raw id never appears on the wire (see 3.2).",
      "pattern": "^[0-9a-f]{32}$"
    },
    "nonce": {
      "type": "string",
      "description": "12-byte AES-GCM nonce, base64 (standard) encoded, unpadded — always exactly 16 chars.",
      "pattern": "^[A-Za-z0-9+/]{16}$"
    },
    "ciphertext": {
      "type": "string",
      "description": "Encrypted plaintext message (Section 4), base64 encoded."
    },
    "tag": {
      "type": "string",
      "description": "16-byte AES-GCM authentication tag, base64 encoded, unpadded — always exactly 22 chars.",
      "pattern": "^[A-Za-z0-9+/]{22}$"
    }
  }
}
```

### 3.2 Rules

- **Base64:** standard alphabet everywhere on the wire; base64url only in the
  pairing URI. Centralized in one helper per peer — no mixing.
- **Nonce:** fresh, cryptographically secure 12 bytes per message; never
  reused.
- **Channel id:** the raw 128-bit id must not appear in plaintext on the
  relay. Both peers derive it identically at pairing time and freeze it:
  `channel_id = lowercase hex( SHA-256( channel_hex_utf8 )[0:16] )` — i.e. the
  first 16 bytes of SHA-256 over the 32-char lowercase hex channel string from
  the URI, encoded as 32 lowercase hex chars. It is used for both the
  `channel_id` envelope field and WebSocket routing. Derivation is pinned;
  do not substitute another hash/encoding.
- **Tag failure:** GCM authentication failure aborts the message, flags the
  connection, and logs a security alert. The connection may be dropped.
- **Replay protection:** GCM provides integrity but not freshness. Both peers
  reject any message whose plaintext `id` was already seen within the last
  N (e.g. 512) processed messages (small LRU) as a replay → drop + `error`
  `operationDenied` without user interaction. This bounds replay risk to the
  LRU window; the 30 s request timeout additionally bounds staleness.

---

## 4. Plaintext Message (inside `ciphertext`)

The decrypted `ciphertext` is itself a JSON object carrying a typed
request/response plus a correlation id, so both peers can multiplex
multiple in-flight operations on one WebSocket connection.

```json
{
  "version": 1,
  "type": "getAssertion",
  "id": "018f8b32-...-uuid",
  "payload": { }
}
```

### 4.1 Header fields

| Field     | Type   | Description |
|-----------|--------|-------------|
| `version` | int    | `PROTOCOL_VERSION` (currently `2`). Mismatch → abort with `VERSION_MISMATCH`. |
| `type`    | string | One of the message types in Section 5. |
| `id`      | string | UUID correlation id. The response to a request echoes the same `id`. |
| `payload` | object | Typed body per `type` (Sections 5.1–5.4). |

### 4.2 JSON Schema (2020-12) — base

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://fidobridge.dev/schema/message/v1.schema.json",
  "title": "Message",
  "type": "object",
  "additionalProperties": false,
  "required": ["version", "type", "id", "payload"],
  "properties": {
    "version": { "const": 2 },
    "type": {
      "enum": ["getAssertion", "makeCredential",
               "assertionResult", "makeCredentialResult",
               "error", "ping"]
    },
    "id": { "type": "string", "format": "uuid" },
    "payload": { "type": "object" }
  }
}
```

---

## 5. Message Types

All byte-string fields are encoded base64 (standard alphabet) in JSON.
`id` on the response must equal the request's `id`.

### 5.1 `getAssertion` — daemon → phone

```json
{
  "version": 1,
  "type": "getAssertion",
  "id": "<uuid>",
  "payload": {
    "clientDataHash": "BASE64_32_BYTES",
    "rpId": "example.com",
    "allowCredentials": ["BASE64_CRED_ID", "..."],
    "option": { "up": true, "uv": true }
  }
}
```

| Field             | Type       | Required | Notes |
|-------------------|------------|----------|-------|
| `clientDataHash`  | base64 str | yes      | SHA-256 of the clientDataJSON, from the local browser. |
| `rpId`            | string     | yes      | Relying Party ID; shown verbatim in the phone's `BiometricPrompt`. |
| `allowCredentials`| string[]   | no       | Credential ids the RP will accept. Empty/absent → any registered credential. |
| `option`          | object     | no       | CTAP2 options: `up` (user presence), `uv` (user verification). |

Empty `allowCredentials` = no filtering. If present, the phone returns
`operationDenied` for unknown ids.

**Selection policy when `allowCredentials` is empty/absent:** if the phone has
exactly one registered credential for the `rpId`, it uses it; if it has several,
it returns `operationDenied` (0x27) — the daemon must re-issue the request with
an explicit `allowCredentials`. This is deterministic and fail-safe; no
implicit first-only pick across multiple credentials.

### 5.2 `makeCredential` — daemon → phone

```json
{
  "version": 1,
  "type": "makeCredential",
  "id": "<uuid>",
  "payload": {
    "clientDataHash": "BASE64_32_BYTES",
    "rpId": "example.com",
    "user": { "id": "BASE64", "name": "alice@example.com", "displayName": "Alice" },
    "pubKeyCredParams": [{ "alg": -7 }],
    "excludeCredentials": []
  }
}
```

| Field                | Type   | Required | Notes |
|----------------------|--------|----------|-------|
| `clientDataHash`     | b64 str| yes      | As above. |
| `rpId`               | string | yes      | Relying Party ID. |
| `user`               | object| yes      | `id` (base64, opaque), `name`, `displayName`. |
| `pubKeyCredParams`   | array  | no       | `{alg}` COSE algorithm ids. Absent/empty → default `[{"alg": -7}]` (ES256/P-256). Present but not containing `-7` → reject with `CTAP2_ERR_UNSUPPORTED_ALGORITHM` (0x26): only ES256 is supported. |
| `excludeCredentials` | array  | no       | Ids the RP claims are already registered; match → `operationDenied`. |

### 5.3 `assertionResult` — phone → daemon

```json
{
  "version": 1,
  "type": "assertionResult",
  "id": "<uuid>",
  "payload": {
    "credentialId": "BASE64",
    "authenticatorData": "BASE64",
    "signature": "BASE64",
    "userHandle": "BASE64"
  }
}
```

- `authenticatorData` is `rpIdHash(32) ‖ flags(0x05) ‖ signCount(4)` — flags
  `0x05` = UP (0x01) + UV (0x04). UV is always set:
  the KeyStore spec in `agents.md` requires biometric authentication for
  *every* signature, so user verification is unconditional. Consequently
  `option.uv=false` in a request cannot be honored and is treated as `true`
  (never fail-safe to a weaker assertion). `signCount` is always `0`
  (the phone's KeyStore maintains no counter; CTAP2 permits zero).
- `signature` is a DER-encoded ECDSA P-256 signature over
  `authenticatorData ‖ clientDataHash`, produced by the TEE/StrongBox key
  after successful biometric auth.
- `userHandle` optional (used by the RP to map back to the account).

### 5.4 `makeCredentialResult` — phone → daemon

```json
{
  "version": 1,
  "type": "makeCredentialResult",
  "id": "<uuid>",
  "payload": {
    "credentialId": "BASE64",
    "authenticatorData": "BASE64",
    "attestationObject": "BASE64",
    "signature": "BASE64"
  }
}
```

- `authenticatorData` includes `attestedCredentialData`
  (AAGUID(16) ‖ credentialIdLen(2) ‖ credentialId ‖ COSE_P-256_pubkey) and
  flags `0x45` = UP (0x01) + UV (0x04) + AT (0x40). UV is set for the same
  reason as §5.3: biometrics are mandatory for every signature.
- `attestationObject` is the full CBOR-encoded attestation object, pinned to
  `fmt = "none"` with an empty `attStmt = {}` — the phone has no attestation
  certificate to provide. Structure: `{ "fmt": "none", "attStmt": {},
  "authData": <authenticatorData bytes> }`. Do not emit any other `fmt`.
- `signature` over `authenticatorData ‖ clientDataHash`, as above.

### 5.5 `error` — either direction

```json
{
  "version": 1,
  "type": "error",
  "id": "<uuid>",
  "payload": {
    "code": 39,
    "message": "operation denied"
  }
}
```

`code` mirrors CTAP2 status bytes so the daemon can reply to its local
client with the standard error:

| Code | Name                          | Trigger |
|------|-------------------------------|---------|
| 0x01 | `CTAP2_ERR_INVALID_COMMAND`   | Unknown/unparseable request |
| 0x26 | `CTAP2_ERR_UNSUPPORTED_ALGORITHM` | `pubKeyCredParams` without a supported algorithm |
| 0x27 | `CTAP2_ERR_OPERATION_DENIED`  | rpId mismatch, unknown credential, biometric fail/cancel/timeout, exclude match |
| 0x2C | `CTAP2_ERR_INVALID_OPTION`    | Unsupported `option` value |
| 0x7F | `VERSION_MISMATCH`            | `version != 2` (implementation-specific, non-CTAP2) |

Fail-safe: any local failure on the phone (biometric denial, tag mismatch,
origin mismatch) maps to `operationDenied` and aborts immediately.

### 5.6 `ping` — either direction (optional)

```json
{ "version": 1, "type": "ping", "id": "<uuid>", "payload": { "ts": 1699999999999 } }
```

Echoed back as a `ping` with the same `id`; used for liveness/timeout probes.

---

## 6. Message Flow

```
 Local CTAP2 client            Daemon              Relay              Phone
      │                          │                  │                  │
      │  getAssertion CBOR       │                  │                  │
      │─────────────────────────▶│                  │                  │
      │                          │ [parse CTAP2]    │                  │
      │                          │ [seal getAssertion]                 │
      │                          │──────────────────▶                  │
      │                          │   WireMessage    │                  │
      │                          │                  │─────────────────▶│
      │                          │                  │   [open + verify tag]
      │                          │                  │   [BiometricPrompt(rpId)]
      │                          │                  │   [sign in TEE]
      │                          │                  │   [seal assertionResult]
      │                          │                  │◀─────────────────│
      │                          │   WireMessage    │                  │
      │                          │◀─────────────────│                  │
      │  [open + reply]          │                  │                  │
      │◀─────────────────────────│                  │                  │
```

- Correlation: the response `id` equals the request `id`.
- Timeout: daemon aborts a request after 30 s (`FIDO2_REQUEST_TIMEOUT`) if no
  valid response arrives → returns timeout error to its socket client.
- Reconnect: on connection drop either peer reconnects with exponential
  backoff + jitter; in-flight requests are failed and the client retries.
- Replay: a request is dropped (and answered with `operationDenied`) if its
  `id` matches one already processed in the LRU window (see §3.2).

---

## 7. Validation Strategy

- **Canonical form:** each peer keeps a copy of the versioned JSON Schemas and
  validates every inbound/outbound message against them — no silent nulls,
  unknown keys rejected.
  - Android: `kotlinx.serialization` decodes the shape but does **not** enforce
    length/pattern constraints, so add explicit decoded-length checks after
    decode: `nonce` = 12 bytes, `tag` = 16 bytes, `key` = 32 bytes,
    `clientDataHash` = 32 bytes, and re-verify Base64 strictly.
  - Daemon: `jsonschema` (or equivalent) validates the full schema including
    the `pattern`/`const` constraints above.
- **Cross-peer tests:** daemon tests emit fixtures that the Android processor
  tests consume byte-for-byte (`allowCredentials` filtering, COSE layout,
  authenticator-data layout) so both sides stay in lock-step with this spec.
- **Freeze rule:** schema constants live in one place per peer (e.g.
  `protocol.py` / `Protocol.kt`) and are versioned; both plans already require
  this — `PROTOCOL.md` is the reference both implementations mirror.

## 8. Change Process

1. Edit this document; bump `version` in §1 for breaking changes.
2. Update the mirrored constants in both `linux-fido-daemon` and
   `android-fido-client`.
3. Update the cross-peer fixtures in both test suites; run all gates
   (`pytest` + `testDebugUnitTest`) before merging.
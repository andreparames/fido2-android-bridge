# PROTOCOL.md — Daemon ↔ Android Bridge Protocol (v3)

This document is the single source of truth for the communication formats
between the **Linux daemon** (`linux-fido-daemon`) and the **Android client**
(`android-fido-client`). It covers only the two interactions those peers have
with each other:

1. **Pairing** — one-time static-key exchange (daemon → phone).
2. **Request/Response relay** — Noise-protected CTAP2-style messages over the
   WebSocket relay (bidirectional).

Out of scope: the local Unix socket, CTAP2 CBOR decoding, and any
daemon-internal detail. See `ANDROID_PLAN.md` and `DAEMON_PLAN.md` for those.

Security posture (from `AGENTS.md`): the relay is **untrusted**; every payload
is protected by the **Noise Protocol Framework** before touching the network;
private keys never leave the phone's TEE/StrongBox.

---

## 1. Versioning

The protocol is versioned as a whole (`PROTOCOL_VERSION = 3`). Both peers must
agree on the version or refuse to communicate. The version travels in the
plaintext header (Section 4) and is also embedded in the pairing URI
(optional, `v` parameter).

Schema constants are frozen at the emitted version; any breaking change bumps
the version rather than mutating a published field.

**v3 replaces v2 entirely.** The static AES-256-GCM envelope
(`key=<BASE64_AES_KEY>`, `{nonce, ciphertext, tag}`) and the LRU replay cache
are removed. Both peers must be updated in lock-step; v2 peers are refused with
`VERSION_MISMATCH` (0x7F). v2 compatibility is not maintained.

---

## 2. Pairing URI

Generated once by the daemon (`fido-daemon pair`), displayed as a QR code or
terminal string, scanned/pasted into the Android app.

The URI carries the daemon's **static X25519 public key** (out-of-band, so the
relay never sees it). It carries **no symmetric key material**: session keys
are derived per-connection by the Noise handshake, giving perfect forward
secrecy.

### 2.1 ABNF

```abnf
pairing-uri = "fidobridge://pair" "?" pair-params
pair-params = "channel=" channel-id "&" "pubkey=" b64-static-key ["&" "token=" token] ["&" "v=" version]
channel-id  = 32 LCHEXDIG      ; 16 bytes, 128-bit channel identifier
b64-static-key = 43 BASE64URL  ; 32-byte X25519 static public key, base64url, no padding
token       = 1*( ALPHA / DIGIT / "-" / "_" / "." ) ; Centrifugo connection JWT, compact serialization
version     = "3"
LCHEXDIG    = %x30-39 / %x61-66 ; lowercase hex digit: 0-9 a-f
```

`BASE64URL` means standard base64 with URL-safe alphabet (`-`/`_`) and **no
padding**, per the "no URL-safe vs standard mixing" rule in both plans.

### 2.2 Parameters

| Parameter | Encoding | Length (bytes) | Purpose |
|-----------|----------|----------------|---------|
| `channel` | hex (lowercase) | 16 | Channel identifier |
| `pubkey`  | base64url, unpadded | 32 | Daemon static X25519 public key `s_daemon_pub` |
| `token`   | JWT compact serialization | variable | Optional Centrifugo connection token |
| `v`       | decimal string | – | Protocol version (optional, default `3`) |

### 2.3 Validation rules

- Missing `channel` or `pubkey` → reject.
- `pubkey` must decode to exactly 32 bytes.
- `channel` must be exactly 32 **lowercase** hex chars (`[0-9a-f]{32}`); uppercase
  hex is rejected, per the `channel_id` derivation in §3.2.
- `token` is optional; if present, must be a non-empty string (a Centrifugo
  connection JWT signed with the relay's `hmac_secret_key`).
- If `v` is present and not equal to `PROTOCOL_VERSION` (`3`) → reject with
  `VERSION_MISMATCH` (0x7F), mirroring the version check in §4.1. Absent `v`
  defaults to `3`.
- Unknown/duplicate parameters → reject (fail-safe, no silent nulls).

### 2.4 Static keys

- The daemon generates a long-term X25519 keypair on first `pair` and persists
  the 32-byte private scalar to a file with mode `0600`. Its public key travels
  in the pairing URI.
- The phone generates its own long-term X25519 keypair at install time and
  stores it, plus the daemon's public key, in `EncryptedSharedPreferences`.
- Static keys are used **only** to authenticate the Noise handshake. Every
  WebSocket session derives fresh ephemeral keys, so static-key compromise does
  not compromise past or future session traffic.
- **Phone pinning (trust-on-first-use):** during the first handshake the daemon
  learns the phone's static public key and pins it (persisted to its config
  file as `phone_public_key`). Every later handshake must present the same key
  or it is rejected as a security alert — so an attacker who learns only the
  channel id cannot impersonate the phone. An explicit override
  (`FIDO2_PHONE_PUBLIC_KEY`, or the same config value) takes precedence over
  the learned key.
- **Resetting the pin (pair a different phone):** re-pairing requires a fresh
  pairing URI (new `channel`/`pubkey`) **and** clearing the old pin. Run
  `fido-daemon pair -c <config>` to generate the new URI, then
  `fido-daemon unpair -c <config>` to clear the stored `phone_public_key`
  (`--confirm` skips the confirmation prompt for scripts); the new phone's key
  is learned and pinned on its first handshake. (Alternatively, delete the
  `phone_public_key` line from the config file, or unset
  `FIDO2_PHONE_PUBLIC_KEY`.)

---

## 3. Wire Envelope (on the relay)

Every message sent over the WebSocket — either direction — is exactly this JSON
object. The relay only sees this envelope.

```json
{
  "channel_id": "HASHED_CHANNEL_ID_STRING",
  "kind": "ik1",
  "payload": "BASE64_NOISE_MESSAGE"
}
```

### 3.1 JSON Schema (2020-12)

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://fidobridge.dev/schema/wire-message/v3.schema.json",
  "title": "WireEnvelope",
  "type": "object",
  "additionalProperties": false,
  "required": ["channel_id", "kind", "payload"],
  "properties": {
    "channel_id": {
      "type": "string",
      "description": "Channel identifier. NOT the raw 128-bit id from the URI: it is a stable hash/digest of it so the raw id never appears on the wire (see 3.2).",
      "pattern": "^[0-9a-f]{32}$"
    },
    "kind": {
      "type": "string",
      "enum": ["ik1", "ik2", "data"],
      "description": "ik1: phone->daemon handshake message 1. ik2: daemon->phone handshake message 2. data: Noise transport ciphertext."
    },
    "payload": {
      "type": "string",
      "description": "Raw Noise handshake message or transport ciphertext (ciphertext || 16-byte tag), base64 (standard) encoded, unpadded."
    }
  }
}
```

### 3.2 Rules

- **Base64:** standard alphabet everywhere on the wire; base64url only in the
  pairing URI. Centralized in one helper per peer — no mixing.
- **Channel id:** the raw 128-bit id must not appear in plaintext on the
  relay. Both peers derive it identically at pairing time and freeze it:
  `channel_id = lowercase hex( SHA-256( channel_hex_utf8 )[0:16] )` — i.e. the
  first 16 bytes of SHA-256 over the 32-char lowercase hex channel string from
  the URI, encoded as 32 lowercase hex chars. It is used for both the
  `channel_id` envelope field and the relay topic (see §3.3). Derivation is pinned;
  do not substitute another hash/encoding.
- **Handshake routing:** the initiator (phone) publishes only `ik1`; the
  responder (daemon) publishes only `ik2`. Each peer ignores publications whose
  `kind` is its own (which is how each peer discards its own relay echo).
  After the handshake, both peers publish `data`.
- **Replay protection:** the Noise transport maintains two 64-bit monotonic
  sequence counters per session — one for transmission, one for reception —
  that start at `0` and increment with every message. Inbound `data` frames
  are decrypted under the current receive counter; a replayed or reordered
  frame therefore fails the GCM authentication tag (its ciphertext was produced
  under a different counter) and is dropped as a **security alert** before any
  processing. Because ephemeral keys are regenerated for every session, frames
  captured from a previous session cannot be replayed into a new one. No LRU
  replay cache is required or used.
- **Auth failure:** any Noise authentication failure (handshake or transport)
  aborts the message, flags the connection, and logs a security alert. The
  connection may be dropped.

### 3.3 Relay topic (WebSocket routing)

Both peers subscribe and publish on exactly one relay topic per pairing:

    relay-topic = "fidobridge:" channel-id     ; e.g. fidobridge:a1b2…0f

- The topic is the **colon-delimited** form `fidobridge:<channel_id>` — the
  `fidobridge` prefix is a Centrifugo channel namespace, so the relay accepts
  only topics with that prefix and refuses any other (non-namespaced) channel.
- The topic is the only place the derived `channel_id` reaches the relay as
  routing state; the raw 128-bit id and the pairing URI never appear on the
  wire.
- The `fidobridge:log:<channel_id>` diagnostic topic (optional, phone-only)
  is reserved for troubleshooting lines and lives under the same namespace;
  it is not part of the Noise request/response flow.
- Both peers MUST derive the topic identically from the frozen `channel_id`;
  there is no dynamic topic routing.

---

## 4. Plaintext Message (inside `data`)

The decrypted `data` payload is itself a JSON object carrying a typed
request/response plus a correlation id, so both peers can multiplex
multiple in-flight operations on one WebSocket connection.

```json
{
  "version": 3,
  "type": "getAssertion",
  "id": "018f8b32-...-uuid",
  "payload": { }
}
```

### 4.1 Header fields

| Field     | Type   | Description |
|-----------|--------|-------------|
| `version` | int    | `PROTOCOL_VERSION` (currently `3`). Mismatch → abort with `VERSION_MISMATCH`. |
| `type`    | string | One of the message types in Section 5. |
| `id`      | string | UUID correlation id. The response to a request echoes the same `id`. |
| `payload` | object | Typed body per `type` (Sections 5.1–5.4). |

### 4.2 JSON Schema (2020-12) — base

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://fidobridge.dev/schema/message/v3.schema.json",
  "title": "Message",
  "type": "object",
  "additionalProperties": false,
  "required": ["version", "type", "id", "payload"],
  "properties": {
    "version": { "const": 3 },
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
  "version": 3,
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
  "version": 3,
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
  "version": 3,
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
  the KeyStore spec in `AGENTS.md` requires biometric authentication for
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
  "version": 3,
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
  "version": 3,
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
| 0x7F | `VERSION_MISMATCH`            | `version != 3` (implementation-specific, non-CTAP2) |

Fail-safe: any local failure on the phone (biometric denial, tag mismatch,
origin mismatch) maps to `operationDenied` and aborts immediately.

### 5.6 `ping` — either direction (optional)

```json
{ "version": 3, "type": "ping", "id": "<uuid>", "payload": { "ts": 1699999999999 } }
```

Echoed back as a `ping` with the same `id`; used for liveness/timeout probes.

---

## 6. Message Flow

```
 Local CTAP2 client            Daemon              Relay              Phone
      │                          │                  │                  │
      │                          │   [handshake]    │  ik1 (phone→daemon)
      │                          │◀─────────────────│──────────────────│
      │                          │                  │  ik2 (daemon→phone)
      │                          │──────────────────│──────────────────▶
      │                          │  [split → transport, PFS]
      │                          │                  │                  │
      │  getAssertion CBOR       │                  │                  │
      │─────────────────────────▶│                  │                  │
      │                          │ [parse CTAP2]    │                  │
      │                          │ [encrypt data]   │                  │
      │                          │──────────────────▶                  │
      │                          │   WireEnvelope    │                  │
      │                          │     kind=data     │─────────────────▶│
      │                          │                  │   [decrypt, verify tag]
      │                          │                  │   [BiometricPrompt(rpId)]
      │                          │                  │   [sign in TEE]
      │                          │                  │   [encrypt data]
      │                          │                  │◀─────────────────│
      │                          │   WireEnvelope    │                  │
      │                          │◀─────────────────│                  │
      │  [decrypt + reply]       │                  │                  │
      │◀─────────────────────────│                  │                  │
```

- **Handshake:** on every (re)connect the phone initiates a fresh
  `Noise_IK_25519_AESGCM_SHA256` handshake with prologue `"FIDO2_BRIDGE_V2"`:
  it publishes `ik1`, the daemon replies with `ik2`, both call `split()`.
  Ephemeral keys are generated per session and discarded after the handshake.
- **Correlation:** the response `id` equals the request `id`.
- **Timeout:** daemon aborts a request after 30 s (`FIDO2_REQUEST_TIMEOUT`) if no
  valid response arrives → returns timeout error to its socket client. Requests
  issued before the handshake completes wait for it, bounded by the same timeout.
- **Reconnect:** on connection drop either peer reconnects with exponential
  backoff + jitter; a new handshake runs; in-flight requests are failed and the
  client retries.
- **Replay:** prevented by the Noise transport sequence counters (§3.2). A
  replayed/reordered frame fails authentication and is dropped as a security
  alert without user interaction.

---

## 7. Validation Strategy

- **Canonical form:** each peer keeps a copy of the versioned JSON Schemas and
  validates every inbound/outbound message against them — no silent nulls,
  unknown keys rejected.
  - Android: `kotlinx.serialization` decodes the shape but does **not** enforce
    length/pattern constraints, so add explicit decoded-length checks after
    decode: `pubkey` = 32 bytes, `channel` = 32 lowercase hex,
    `clientDataHash` = 32 bytes, and re-verify Base64 strictly.
  - Daemon: `jsonschema` (or equivalent) validates the full schema including
    the `pattern`/`const` constraints above.
- **Noise failures:** any `ik1`/`ik2`/`data` frame that fails Noise
  authentication (handshake error, `InvalidTag`, replay) is treated as a
  security alert, logged, and dropped. The connection may be dropped.
- **Cross-peer tests:** daemon tests emit fixtures that the Android processor
  tests consume byte-for-byte (`ik1`/`ik2` message sizes, envelope schema,
  pairing-URI parsing, authenticator-data layout) so both sides stay in
  lock-step with this spec.
- **Freeze rule:** schema constants live in one place per peer (e.g.
  `protocol.py` / `Protocol.kt`) and are versioned; both plans already require
  this — `PROTOCOL.md` is the reference both implementations mirror.

## 8. Change Process

1. Edit this document; bump `version` in §1 for breaking changes.
2. Update the mirrored constants in both `linux-fido-daemon` and
   `android-fido-client`.
3. Update the cross-peer fixtures in both test suites; run all gates
   (`pytest` + `testDebugUnitTest`) before merging.
# android-fido-client

Native Android client for the **Remote WebAuthn / Passkey Hardware-Backed Bridge**.
The app receives WebAuthn/CTAP2 challenges over a self-hosted **Centrifugo** realtime
broker, prompts for local biometric verification (`BiometricPrompt`), and signs the
challenge with a hardware-backed EC P-256 key held in Android KeyStore (TEE/StrongBox).

It is the phone-side peer of `linux-fido-daemon`. The relay (Centrifugo) is **untrusted**:
every payload is AES-256-GCM sealed before it touches the network; the relay only ever
sees ciphertext.

```
[ Linux daemon ]  --AES-256-GCM-->  [ Centrifugo broker ]  -->  [ This app ]
      (seals)                            (untrusted)              (opens, biometric sign)
```

---

## Docs you must read first

| Doc | Purpose |
|-----|---------|
| [`PROTOCOL.md`](../PROTOCOL.md) | **Single source of truth** for wire formats: pairing URI, WireMessage envelope, plaintext message schema, `channel_id` derivation, error codes. Do not change these constants without bumping `PROTOCOL_VERSION`. |
| [`agents.md`](../agents.md) | Security requirements, Definition of Done, hardware-key spec. |
| [`ANDROID_PLAN.md`](../ANDROID_PLAN.md) | The TDD build plan this codebase follows (Red → Green → Refactor). |
| [`DAEMON_PLAN.md`](../DAEMON_PLAN.md) | The daemon-side mirror plan (also Centrifugo). |

---

## Current status

Built via strict TDD through **M1–M5** of `ANDROID_PLAN.md`, plus the Centrifugo
transport migration. Unit-testable layers are done and green (48 unit tests,
`lint`, `assembleDebug`). Device-dependent pieces compile but are **not yet run on a
device**.

### Verified green
- **M1** project scaffolding: Kotlin 2.0.21, Compose, Hilt, Gradle 8.7, AGP 8.5.2,
  JDK 17, version catalog, JUnit4 harness.
- **M2** `crypto/`, `protocol/`, `pairing/`, QR UI:
  - `AesGcmCipher` — AES-256-GCM, 12-byte nonce, 16-byte tag, `TagMismatchException`.
  - `MessageCodec` / `WireMessage` — the §3 JSON envelope (strict base64, length checks).
  - `PairingRepository` — `fidobridge://pair?channel=…&key=…[&v=1]` parsing, `channel_id`
    derivation (`lowercase hex(SHA-256(channel_hex_utf8)[0:16])`), `v`/`VERSION_MISMATCH`.
  - `SessionKeyStore` (EncryptedSharedPreferences) — persists the session key **and** the
    derived `channel_id` together.
  - `PairingViewModel` + `PairingScreen` (ZXing QR + manual paste), unpaired users are
    routed to the pairing screen.
- **M3** `security/`:
  - `KeystoreManager` — EC P-256, SIGN-only, SHA-256, biometric-locked StrongBox
    (`setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG|AUTH_DEVICE_CREDENTIAL)`
    on API 30+, `setUserAuthenticationValidityDurationSeconds(0)` below, StrongBox on API 28+).
  - `BiometricSigner` — signs only inside TEE after a successful `BiometricPrompt`.
- **M4** `networking/` (Centrifugo):
  - `CentrifugoTransport` — wraps the official `io.github.centrifugal:centrifuge-java`
    SDK; subscribes to `fidobridge.<channel_id>`; connection JWT attached on connect and
    re-attached on reconnect via `setToken`/`setTokenGetter`; never logs the token.
  - `RelayClient` — the E2EE layer over a thin `RelayTransport` abstraction: seal-before-
    publish, open-on-receive, GCM tag-failure abort → security alert, `channel_id` check,
    replay drop (LRU, N=512) with `error`/`operationDenied` reply, and a `disconnections`
    flow used to abort in-flight requests on disconnect.
  - `FidoBridgeService` — foreground service skeleton (`DATA_SYNC`); relay lifecycle
    wiring is deferred to integration (see "Next steps").
- **M5** `ctap/`:
  - `Ctap2Processor` — dispatches `getAssertion`/`makeCredential`/`ping`; builds
    `authenticatorData` (`0x05` assertion / `0x45` makeCredential, `signCount=0`), COSE
    P-256 keys, `fmt="none"` attestation objects; enforces `rpId`/`allowCredentials`/
    `excludeCredentials`/`pubKeyCredParams` rules and maps failures to CTAP2 codes
    (`0x26`, `0x27`, `0x7F`).

### Not yet done / requires hardware
- **Phase 10 / DoD** (`agents.md` §6): the full loop — inbound JSON → `BiometricPrompt`
  (showing `rpId`) → sign → response — and the manual `https://webauthn.io` E2E. The
  service wiring (`FidoBridgeService` → `RelayClient` → `Ctap2Processor` →
  `BiometricSigner`) and a StrongBox→TEE fallback still need to be built; only a
  physical device can prove `isInsideSecureHardware`.
- **Daemon side** of the Centrifugo migration is a separate workstream (see the "daemon
  handoff" note below).

### Integration harness (Phase 11)

The relay round-trip **is** validated on the host JVM — no emulator needed. Run:

```bash
# 1. Write /tmp/fido2_harness.properties:
#    enabled=true / channel_id=<32-hex> / session_key_b64=<b64> / relay_url=wss://gary.andreparames.com:8000/connection/websocket
# 2. In one terminal (daemon-peer, publishes synthetic CTAP2 requests):
FIDO2_CHANNEL_ID=<same> FIDO2_SESSION_KEY_B64=<same> \
  python -m mock_daemon all --timeout 5 --retries 30
# 3. In another:
./gradlew testDebugUnitTest --tests 'com.fidobridge.client.harness.IntegrationHarnessTest'
```

Both `get-assertion` and `make-credential` pass end-to-end through a live Centrifugo
(`CentrifugoTransport` JSON-vs-object payload handling, `onConnected`-subscribe, and
Python `relay.py` dict tolerance were fixed to make this work).

---

## Repository layout

```
app/src/main/java/com/fidobridge/client/
  crypto/       AES-256-GCM (AesGcmCipher, SessionKey, EncryptedMessage)
  protocol/     Wire schema (Protocol, WireMessage, MessageCodec, PlaintextEnvelope,
                Ctap2Status) — mirrors PROTOCOL.md constants
  pairing/      Pairing URI parse, SessionKeyStore (key + channel_id), RelayTokenStore
                removed — token now build-time (see below)
  security/     KeystoreManager, BiometricSigner, BiometricAuthenticator
  networking/   RelayTransport, CentrifugoTransport, RelayClient, ReplayCache,
                FidoBridgeService
  ctap/         Ctap2Processor, AuthenticatorDataBuilder, CoseKey,
                AttestationObjectBuilder, message DTOs
  di/           Hilt DataModule (SessionKeyStore, PairingRepository)
  ui/           FidoBridgeApp (nav), PairingScreen + QrScanner, AppViewModel, theme
  util/         Base64 (standard + url-safe, unpadded, centralized)
app/src/test/   JVM unit tests (crypto, protocol, pairing, networking, ctap)
app/src/androidTest/  instrumented tests (Keystore, Biometric) — device only
```

---

## Build & test

Requirements: JDK 17+, Android SDK (`sdk.dir` in `local.properties`), Android Gradle
Plugin 8.5.2 / Gradle 8.7 (wrapped).

```bash
./gradlew testDebugUnitTest    # JVM unit tests
./gradlew lint                 # static analysis
./gradlew assembleDebug        # APK build
./gradlew connectedDebugAndroidTest   # device required
```

For the headless-emulator E2E (real APK + live Centrifugo + `mock-daemon`), see
`ANDROID_PLAN.md` Phase 12; the exact emulator/SDK install state and cleanup
steps are in [`EMULATOR_ENV.md`](../EMULATOR_ENV.md).

### Relay token (dev convenience)

The Centrifugo connection JWT is read from the `pass` CLI at build time and embedded as
`BuildConfig.RELAY_TOKEN`:

```bash
pass insert fidobridge/relay-token   # or: pass show fidobridge/relay-token
./gradlew assembleDebug
```

- Entry name overridable via the `FIDO_RELAY_PASS_ENTRY` env var.
- Missing `pass`/entry → empty token → the client fails closed (unauthorized) rather
  than connecting anonymously.
- **Warning:** an embedded token ships inside the APK and is extractable; fine for this
  dev phase, not for production.

---

## Security invariants (from `agents.md`)

- Private keys never leave TEE/StrongBox; every signature requires biometric auth.
- AES-256-GCM on all wire data; fresh 12-byte nonce per message; never reused.
- GCM tag failure → abort + security alert + message dropped.
- The relay never sees the raw 128-bit channel id (only the derived `channel_id` digest),
  nor any plaintext.
- Token is never logged and never passed through UI/intent args.

---

## Next steps / how to pick up

1. **Device verification (M3/DoD):** run `connectedDebugAndroidTest` on a device and
   confirm `KeyInfo.isInsideSecureHardware == true` and
   `Signature.sign()` without auth throws `UserNotAuthenticatedException`.
2. **Relay endpoint config:** `CentrifugoTransport` needs a real endpoint URL
   (`ws(s)://host:port/connection/websocket`). There is no config source yet — decide
   how to supply it (BuildConfig field, runtime setting, or from the pairing/daemon).
3. **Wire the full loop in `FidoBridgeService`:**
   `CentrifugoTransport` + `RelayClient` → `Ctap2Processor` → `BiometricSigner` →
   publish `assertionResult`/`makeCredentialResult`, correlated by plaintext `id`;
   abort in-flight requests on `RelayClient.disconnections`.
4. **Daemon handoff:** the daemon must migrate its own relay to Centrifugo in lock-step.
   The critical convention to agree on: the §3 `WireMessage` is the **publication
   payload** (a JSON object on the Python side; its UTF-8 bytes on this side), *not* a
   JSON-string-inside-a-string. All `PROTOCOL.md` constants stay authoritative.
5. **Production token provisioning:** replace the build-time `pass` embed with runtime
   delivery (e.g. extend the pairing flow) once the transport is stable.
# ANDROID_PLAN.md — Android Client Build Plan (TDD)

This plan builds the `android-fido-client` using a strict **Test-Driven Development** workflow. Every phase follows the Red → Green → Refactor cycle: write a failing test, implement the minimum to pass, then refactor. No phase is considered done until its tests pass.

Source of truth for security requirements: `agents.md`.

---

## 0. Guiding Rules

- **TDD everywhere:** no production code is written before a failing test defines its behavior.
- **Security-first:** private keys never leave TEE/StrongBox; AES-256-GCM for all wire data; relay is untrusted.
- **Toolchain:** Kotlin 2.0+, `minSdk 26`, `targetSdk/compileSdk 34`, JDK 17, Gradle 8.x + version catalog.
- **Decisions (confirmed):** QR scanning = ZXing (journeyapps); CTAP2 response relay = JSON mirroring the daemon schema.
- **Verification gates:** each phase ends with `./gradlew testDebugUnitTest` (and `connectedDebugAndroidTest` where hardware is needed).

---

## 1. Project Scaffolding (M1)

**Objective:** buildable Gradle project with Kotlin/Compose/Hilt wired up and a test harness that runs.

### Steps
1. Create root `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`.
2. Create `app` module with `build.gradle.kts` and `AndroidManifest.xml`.
3. Add dependencies: Compose BOM, Material 3, Navigation Compose, Lifecycle ViewModel Compose, Hilt, Coroutines, `kotlinx-serialization-json`, OkHttp, `androidx.biometric`, `androidx.security:security-crypto`, `com.upokecenter:cbor`, `com.journeyapps:zxing-android-embedded`.
4. Add test deps: `junit4`, `kotlinx-coroutines-test`, `mockk`, `androidx.arch.core:core-testing`, `turbine`.

### TDD Gate
- **Test:** a trivial smoke test (e.g., `ArithmeticSmokeTest`) that fails before implementation and passes after, proving the test runner + JUnit4 wiring works.
- **Done when:** `./gradlew testDebugUnitTest` is green and `assembleDebug` succeeds.

---

## 2. AES-256-GCM Cipher (M2)

**Objective:** `AesGcmCipher` — the E2EE primitive used by every message.

### TDD (Red → Green → Refactor)
- **Tests first** (`AesGcmCipherTest`):
  1. Encrypting a known plaintext produces non-empty `nonce` (12 bytes), `ciphertext`, and `tag` (16 bytes).
  2. `decrypt(encrypt(p)) == p` (round-trip).
  3. Two encryptions of the same plaintext produce different ciphertext (unique nonce per message).
  4. Tampering with ciphertext OR tag causes `decrypt` to throw `AesGcmCipher.TagMismatchException` (security alert path).
  5. Decrypting with the wrong key throws.
- **Implement:** `AesGcmCipher` using `javax.crypto.Cipher` with `AES/GCM/NoPadding`, `SecureRandom` 12-byte nonce, and `GCMParameterSpec(128, nonce)`.
- **Refactor:** extract a `SecretKey` wrapper type for type safety.

**Done when:** all cipher tests pass.

---

## 3. Wire Message Codec (M2)

**Objective:** serialize/deserialize the wire schema:
```json
{ "channel_id": "...", "nonce": "BASE64", "ciphertext": "BASE64", "tag": "BASE64" }
```

### TDD
- **Tests first** (`MessageCodecTest`):
  1. Encode a `WireMessage` and decode it back to an equal object.
  2. Missing/invalid `nonce` or `tag` fields → parse error.
  3. Base64 decode of a malformed field → error (no silent nulls).
  4. Round-trip with real cipher output (integration with `AesGcmCipher`).
- **Implement:** `WireMessage` data class + `MessageCodec` using `kotlinx.serialization`.
- **Refactor:** centralize Base64 encoding (no URL-safe vs standard mixing).

**Done when:** codec tests pass.

---

## 4. Pairing Repository + SessionKeyStore (M2)

**Objective:** parse the QR/URI and securely persist the session key.

### TDD
- **Tests first** (`PairingRepositoryTest`, using a fake/in-memory store):
  1. `fidobridge://pair?channel=<32_lc_hex>&key=<BASE64URL_32BYTE>` parses into `PairingInfo(channel, key)`; `channel_id` is derived as `hex(SHA-256(channel_hex)[0:16])` (lowercase hex), per `PROTOCOL.md` §3.2.
  2. Malformed URI (missing channel/key, non-base64url key, wrong key length ≠ 32 bytes, `channel` not `[0-9a-f]{32}`/uppercase hex) → error.
  3. `v` present and ≠ `1` → `VERSION_MISMATCH` (0x7F); absent `v` defaults to `1`, per `PROTOCOL.md` §2.3.
  4. Unknown/duplicate parameters → error (fail-safe, no silent nulls).
  5. Store round-trips the key; stored key is not equal to a plaintext string (stored encrypted).
  6. `isPaired` reflects persisted state.
- **Implement:**
  - `SessionKeyStore` backed by `EncryptedSharedPreferences`.
  - `PairingRepository.parseUri(String): Result<PairingInfo>`.
  - `channel_id` derivation helper (SHA-256 → first 16 bytes → lowercase hex), frozen as a constant.
- **Refactor:** single source of truth for the `fidobridge://` scheme constant; base64url (unpadded) strictly for the URI key, standard base64 on the wire — no mixing.

**Done when:** pairing tests pass (store tested with fakes at unit level; EncryptedSharedPreferences verified on device in Phase 9).

---

## 5. QR Scanning UI + Pairing Screen (M2)

**Objective:** scan QR (ZXing) or paste URI manually, then persist pairing.

### TDD
- **Tests first** (`PairingViewModelTest`):
  1. `onQrResult(validUri)` → state becomes `Paired` and `PairingInfo` is stored (via fake repo).
  2. `onQrResult(invalidUri)` → state `Error(message)`.
  3. Manual text input path behaves identically to QR path.
  4. Idle → `Scanning` initial state.
- **Implement:**
  - `PairingViewModel` + `PairingUiState` sealed interface.
  - `PairingScreen` (Compose) hosting ZXing `ScannerView` + manual input field.
  - Navigation: unpaired users are routed to `PairingScreen`.
- **Refactor:** extract QR-lifetime handling (camera start/stop on lifecycle) into a dedicated composable.

**Done when:** ViewModel tests pass; screen compiles and scans on device.

---

## 6. Hardware-Backed Keystore Manager (M3)

**Objective:** generate and use an EC P-256 key locked to biometrics, using the exact spec from `agents.md`.

### TDD
- **Tests first** (`KeystoreManagerTest`, instrumented — device/emulator with KeyStore):
  1. `getOrCreateSigningKey()` returns a key whose `KeyInfo.isInsideSecureHardware == true`.
  2. Key purpose is `SIGN` only, algorithm EC P-256, digest SHA256.
  3. `userAuthenticationRequired` is true.
  4. `Signature.sign()` without prior biometric auth throws `UserNotAuthenticatedException`.
  5. Key is stable across calls (same alias does not regenerate).
- **Implement:** `KeystoreManager` wrapping `KeyPairGenerator` + `KeyGenParameterSpec` (StrongBox-backed, `AUTH_BIOMETRIC_STRONG | DEVICE_CREDENTIAL`, `setUserAuthenticationParameters(0, ...)`).
- **Refactor:** separate key generation from `Signature` creation; expose a `Signature` factory bound to `CryptoObject`.

**Done when:** instrumented Keystore tests pass.

---

## 7. BiometricPrompt + Signing Flow (M3)

**Objective:** wrap `BiometricPrompt.CryptoObject` so signing is unlocked only inside TEE after a successful fingerprint/face scan.

### TDD
- **Tests first** (`BiometricSignerTest`, with fake `BiometricPrompt` result + real Keystore on device):
  1. On auth success, the bound `Signature` can sign a digest.
  2. On auth failure/cancel, no signature is produced and `OperationDenied` is returned.
  3. The prompt payload (title/subtitle) contains the target `rpId`/domain (DoD §6 requirement).
- **Implement:** `BiometricSigner` returning a `Result<ByteArray>` and surfacing the `rpId` into the prompt UI.
- **Refactor:** isolate `BiometricPrompt` construction for testability via an interface.

**Done when:** instrumented biometric tests pass.

---

## 8. Centrifugo Relay + E2EE + Token Auth (M4)

**Objective:** carry sealed payloads to the daemon over a self-hosted **Centrifugo** broker, authenticate with a connection JWT, and keep the E2EE sealing/opening unchanged.

> The relay is an untrusted Centrifugo broker. Both peers subscribe and publish to the channel `fidobridge.<channel_id>`; the `PROTOCOL.md` §3 `WireMessage` envelope is the publication payload (the relay only ever sees ciphertext). The connection JWT is read from the `pass` CLI at build time and embedded as `BuildConfig.RELAY_TOKEN` (dev convenience); it is attached on initial connect and on every reconnect via the SDK's `getToken` callback and is never logged or exposed.

### TDD
- **Tests first** (`RelayClientTest` with a fake `RelayTransport` — no live broker):
  1. `send(p)` publishes a sealed `WireMessage` for the current `channel_id` (encrypt-before-publish).
  2. An inbound publication is opened and the plaintext is emitted on the `Flow`.
  3. GCM tag failure on a publication → security alert + message dropped.
  4. `channel_id` mismatch → security alert.
  5. Replay: inbound plaintext whose `id` was already seen in the last 512 processed messages → dropped + answered `error`/`operationDenied` without user interaction, per `PROTOCOL.md` §3.2.
  6. Disconnect → state `DISCONNECTED` + `disconnections` emitted (signal to abort in-flight requests).
- **Implement:**
  - `RelayTransport` (thin abstraction) + `CentrifugoTransport` (centrifuge-java SDK: connect with token via `setToken`/`setTokenGetter`, subscribe to `fidobridge.<channel_id>`, publish/consume `byte[]`).
  - `RelayClient` (E2EE layer over the transport: seal/open, replay, security alerts).
  - Build-time token injection: `relayTokenFromPass()` reads `pass show fidobridge/relay-token` into `BuildConfig.RELAY_TOKEN` (overridable via `FIDO_RELAY_PASS_ENTRY`).
  - `ReplayCache` (LRU, N=512) checked against the plaintext `id` before processing.
  - `FidoBridgeService` (`ForegroundServiceType.DATA_SYNC`) owning the relay lifecycle.
- **Refactor:** rely on the SDK's built-in reconnect; fail in-flight requests on `disconnections`.

**Done when:** `RelayClient` unit tests pass with the fake transport (the `CentrifugoTransport` adapter is verified on device in Phase 10).

---

## 9. CTAP2 Processor + Authenticator Data (M5)

**Objective:** turn inbound JSON (`clientDataHash`, `rpId`, `allowCredentials`) into signed responses; JSON in, JSON out.

### TDD
- **Tests first** (`Ctap2ProcessorTest`, `AuthenticatorDataBuilderTest`, `CoseKeyTest`):
  1. **Assertion:** builds `authenticatorData = rpIdHash(32) || flags(0x05) || signCount(4)` — flags `0x05` = UP (0x01) + UV (0x04); UV is unconditional because biometrics are mandatory per `agents.md` — `signCount` always `0` — produces a valid signature over `authenticatorData || clientDataHash`, returns `{credentialId, authenticatorData, signature, userHandle}`. See `PROTOCOL.md` §5.3.
  2. **MakeCredential:** builds `authenticatorData` with `attestedCredentialData` (AAGUID + credentialId + COSE P-256 public key) and flags `0x45` (UP + UV + AT), per `PROTOCOL.md` §5.4, and signs `authenticatorData || clientDataHash`.
  3. **MakeCredential result** includes `attestationObject` = CBOR `{ "fmt": "none", "attStmt": {}, "authData": <authenticatorData> }` (empty `attStmt`, no other `fmt`), per `PROTOCOL.md` §5.4.
  4. `rpId` mismatch vs registered credential → `CTAP2_ERR_OPERATION_DENIED` (0x27).
  5. `allowCredentials` filtering: unknown credential ID → denied; empty/absent `allowCredentials` with multiple registered credentials for `rpId` → `operationDenied` (deterministic, no implicit first-only pick), per `PROTOCOL.md` §5.1.
  6. `pubKeyCredParams` absent → default `[{"alg": -7}]`; present but not containing `-7` → `CTAP2_ERR_INVALID_OPTION` (0x26), per `PROTOCOL.md` §5.2.
  7. `excludeCredentials` match → `operationDenied` (0x27), per `PROTOCOL.md` §5.2.
  8. COSE key encoding matches expected CBOR layout (byte-for-byte in fixtures).
- **Implement:**
  - `AuthenticatorDataBuilder` (byte layout).
  - `CoseKey` encoder (CBOR via `com.upokecenter:cbor`).
  - `AttestationObjectBuilder` (`fmt="none"`, empty `attStmt`).
  - `Ctap2Processor` dispatching assertion vs makeCredential and building JSON responses.
- **Refactor:** freeze wire/JSON schemas as versioned constants.

**Done when:** processor tests pass with deterministic fixtures.

---

## 10. End-to-End Integration + DoD Verification (M5)

**Objective:** satisfy `agents.md` §6 Definition of Done.

### Verification checklist
1. `KeyInfo.isInsideSecureHardware == true` on device (Phase 6 test).
2. `Signature.sign()` without biometric auth throws (Phase 6 test).
3. Full loop: inbound JSON → biometric prompt (showing `rpId`) → sign → JSON response, over MockWebServer.
4. AES-GCM tag tamper → abort + security alert.
5. Stub daemon + `https://webauthn.io` manual E2E.

### Final commands
```bash
./gradlew testDebugUnitTest
./gradlew lint
./gradlew assembleDebug
./gradlew connectedDebugAndroidTest   # device required
```

---

## 11. Integration Test Harness — Mocked Daemon, Live Centrifugo (M6)

**Objective:** validate the full daemon → Centrifugo → Android app path on a
single machine by replacing the daemon/browser side with a mock that generates
synthetic CTAP2 requests and consumes the real app responses.

> This is **not** the unit-test FakeTransport from Phase 8 — those tests never
> touch Centrifugo. Phase 11 connects to a real (local) Centrifugo instance and
> exercises the Android relay layer end-to-end, but fakes the daemon/CTAP2 peer
> so the harness can be driven programmatically without a running Linux daemon
> or a real browser.

### Architecture

```
 MockDaemon ──(Centrifugo, AES-GCM)──> Android App
      ▲                                    │
      └──── assertionResult / ◄────────────┘
            makeCredentialResult
```

- **MockDaemon:** connects to Centrifugo over WebSocket, subscribes to
  `fidobridge.<channel_id>`, seals and publishes synthetic `getAssertion` or
  `makeCredential` requests, then waits for and validates the sealed response.
- **Android App:** the real `RelayClient` + `Ctap2Processor` running in an
  instrumented test (or on-device), receiving the request, signing with TEE
  (or a test key), and publishing the sealed response.
- Both MockDaemon and the Android app use the same `channel_id` and session
  key; the MockDaemon runs on the host machine (Python, reusing the daemon's
  `crypto.py` helpers), while the Android side runs in an `androidTest`.

### Steps

1. **`androidTest/.../harness/MockDaemon.kt`** — Kotlin WebSocket client that:
   - Connects to the local Centrifugo with the test JWT.
   - Subscribes to `fidobridge.<channel_id>`.
   - Seals a canned `getAssertion` or `makeCredential` `WireMessage` using the
     same `AesGcmCipher` from production code and publishes it.
   - Listens for the response publication, opens it, and asserts the plaintext
     matches the expected `assertionResult` or `makeCredentialResult` shape.
   - Implements the full `PROTOCOL.md` §3 envelope (nonce, ciphertext, tag,
     channel_id) so the exercise is wire-realistic.

2. **`androidTest/.../harness/HarnessConfig.kt`** — reads from
   `BuildConfig` or test runner arguments:
   - `RELAY_URL` (default `ws://10.0.2.2:8000/connection/websocket` for
     emulator → host loopback; overridable for real device).
   - `RELAY_TOKEN`, `CHANNEL_ID`, `SESSION_KEY_B64` — same values used by the
     Android app under test, injected via `gradle.properties` or env at build
     time.

3. **`androidTest/.../IntegrationHarnessTest.kt`** (TDD):
   1. `test_harness_get_assertion` — MockDaemon publishes a sealed
      `getAssertion` request; the app's `RelayClient` receives it, the
      `Ctap2Processor` builds a signed `assertionResult` (using a test key
      when biometrics are unavailable), and MockDaemon validates the response
      shape and signature.
   2. `test_harness_make_credential` — same flow for `makeCredential`,
      MockDaemon validates `attestationObject` CBOR structure and
      `authenticatorData` flags (`0x45`).
   3. `test_harness_error_response` — MockDaemon sends a request with an
      unknown credential id; the app returns `operationDenied` (0x27);
      MockDaemon validates the error code.
   4. `test_harness_round_trip_e2ee` — end-to-end: MockDaemon sends, app
      seals the response, MockDaemon opens it; verifies the session key is
      never transmitted in plaintext on the wire (capture traffic and assert
      no key bytes appear).

4. **`harness.py` CLI** (host-side, Python) — `python -m
   android_harness.mock_daemon` starts the MockDaemon, publishes the
   requested scenario, and reports pass/fail. Useful for quick dev-time smoke
   tests:

   ```bash
   FIDO2_RELAY_URL=ws://localhost:8000/connection/websocket \
   FIDO2_SESSION_KEY_B64=<same-as-app> \
   python -m android_harness.mock_daemon get-assertion
   ```

5. **Integration into `connectedDebugAndroidTest`** — the harness tests run
   as part of the instrumented test suite when a local Centrifugo is available.
   Gate them behind a `@RequiresExternalService` annotation or
   `BuildConfig.RUN_HARNESS` flag so CI (no Centrifugo) stays green.

### TDD Gate

- All four test cases in `IntegrationHarnessTest.kt` pass against a local
  Centrifugo instance.
- `python -m android_harness.mock_daemon all` exits 0.
- Default `connectedDebugAndroidTest` without the flag skips the harness
  tests — no regression on the existing test suite.

**Done when:** harness tests pass and the mock round-trip exercises the real
Centrifugo relay path from the Android side.

---

## Milestones Recap

| Milestone | Content | Test gate |
|-----------|---------|-----------|
| M1 | Scaffold + toolchain + Hilt/Compose | smoke test green |
| M2 | AES-GCM, codec, pairing, QR UI | cipher/codec/pairing/VM tests |
| M3 | Keystore + biometric signing | instrumented keystore/biometric |
| M4 | Centrifugo relay + foreground service | RelayClient + fake-transport tests |
| M5 | CTAP2 processor + DoD | processor + integration tests |
| M6 | Integration harness (mock daemon, live Centrifugo) | harness + round-trip tests |

Each milestone is only "done" when its tests pass — no implementation code precedes its failing test.

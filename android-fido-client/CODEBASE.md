# Android Client Codebase Inventory

Snapshot of `android-fido-client/` as of 2026-09-27.

---

## Package Structure

```
com.fidobridge.client
├── MainActivity.kt                    # Single Activity, @AndroidEntryPoint
├── FidoBridgeApplication.kt           # @HiltAndroidApp
├── di/
│   └── DataModule.kt                  # Hilt DI: provides SessionKeyStore, PairingRepository
├── util/
│   └── Base64.kt                      # Base64 standard/URL encode/decode
├── crypto/
│   ├── AesGcmCipher.kt               # AES-256-GCM (javax.crypto, 12-byte nonce, 16-byte tag)
│   ├── SessionKey.kt                  # 32-byte key value class
│   └── EncryptedMessage.kt            # Data class: nonce, ciphertext, tag
├── networking/
│   ├── RelayTransport.kt              # Interface: connect, disconnect, publish, listener
│   ├── CentrifugoTransport.kt         # WebSocket impl using centrifuge-java SDK
│   ├── RelayClient.kt                 # High-level: encrypt outbound, decrypt inbound, replay detection
│   ├── ReplayCache.kt                 # LRU cache (capacity 512) for replay detection
│   └── FidoBridgeService.kt           # Foreground Service with notification channel
├── protocol/
│   ├── WireMessage.kt                 # Data class: channelId, nonce, ciphertext, tag
│   ├── MessageCodec.kt                # JSON serialization/deserialization of WireMessage
│   ├── PlaintextEnvelope.kt           # @Serializable: version, type, id, payload (JsonObject)
│   ├── Protocol.kt                    # Constants: VERSION=1, SCHEME, RELAY_CHANNEL_PREFIX
│   └── Ctap2Status.kt                # CTAP2 error codes (0x01, 0x26, 0x27, 0x7F)
├── ctap/
│   ├── Ctap2Processor.kt             # Core: handles getAssertion, makeCredential, ping
│   ├── Messages.kt                    # Request/response DTOs
│   ├── Credential.kt                  # StoredCredential, GeneratedCredential, KeyGenerator, CredentialStore, Signer
│   ├── CoseKey.kt                     # COSE key encoding (EC2, ES256, P-256)
│   ├── AttestationObjectBuilder.kt    # CBOR attestation object (fmt=none)
│   └── AuthenticatorDataBuilder.kt    # WebAuthn authenticator data
├── pairing/
│   ├── PairingInfo.kt                 # Data class: channel, channelId, key
│   ├── PairingRepository.kt           # Parses fidobridge:// URIs, derives channelId via SHA-256
│   ├── PairingViewModel.kt            # @HiltViewModel, drives pairing UI state machine
│   ├── PairingUiState.kt              # Sealed interface: Scanning, Paired, Error
│   ├── SessionKeyStore.kt            # Interface
│   ├── EncryptedSessionKeyStore.kt    # EncryptedSharedPreferences impl
│   └── PairingException.kt           # MalformedPairingUriException, VersionMismatchException
├── security/
│   ├── KeystoreManager.kt            # Android Keystore: EC P-256, StrongBox-backed
│   ├── BiometricSigner.kt            # BiometricPrompt + crypto signing
│   ├── BiometricAuthenticator.kt     # Interface
│   └── OperationDeniedException.kt
└── ui/
    ├── FidoBridgeApp.kt               # Root Composable + NavHost (pairing, home)
    ├── AppViewModel.kt                # @HiltViewModel, isPaired state
    ├── theme/Theme.kt                 # Material3 theme
    └── pairing/
        ├── PairingScreen.kt           # QR scanner + manual URI input
        └── QrScanner.kt              # ZXing BarcodeView wrapper
```

---

## Key APIs

### RelayClient (`networking/RelayClient.kt`)
- Constructor: `(transport: RelayTransport, channelId: String, cipher: AesGcmCipher, replayCache, json)`
- `connect()` — starts transport connection
- `send(payload: ByteArray): Boolean` — encrypts + publishes
- `close()` — disconnects
- `inbound: Flow<ByteArray>` — decrypted inbound plaintexts
- `state: StateFlow<ConnectionState>` — DISCONNECTED / CONNECTING / CONNECTED
- `securityAlerts: Flow<Unit>` — tag mismatch, channel mismatch, decode errors
- `disconnections: Flow<Unit>` — emitted on disconnect

### RelayTransport (`networking/RelayTransport.kt`)
- Interface: `connect()`, `disconnect()`, `publish(data, onDone)`, `setListener(listener)`
- Listener: `onPublication(data)`, `onConnected()`, `onDisconnected(code, reason)`

### CentrifugoTransport (`networking/CentrifugoTransport.kt`)
- Constructor: `(endpoint: String, channel: String)`
- Uses `BuildConfig.RELAY_TOKEN` for auth
- `centrifuge-java` SDK: `Client`, `Subscription`, `PublicationEvent`

### AesGcmCipher (`crypto/AesGcmCipher.kt`)
- Constructor: `(key: SessionKey)`
- `encrypt(plaintext: ByteArray): EncryptedMessage` — random 12-byte nonce
- `decrypt(message: EncryptedMessage): ByteArray` — throws `TagMismatchException`

### Ctap2Processor (`ctap/Ctap2Processor.kt`)
- Constructor: `(credentialStore, keyGenerator, signer, json)`
- `process(plaintext: ByteArray, onResult: (Result<ByteArray>) -> Unit)`
- Handles: `getAssertion`, `makeCredential`, `ping`
- Uses injected fakes for `CredentialStore`, `KeyGenerator`, `Signer`

### MessageCodec (`protocol/MessageCodec.kt`)
- `encode(wire: WireMessage): String` — JSON with Base64 fields
- `decode(json: String): WireMessage` — validates channel_id format

---

## Test Infrastructure

### Unit Tests (`app/src/test/`)
| File | Tests |
|------|-------|
| `ArithmeticSmokeTest.kt` | 1 (smoke) |
| `networking/RelayClientTest.kt` | 5 (encrypt, decrypt, tag fail, channel mismatch, replay, disconnect) |
| `networking/ReplayCacheTest.kt` | 3 (first, replay, LRU eviction) |
| `crypto/AesGcmCipherTest.kt` | 6 (round-trip, nonce, tamper, wrong key) |
| `protocol/MessageCodecTest.kt` | 6 (round-trip, missing fields, base64, nonce) |
| `ctap/Ctap2ProcessorTest.kt` | 7 (getAssertion, makeCredential, rpId, unknown cred, multi cred, no ES256, exclude) |
| `ctap/CoseKeyTest.kt` | 1 (CBOR layout) |
| `ctap/AuthenticatorDataBuilderTest.kt` | 2 (assertion, makeCredential) |
| `pairing/PairingRepositoryTest.kt` | 10 (valid URI, missing, bad, key length, version, unknown, dupes, store) |
| `pairing/PairingViewModelTest.kt` | 4 (initial, QR valid, QR invalid, manual) |

### Test Fakes
| Fake | Location | Replaces |
|------|----------|----------|
| `FakeRelayTransport` | `test/.../networking/` | `RelayTransport` — `simulatePublication()`, `simulateDisconnect()` |
| `FakeCredentialStore` | `test/.../ctap/Ctap2ProcessorTest.kt` (private) | `CredentialStore` — in-memory list |
| `FakeKeyGenerator` | `test/.../ctap/Ctap2ProcessorTest.kt` (private) | `KeyGenerator` — deterministic counter |
| `FakeSigner` | `test/.../ctap/Ctap2ProcessorTest.kt` (private) | `Signer` — returns fixed signature |
| `FakeSessionKeyStore` | `test/.../pairing/` | `SessionKeyStore` — in-memory |

### Instrumented Tests (`app/src/androidTest/`)
| File | Tests |
|------|-------|
| `security/KeystoreManagerTest.kt` | 5 (secure hardware, EC P-256, auth required, signing, stability) |
| `security/BiometricSignerTest.kt` | 2 (auth failure, prompt subtitle) |

---

## Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| centrifuge-java | (via Gradle) | Centrifugo WebSocket client |
| kotlinx-serialization-json | (via catalog) | JSON encoding/decoding |
| com.upokecenter:cbor | 4.5.2 | CBOR encoding (attestation objects) |
| com.journeyapps:zxing-android-embedded | (via catalog) | QR code scanning |
| androidx.biometric | (via catalog) | BiometricPrompt |
| androidx.security:security-crypto | (via catalog) | EncryptedSharedPreferences |
| Hilt | 2.51.1 | Dependency injection |
| Compose BOM | 2024.09.03 | UI framework |
| OkHttp | (via catalog) | HTTP client |
| MockK | 1.13.12 | Test mocking |
| Turbine | 1.1.0 | Flow testing |

---

## Build Config

- `compileSdk`: 34
- `minSdk`: 26
- `targetSdk`: 34
- `applicationId`: `com.fidobridge.client`
- `BuildConfig.RELAY_TOKEN` — injected at build time from `pass` CLI

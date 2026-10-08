# AGENTS.md — Remote WebAuthn / Passkey Hardware-Backed Bridge

## 1. Project Overview & Architecture
This project consists of two software components linked via an End-to-End Encrypted (E2EE) WebSocket relay:

1. **Android Client (`android-fido-client`):** A native Android application that receives WebAuthn/CTAP2 challenges over WebSocket, prompts for local biometric verification (`BiometricPrompt`), and uses Android's Hardware KeyStore (TEE/StrongBox) to sign the challenge.
2. **Linux Server Daemon (`linux-fido-daemon`):** A Linux background daemon running on a remote server that exposes a Unix domain socket (`/run/user/<UID>/fido2.sock`), intercepts WebAuthn requests from local applications/browsers, and routes them through the WebSocket relay to the Android Client.

```
┌───────────────────────────────────────────────────────────────────────────┐
│                           SYSTEM ARCHITECTURE                             │
│                                                                           │
│ [ Remote Linux Server ]               [ Cloud Relay ]    [ Android Phone ]│
│ ┌──────────────────────┐             ┌───────────────┐  ┌────────────────┐│
│ │ Local Browser        │             │ Third-Party / │  │ Native Android ││
│ │   │ (CTAP2 Request)  │             │ Cloud WS      │  │ App            ││
│ │   ▼                  │             │ Server        │  │                ││
│ │ Linux Daemon Socket  │ ─ AES-GCM ─►│ (Dumb Broker) │─►│ WS Listener    ││
│ │ (virtual-hid/socket) │             │               │  │   │            ││
│ └──────────────────────┘             └───────────────┘  │   ▼            ││
│                                                         │ BiometricPrompt││
│                                                         │   │            ││
│                                                         │   ▼            ││
│                                                         │ Android        ││
│                                                         │ KeyStore (TEE) ││
│                                                         └────────────────┘│
└───────────────────────────────────────────────────────────────────────────┘
```

### 1.1 Protocol Specification (`PROTOCOL.md`)

`PROTOCOL.md` (repo root) is the **single source of truth** for every wire
format exchanged between the daemon and the Android client: the pairing URI,
the AES-256-GCM `WireMessage` envelope, the plaintext message schema, the
message types (`getAssertion`, `makeCredential`, results, `error`, `ping`),
authenticator-data flags, and error codes.

**The developer agent MUST:**
1. Consult `PROTOCOL.md` before implementing or modifying any pairing,
   encryption, or message-format code in either component.
2. Treat its JSON Schemas, ABNF grammar, and pinned constants (nonce/tag
   lengths, base64 encodings, flag values, error codes) as authoritative —
   do not deviate from or re-invent them.
3. Mirror any protocol change back into `PROTOCOL.md` (bump `PROTOCOL_VERSION`
   for breaking changes) and keep both `ANDROID_PLAN.md` / `DAEMON_PLAN.md`
   fixtures and both implementations in lock-step, per §8 of `PROTOCOL.md`.
4. Not hardcode wire formats inline; reference `PROTOCOL.md` for the canonical
   definition and keep versioned constants in a single place per peer.

When in doubt, `PROTOCOL.md` wins over anything in this file or the per-side
plans.

---

## 2. General Engineering Principles for the AI Agent
* **Strict Security-First Design:** Private keys MUST NEVER leave the Android hardware enclave (TEE/StrongBox). The WebSocket relay server MUST be treated as untrusted; all transmitted data must be E2EE using AES-256-GCM.
* **No Speculative Code:** Use officially supported libraries. Do not invent proprietary cryptographic primitives.
* **Modern Toolchains:** Target Android API Level 34+ (Kotlin 2.0+) and Linux Python 3.11+ / Rust (2021 edition).
* **Fail-Safe Behavior:** If biometric authentication fails, times out, or the origin does not match, return standard CTAP2 error codes (`CTAP2_ERR_OPERATION_DENIED`) and abort immediately.
* **Protocol Conformance:** All pairing/relay wire formats must follow `PROTOCOL.md` exactly (see §1.1). Reject malformed input (no silent nulls, no padding/base64 mixing), and abort on GCM tag failure.

---

## 3. Android Client Guidelines (`android-fido-client`)

> Wire formats: see `PROTOCOL.md` §1.1. All pairing-URI parsing, wire-message
> sealing/opening, and CTAP2 JSON responses MUST follow `PROTOCOL.md`
> (sections 2–5) and its JSON Schemas.

### Tech Stack & Libraries
* **Language:** 100% Kotlin with Coroutines and Flow for asynchronous events.
* **Architecture:** Modern Android MVVM / Clean Architecture using Jetpack Compose for UI.
* **Security & Crypto:**
  * `androidx.biometric:biometric` (for secure user prompt).
  * `android.security.keystore` (for hardware-backed key generation and signing).
* **Networking:** `com.squareup.okhttp3:okhttp` (WebSocket client).

### Best Practices & Strict Requirements
1. **Hardware-Backed Key Generation (`KeyStore`):**
   When generating asymmetric key pairs (EC P-256 / secp256r1), enforce strict hardware isolation and biometric locking:
   ```kotlin
   val keyPairGenerator = KeyPairGenerator.getInstance(
       KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore"
   )
   val keyGenParameterSpec = KeyGenParameterSpec.Builder(
       alias,
       KeyProperties.PURPOSE_SIGN
   )
       .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
       .setDigestProperties(KeyProperties.DIGEST_SHA256)
       // ENFORCE BIOMETRIC CHECK AT HARDWARE LEVEL
       .setUserAuthenticationRequired(true)
       .setUserAuthenticationParameters(
           0, // 0 = requires authentication for EVERY signature
           KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
       )
       .setIsStrongBoxBacked(true) // Fallback to TEE if StrongBox isn't present
       .build()

   keyPairGenerator.initialize(keyGenParameterSpec)
   keyPairGenerator.generateKeyPair()
   ```

2. **BiometricPrompt Integration:**
   * Authenticate using `BiometricPrompt.CryptoObject` initialized with the `Signature` object bound to the KeyStore key.
   * This ensures the key is unlocked **only inside the Android TEE/StrongBox** upon successful fingerprint or face scan.

3. **Background Lifecycles & Battery Management:**
   * Do NOT run a persistent background service that Android will kill. 
   * Expect the user to manually open the app before starting a login sequence.
   * Maintain an active WebSocket connection while the app is in the foreground or executing an active foreground service session (`ForegroundServiceType.DATA_SYNC`).

---

## 4. Linux Daemon Guidelines (`linux-fido-daemon`)

> Wire formats: see `PROTOCOL.md` §1.1. Pairing-URI generation, wire-message
> sealing/opening, and the JSON relay schema MUST follow `PROTOCOL.md`
> (sections 2–5). The daemon's own wire output must validate against the
> `PROTOCOL.md` §3.1 JSON Schema (canonical unpadded base64).

### Tech Stack & Libraries
* **Language:** Python 3.11+ (using `uv` or `poetry`) OR Rust.
* **Libraries:**
  * `python-fido2` (Yubico official library) or `libfido2` bindings.
  * `cryptography` (for local AES-256-GCM encryption).
  * `websockets` (Python asyncio client) or `tokio-tungstenite` (Rust).
* **System Integration:** `systemd` user service unit (`~/.config/systemd/user/fido-daemon.service`).

### Best Practices & Strict Requirements
1. **Socket Management:**
   * Bind the Unix domain socket strictly inside the runtime user directory to enforce Linux filesystem permissions: `/run/user/<UID>/fido2-bridge.sock` (chmod `0600`).
   * Support overriding the socket location via environment variables (`FIDO2_REMOTE_SOCKET`).

2. **CTAP2 Interception & Forwarding:**
   * Implement a lightweight FIDO2 middleware that intercepts `authenticatorGetAssertion` and `authenticatorMakeCredential` requests.
   * Convert raw WebAuthn/CTAP requests into JSON schema messages containing:
     * `clientDataHash` / `challenge`
     * `rpId` (Relying Party ID / Domain)
     * `allowCredentials` (Credential IDs)
   * Time out requests if no response arrives from the Android Client within **30 seconds**.

3. **Systemd Service Spec:**
   Create a non-root systemd user service file:
   ```ini
   [Unit]
   Description=Remote WebAuthn Android Bridge Daemon
   After=network.target

   [Service]
   Type=simple
   ExecStart=/usr/bin/python3 -m fido_daemon.cli --socket /run/user/1000/fido2-bridge.sock
   Restart=on-failure
   Environment=PYTHONUNBUFFERED=1

   [Install]
   WantedBy=default.target
   ```

---

## 5. End-to-End Encryption Protocol (E2EE)

Because the WebSocket server is hosted on a third-party or untrusted cloud provider, **all payloads must be encrypted before hitting the WebSocket network**.

### Pairing Setup (QR Code)
1. On initial setup, the Linux Daemon generates a 256-bit random key ($K_{session}$) and a 128-bit Channel ID ($ID_{channel}$).
2. The Linux Daemon displays this as a QR code or terminal string format: 
   `fidobridge://pair?channel=<ID>&key=<BASE64_AES_KEY>`
3. The Android App scans the QR code to store $K_{session}$ in encrypted `EncryptedSharedPreferences`.

### Wire Payload Schema
All messages over WebSocket must adhere to this JSON format:

```json
{
  "channel_id": "HASHED_CHANNEL_ID_STRING",
  "nonce": "BASE64_12BYTE_GCM_NONCE",
  "ciphertext": "BASE64_AES_256_GCM_PAYLOAD",
  "tag": "BASE64_16BYTE_AUTH_TAG"
}
```

### Encryption Algorithm
* **Cipher:** AES-256-GCM.
* **Nonce:** Must be a cryptographically secure random 96-bit (12-byte) value generated **per message**. Never reuse a nonce.
* **Decryption:** Abort and log a security alert if the GCM authentication tag fails to verify.

---

## 6. Definition of Done & Testing Criteria

The AI agent must verify the following tests before completing the implementation:

1. **Android Security Verification:**
   * Verify using Android Studio Profiler/KeyStore tools that the generated private key is marked with `InsideSecureHardware = true`.
   * Confirm that triggering `Signature.sign()` without prior `BiometricPrompt` authorization throws an `UserNotAuthenticatedException`.

2. **Linux Daemon Socket Verification:**
   * Verify the Unix socket is correctly created under `/run/user/<UID>/` with `0600` permissions.
   * Confirm that closing the Linux daemon cleanly unlinks the socket file.

3. **End-to-End WebAuthn Test:**
   * Connect remote browser (e.g., Chrome/Firefox on VNC) to test via `https://webauthn.io`.
   * Ensure that clicking "Authenticate":
     1. Triggers the Linux Daemon over the socket.
     2. Encrypts payload and delivers it over WebSockets.
     3. Triggers `BiometricPrompt` on the Android device displaying the target Domain Name (`rpId`).
     4. Successfully signs and completes WebAuthn authentication in the remote browser.
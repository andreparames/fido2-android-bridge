# FIDO2 Android Bridge

Use your Android phone as a FIDO2/WebAuthn hardware security key for a remote Linux server.

When you log in to a website with passkeys or a security key on a headless Linux server (e.g. over SSH, a VPS, or a remote workstation), this tool lets your Android phone handle the biometric prompt — just like a physical YubiKey, but backed by your phone's secure hardware.

## How it works

```
Browser on Linux  ──(Unix socket)──▶  Linux daemon  ──(encrypted relay)──▶  Android phone
                                                    ◀──────────────────
```

1. **Linux daemon** listens on a local Unix socket, presenting itself as a FIDO2 authenticator to your browser or CLI tools.
2. When a website asks for a security key, the daemon encrypts the challenge (AES-256-GCM) and sends it through a WebSocket relay (Centrifugo) to your phone.
3. **Android app** prompts for your fingerprint or face, signs the challenge using a private key stored in the phone's TEE/StrongBox, and sends the signed response back.
4. The daemon delivers the response to the browser — the website never knows the difference.

The relay server is **untrusted** — every payload is end-to-end encrypted before it leaves either device. Private keys never leave your phone.

## Components

| Component | Description |
|-----------|-------------|
| [`linux-fido-daemon/`](linux-fido-daemon/) | Python daemon — Unix socket server, CTAP2 interception, E2EE relay client |
| [`android-fido-client/`](android-fido-client/) | Kotlin Android app — QR pairing, biometric signing, Centrifugo relay |
| [`relay/`](relay/) | Centrifugo relay configuration |

## Getting started

### 1. Set up the relay

You need a running [Centrifugo](https://centrifugal.dev/) instance. See [`relay/`](relay/) for a sample config.

### 2. Pair the daemon with your phone

On your Linux server:

```bash
cd linux-fido-daemon
python3 -m venv .venv && source .venv/bin/activate
pip install -e "."

fido-daemon pair
# Prints: fidobridge://pair?channel=<hex>&key=<base64url>
```

Open the Android app, scan the QR code (or paste the URI).

### 3. Run the daemon

```bash
export FIDO2_SESSION_KEY_B64=<key from pairing>
export FIDO2_RELAY_URL=wss://your-relay.example.com/connection/websocket
fido-daemon
```

Or install as a systemd user service:

```bash
install -D -m 0644 linux-fido-daemon/systemd/fido-daemon.service ~/.config/systemd/user/
systemctl --user enable --now fido-daemon
```

### 4. Use it

With `--uhid` enabled, any WebAuthn-capable browser (e.g. at [webauthn.io](https://webauthn.io)) will see a virtual security key and authenticate through your phone.

```bash
fido-daemon --uhid
```

## Security

- Private keys are generated and stored in the phone's hardware secure element (TEE or StrongBox).
- Every signature requires biometric authentication — there is no fallback to software-only signing.
- All data in transit is encrypted with AES-256-GCM using a shared session key exchanged during pairing.
- The relay server never sees plaintext — it only forwards opaque ciphertext.
- GCM tag failures are treated as security alerts and the connection is dropped.

See [`PROTOCOL.md`](PROTOCOL.md) for the full wire format specification and [`agents.md`](agents.md) for the security requirements.

## Development

Both components are built with strict **Test-Driven Development**. See:
- [`DAEMON_PLAN.md`](DAEMON_PLAN.md) — Linux daemon build plan
- [`ANDROID_PLAN.md`](ANDROID_PLAN.md) — Android client build plan

```bash
# Daemon tests
cd linux-fido-daemon && .venv/bin/pytest

# Android tests
cd android-fido-client && ./gradlew testDebugUnitTest
```

## Status

This project is in active development. The daemon supports Unix socket access and browser WebAuthn via a virtual HID device. The Android app handles pairing, relay communication, and biometric signing. Integration testing is in progress.

See each component's README for detailed status and next steps.

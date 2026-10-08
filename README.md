<p align="center">
  <img src=".github/logo.png" alt="FIDO2 Android Bridge" width="128" height="128">
</p>

<h1 align="center">FIDO2 Android Bridge</h1>

Use your Android phone as a FIDO2/WebAuthn hardware security key for a remote Linux server.

When you log in to a website with passkeys or a security key on a headless Linux server (e.g. over SSH, a VPS, or a remote workstation), this tool lets your Android phone handle the biometric prompt — just like a physical YubiKey, but backed by your phone's secure hardware.

## How it works

```
Browser on Linux  ──(Unix socket)──▶  Linux daemon  ──(encrypted relay)──▶  Android phone
                                                    ◀──────────────────
```

1. **Linux daemon** listens on a local Unix socket, presenting itself as a FIDO2 authenticator to your browser or CLI tools.
2. When a website asks for a security key, the daemon authenticates to your phone with a Noise-protocol handshake and sends the encrypted challenge through a WebSocket relay (Centrifugo).
3. **Android app** prompts for your fingerprint or face, signs the challenge using a private key stored in the phone's TEE/StrongBox, and sends the signed response back.
4. The daemon delivers the response to the browser — the website never knows the difference.

The relay server is **untrusted** — every payload is encrypted with the Noise Protocol (`Noise_IK_25519_AESGCM_SHA256`) before it leaves either device. Session keys are derived per connection from fresh ephemeral keys (perfect forward secrecy). Private keys never leave your phone.

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

fido-daemon pair -c ~/.config/fido-daemon/config.toml
# Prints: fidobridge://pair?channel=<hex>&pubkey=<base64url>
# Creates the daemon's static X25519 identity key and writes the channel id
# (and relay token) to the config file.
```

Open the Android app, scan the QR code (or paste the URI).

### 3. Run the daemon

```bash
export FIDO2_RELAY_URL=wss://your-relay.example.com/connection/websocket
fido-daemon -c ~/.config/fido-daemon/config.toml
```

The first time your phone connects, the daemon pins the phone's public key
(trust-on-first-use) and stores it in the config file; later handshakes from a
different phone are rejected.

**Pairing with a different phone:** run `fido-daemon pair -c <config>` to get a
fresh URI for the new phone, then `fido-daemon unpair -c <config>` to clear the
old phone's pinned key so the new phone is accepted.

Alternatively, configure everything via environment variables (the phone pin
then lasts only for the running session):

```bash
export FIDO2_CHANNEL_ID=<channel_id from pairing>
export FIDO2_RELAY_URL=wss://your-relay.example.com/connection/websocket
fido-daemon
```

Or install as a systemd user service (run `fido-daemon pair -c` first to create
the config file):

```bash
install -D -m 0644 linux-fido-daemon/systemd/fido-daemon.service ~/.config/systemd/user/
systemctl --user daemon-reload
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
- All data in transit is encrypted with the **Noise Protocol** (`Noise_IK_25519_AESGCM_SHA256`). Session keys are derived per connection from fresh ephemeral keys, so each session has **perfect forward secrecy**.
- The daemon pins the phone's public key on first use; handshakes from a different phone are rejected.
- The relay server never sees plaintext — it only forwards opaque Noise handshake and transport messages.
- Noise authentication failures (e.g. a replayed or tampered frame) are treated as security alerts and the connection is dropped.

See [`PROTOCOL.md`](PROTOCOL.md) for the full wire format specification and [`AGENTS.md`](AGENTS.md) for the security requirements.

## Development

Both components are built with strict **Test-Driven Development**. See:
- [`linux-fido-daemon/plan.md`](linux-fido-daemon/plan.md) — Linux daemon build plan
- [`android-fido-client/plans/plan.md`](android-fido-client/plans/plan.md) — Android client build plan

```bash
# Daemon tests
cd linux-fido-daemon && .venv/bin/pytest

# Android tests
cd android-fido-client && ./gradlew testDebugUnitTest
```

## Status

This project is in active development. The daemon supports Unix socket access and browser WebAuthn via a virtual HID device. The Android app handles pairing, relay communication, and biometric signing. Integration testing is in progress.

See each component's README for detailed status and next steps.

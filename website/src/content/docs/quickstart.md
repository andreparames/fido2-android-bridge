---
title: "Quickstart"
description: "Install the server program, pair your phone, and sign in through Gatebridge in under 10 minutes."
order: 1
---

Get from zero to a hardware-backed signature through your phone.

## Prerequisites

- A Linux server (or local VM) where you can run a program
- An Android phone with fingerprint or face unlock
- Python 3.11 or newer on the server

## 1. Set up the relay

For the hosted product, skip to step 2. The managed relay is included.

To self-host, run [Centrifugo](https://centrifugal.dev/):

```bash
# Example: single-node Centrifugo
centrifugo --config=config.json
```

See the repository's `relay/` directory for a sample config.

## 2. Install the server program

```bash
git clone https://github.com/andreparames/fido2-android-bridge.git
cd fido2-android-bridge/linux-fido-daemon
python3 -m venv .venv && source .venv/bin/activate
pip install -e "."
```

## 3. Pair with your phone

```bash
fido-daemon pair -c ~/.config/fido-daemon/config.toml
```

This prints a pairing link:

```
fidobridge://pair?channel=<hex>&pubkey=<base64url>
```

Open the Gatebridge Android app, scan the QR code (or paste the link), and confirm the pairing.

## 4. Run the program

```bash
export FIDO2_RELAY_URL=wss://your-relay.example.com/connection/websocket
fido-daemon -c ~/.config/fido-daemon/config.toml
```

## 5. Sign in

With `--uhid` enabled, any security-key-aware browser will see a virtual hardware key:

```bash
fido-daemon --uhid
```

Visit [webauthn.io](https://webauthn.io), click Authenticate, and approve the prompt on your phone.

## Next steps

- Install as a [systemd service](#) for persistence
- Read the [security model](/security)
- See the [protocol specification](/open-source)

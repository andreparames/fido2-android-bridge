---
title: "Installation"
description: "Detailed installation of the Gatebridge server program, configuration options, and systemd setup."
order: 2
---

Full installation guide for the Gatebridge server program.

## Requirements

- Linux (tested on Ubuntu 22.04+, Debian 12+, Fedora 38+)
- Python 3.11 or newer
- `pip` and `venv`
- Network access to your relay (WebSocket)

## Install from source

```bash
git clone https://github.com/andreparames/fido2-android-bridge.git
cd fido2-android-bridge/linux-fido-daemon
python3 -m venv .venv
source .venv/bin/activate
pip install -e "."
```

Verify the install:

```bash
fido-daemon --version
```

## Configuration

The program reads a TOML config file (default: `~/.config/fido-daemon/config.toml`):

```toml
channel_id = "<from pairing>"
relay_url = "wss://your-relay.example.com/connection/websocket"
# relay_token = "..."  # if your relay requires auth
```

All values can also be set via environment variables:

| Variable | Description |
|---|---|
| `FIDO2_CHANNEL_ID` | Channel ID from pairing |
| `FIDO2_RELAY_URL` | WebSocket URL of the relay |
| `FIDO2_RELAY_TOKEN` | Optional relay auth token |

Environment-variable pinning lasts only for the running session.

## Pairing

```bash
fido-daemon pair -c ~/.config/fido-daemon/config.toml
```

- Generates the program's static identity key
- Writes channel ID (and relay token) to the config
- Prints a `fidobridge://pair?...` link for the Android app

**Pairing a different phone:**

```bash
fido-daemon pair -c <config>      # new link
fido-daemon unpair -c <config>    # clear old pinned key
```

## systemd user service

```bash
install -D -m 0644 linux-fido-daemon/systemd/fido-daemon.service \
  ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now fido-daemon
```

Make sure the config file exists (`fido-daemon pair -c ...`) before enabling the service.

## Virtual HID (browser sign-in)

To present the program as a virtual security key to browsers:

```bash
fido-daemon --uhid
```

Requires appropriate permissions to access `/dev/uhid`.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| Phone not accepted on handshake | Different phone than pinned. Re-pair or unpair. |
| Relay connection drops | Check `FIDO2_RELAY_URL`, TLS cert, network |
| Browser doesn't see the key | `--uhid` not enabled, or missing `/dev/uhid` permission |
| Phone prompt doesn't appear | Phone app not running, or channel mismatch |

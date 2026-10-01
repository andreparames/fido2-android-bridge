# fido-daemon

Linux daemon that bridges local CTAP2/WebAuthn requests to a remote Android
phone. It exposes a Unix domain socket that looks like a local FIDO2
authenticator, establishes a **Noise** (`Noise_IK_25519_AESGCM_SHA256`)
transport with the phone through an untrusted **Centrifugo** relay, forwards
each request, and writes the signed CTAP2 response back to the local client.

The phone holds the private keys; the daemon never sees plaintext on the wire,
and neither does the relay. See `agents.md` (repo root) for the security
requirements and `PROTOCOL.md` (repo root) for the authoritative wire formats.

## Architecture

```
 Local client ──(Unix socket)──> daemon ──(Centrifugo, Noise IK)──> phone
 <───────────────────────────────────────────────────────────────────┘
```

1. A local app/browser (or a virtual-HID shim) sends a CTAP2 request over the
   Unix socket at `/run/user/<UID>/fido2-bridge.sock` (`0600`).
2. The daemon decodes the request (`ctap2.py`) into the `PROTOCOL.md` §4/§5
   plaintext message and routes it through the Noise transport (`relay.py`).
3. On (re)connect the phone initiates the handshake: it publishes `ik1`, the
   daemon replies `ik2`, and both `split()` — fresh ephemeral keys per session
   give perfect forward secrecy.
4. `data` envelopes carry Noise ciphertext. The phone opens requests, prompts
   for biometrics, signs in the TEE, and publishes an `assertionResult` /
   `makeCredentialResult` with the same correlation `id`.
5. The daemon matches the response by `id`, opens it, and writes the CTAP2 reply
   (status byte + CBOR) back to the socket.

The relay is untrusted: every payload is Noise-protected before publish; any
authentication failure (replayed/tampered frame, unknown peer key) is logged as
a security alert and the message is dropped.

## Layout

```
src/fido_daemon/
  cli.py           entry point (python -m fido_daemon.cli / fido-daemon)
                   full request/response wiring + lifecycle management
  config.py        env-driven configuration (Config.from_env)
  protocol.py      frozen versioned constants (version, message types,
                   CTAP2 status codes, COSE alg)
  crypto.py        canonical unpadded base64 helpers (standard alphabet)
  noise.py         Noise_IK responder/initiator sessions, static key store,
                   WireEnvelope JSON codec (kind=ik1|ik2|data)
  ctap2.py         CBOR request decoding + CTAP2 response encoding
  pairing.py       PairingGenerator + derive_channel_id (SHA-256 digest)
  pairing_uri.py   fidobridge:// pairing URI formatter/parser (pubkey=)
  relay.py         RelayClient over centrifuge.Client (Noise handshake, token
                   auth, id correlation, timeout, tamper alert)
  socket_server.py Unix socket server (0600, clean unlink on exit)
  harness.py       integration test harness (mocked browser + phone, live Centrifugo)
src/harness_common/
  config.py        shared HarnessConfig for integration harness scripts
src/mock_daemon/
  __init__.py      MockDaemon: publish synthetic CTAP2 requests, validate responses
  __main__.py      entry point (python -m mock_daemon)
src/mock_phone/
  __init__.py      MockPhone: subscribe to Centrifugo, open Noise handshake, respond
  __main__.py      entry point (python -m mock_phone)
systemd/           user service unit (fido-daemon.service)
tests/             pytest suite (fakes/ has an in-memory broker + Noise phone peer)
  harness/         MockBrowser + MockPhone for integration testing (Phase 9)
```

## Setup

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -e ".[dev]"
pytest
```

Dependencies: `fido2`, `cryptography`, `noiseprotocol`, `centrifuge-python`
(dev: `pytest`, `pytest-asyncio`).

## Configuration

All values are read from the environment by `Config.from_env()`, optionally
overridden by a TOML config file (`-c/--config`) holding `channel_id`,
`relay_token`, and the pinned `phone_public_key`.

| Variable                    | Default                                        | Purpose |
|-----------------------------|------------------------------------------------|---------|
| `FIDO2_REMOTE_SOCKET`       | `/run/user/<UID>/fido2-bridge.sock`            | Unix socket path |
| `FIDO2_RELAY_URL`           | `wss://relay.example.invalid/connection/websocket` | Centrifugo WebSocket endpoint |
| `FIDO2_CHANNEL_ID`          | `""` (required to run)                         | **derived** channel id (32 lowercase hex) |
| `FIDO2_STATIC_KEY_PATH`     | `~/.config/fido-daemon/static_key.pem`         | daemon's long-term X25519 static key (0600) |
| `FIDO2_PHONE_PUBLIC_KEY`    | `""`                                           | pinned phone static key (base64, 32 bytes); overrides the learned pin |
| `FIDO2_RELAY_TOKEN`         | `""` (anonymous)                               | Centrifugo connection JWT |
| `FIDO2_REQUEST_TIMEOUT`     | `30.0`                                         | Relay round-trip timeout (seconds) |

`FIDO2_RELAY_TOKEN` is attached on startup and on every reconnect (via the
SDK's `get_token` callback) and is never logged or leaked into the URL. Leave
it empty for an unprotected/anonymous Centrifugo during local development.

The phone's static key is pinned by **trust-on-first-use**: the first phone
that completes a valid handshake is stored in the config file
(`phone_public_key`) and enforced on every later handshake. `FIDO2_PHONE_PUBLIC_KEY`
(or the config value) overrides the learned key.

**Reset the pin to pair a different phone** — generate a fresh pairing URI
(which rotates the channel) and clear the stored pin:

```bash
fido-daemon pair -c ~/.config/fido-daemon/config.toml    # new URI for the new phone
fido-daemon unpair -c ~/.config/fido-daemon/config.toml  # clear the old phone's pin
```

The new phone's key is learned and pinned on its first handshake. `unpair`
prompts for confirmation; pass `--confirm` to skip it (e.g. in scripts). (You
can also delete the `phone_public_key` line from the config file, or unset
`FIDO2_PHONE_PUBLIC_KEY`.)

## Usage

Pair the daemon with the phone, writing the channel id (and relay token) to a
config file, and printing a QR-able URI for the Android app:

```bash
fido-daemon pair -c ~/.config/fido-daemon/config.toml
# fidobridge://pair?channel=<32hex>&pubkey=<base64url-daemon-static-key>
```

Run the daemon:

```bash
export FIDO2_RELAY_URL=wss://relay.example.com/connection/websocket
export FIDO2_RELAY_TOKEN=<jwt>          # omit for anonymous
python -m fido_daemon.cli -c ~/.config/fido-daemon/config.toml
```

Or configure entirely via environment variables (the phone pin then lasts only
for the running session):

```bash
export FIDO2_CHANNEL_ID=<derived-32hex-id>
export FIDO2_STATIC_KEY_PATH=<path-to-static-key>   # optional
export FIDO2_RELAY_URL=wss://relay.example.com/connection/websocket
export FIDO2_RELAY_TOKEN=<jwt>          # omit for anonymous
python -m fido_daemon.cli
```

Or install the user service (run `fido-daemon pair -c` first; supply secrets
via a `0600` `EnvironmentFile`, not baked into the unit):

```bash
install -D -m 0644 systemd/fido-daemon.service ~/.config/systemd/user/
systemctl --user enable --now fido-daemon
```

## Socket protocol

The local socket speaks a minimal CTAP2 framing (daemon-internal, not in
`PROTOCOL.md`):

- **Request:** one command byte (`0x01` makeCredential, `0x02` getAssertion)
  followed by the CBOR-encoded request map.
- **Response:** a leading status byte — `0x00` followed by CBOR on success, or a
  single non-zero CTAP2 status byte on error.

## Testing

```bash
.venv/bin/pytest                    # unit tests (fast, no external deps)
FIDO2_HARNESS=1 .venv/bin/pytest   # include integration harness tests
```

The suite uses an in-memory `FakeBroker` and a Noise initiator `NoisePhonePeer`
(`tests/fakes.py`) so no live Centrifugo is required for unit tests. Coverage:
config, base64 codec, Noise sessions/envelope, pairing URI, socket server,
CTAP2 parse/encode, relay (handshake, token, correlation, tamper, TOFU pin,
timeout), and an end-to-end `getAssertion` loop.

For integration testing with a live Centrifugo, see Phase 9 of `DAEMON_PLAN.md`.
The harness (`tests/harness/`) provides `MockBrowser` and `MockPhone` to
validate the real relay path without a browser or Android device. See
[`tests/harness/README.md`](tests/harness/README.md) for scenarios, CLI usage,
and how to extend.

### Integration harness scripts

Two standalone scripts test the full Centrifugo relay path against a live
broker. Both require a running Centrifugo instance and read the same env vars
as the daemon (`FIDO2_RELAY_URL`, `FIDO2_CHANNEL_ID`, `FIDO2_STATIC_KEY_PATH`,
`FIDO2_RELAY_TOKEN`).

**Test the Android app** (acts as daemon, publishes requests):
```bash
FIDO2_RELAY_URL=wss://gary.andreparames.com:8000/connection/websocket \
FIDO2_CHANNEL_ID=<32-hex-from-pairing> \
mock-daemon get-assertion
```

**Test the daemon** (acts as phone, opens the Noise handshake and responds):
```bash
FIDO2_RELAY_URL=wss://gary.andreparames.com:8000/connection/websocket \
FIDO2_CHANNEL_ID=<32-hex-from-pairing> \
mock-phone
```

## Browser WebAuthn (virtual FIDO2 HID device)

By default the daemon only exposes the Unix socket (for libfido2/CLI clients).
Pass `--uhid` (or set `FIDO2_UHID_ENABLED=1`) to also present a **virtual FIDO2
security key** via `/dev/uhid`, so any WebAuthn-capable browser
(`https://webauthn.io`, etc.) sees a normal security key and authenticates
through the phone. The virtual HID transport terminates in the same CTAP2 →
PROTOCOL.md handler core as the socket.

```bash
FIDO2_UHID_ENABLED=1 fido-daemon
# or: fido-daemon --uhid
ls /dev/hidraw*          # a new node appears
udevadm info /dev/hidrawN | grep -i fido
```

**Permissions:** `/dev/uhid` is root-only by default. Install the udev rule so
your user can open it:

```bash
sudo install -m 0644 systemd/70-fido2-bridge-uhid.rules /etc/udev/rules.d/
sudo udevadm control --reload && sudo udevadm trigger
sudo usermod -aG uhid $USER    # then log out/in
```

If your user is not in the `uhid` group, run the daemon under a systemd service
that has access (the unit is a user unit; the udev rule makes `/dev/uhid`
group-readable for the `uhid` group).

## Status & next steps

Implemented (M1–M3 of `DAEMON_PLAN.md`): config, Noise transport + envelope
codec, static-key pairing + `derive_channel_id`, Unix socket server, CTAP2
interception + JSON schema, Centrifugo relay with Noise handshake, TOFU phone
pinning, token auth + timeout, and CLI end-to-end wiring. M4 adds the `--uhid`
virtual FIDO2 HID frontend (`ctaphid.py`, `uhid_device.py`) for browser
WebAuthn.

Not yet done:

- **Phase 8** (`DAEMON_PLAN.md` §8): systemd install + Definition-of-Done
  verification against a live Centrifugo and `https://webauthn.io`.
- **Cross-peer lock-step:** the Android side (`android-fido-client`) must speak
  the same Noise handshake, envelope, and pairing URI; `PROTOCOL.md` remains the
  source of truth for any change.
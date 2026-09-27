# fido-daemon

Linux daemon that bridges local CTAP2/WebAuthn requests to a remote Android
phone. It exposes a Unix domain socket that looks like a local FIDO2
authenticator, seals each request with AES-256-GCM, forwards it to the phone
through an untrusted **Centrifugo** relay, and writes the signed CTAP2 response
back to the local client.

The phone holds the private keys; the daemon never sees plaintext on the wire,
and neither does the relay. See `agents.md` (repo root) for the security
requirements and `PROTOCOL.md` (repo root) for the authoritative wire formats.

## Architecture

```
 Local client ──(Unix socket)──> daemon ──(Centrifugo, AES-GCM)──> phone
 <─────────────────────────────────────────────────────────────────┘
```

1. A local app/browser (or a virtual-HID shim) sends a CTAP2 request over the
   Unix socket at `/run/user/<UID>/fido2-bridge.sock` (`0600`).
2. The daemon decodes the request (`ctap2.py`) into the `PROTOCOL.md` §4/§5
   plaintext message, seals it (`crypto.py`), and publishes it to the Centrifugo
   channel `fidobridge.<channel_id>` (`relay.py`).
3. The phone (subscribed to the same channel) opens it, prompts for biometrics,
   signs in the TEE, and publishes a sealed `assertionResult` /
   `makeCredentialResult` with the same correlation `id`.
4. The daemon matches the response by `id`, opens it, and writes the CTAP2 reply
   (status byte + CBOR) back to the socket.

The relay is untrusted: every payload is AES-256-GCM sealed before publish, a
GCM tag failure is logged as a security alert and the message is dropped.

## Layout

```
src/fido_daemon/
  cli.py           entry point (python -m fido_daemon.cli / fido-daemon)
                   full request/response wiring + lifecycle management
  config.py        env-driven configuration (Config.from_env)
  protocol.py      frozen versioned constants (version, message types,
                   CTAP2 status codes, COSE alg)
  crypto.py        AES-256-GCM seal/open + WireMessage JSON codec
                   (standard unpadded base64; channel_id = 32 lowercase hex)
  ctap2.py         CBOR request decoding + CTAP2 response encoding
  pairing.py       PairingGenerator + derive_channel_id (SHA-256 digest)
  pairing_uri.py   fidobridge:// pairing URI formatter/parser
  relay.py         RelayClient over centrifuge.Client (token auth, id
                   correlation, timeout, tamper alert)
  socket_server.py Unix socket server (0600, clean unlink on exit)
  harness.py       integration test harness (mocked browser + phone, live Centrifugo)
src/harness_common/
  config.py        shared HarnessConfig for integration harness scripts
src/mock_daemon/
  __init__.py      MockDaemon: publish synthetic CTAP2 requests, validate responses
  __main__.py      entry point (python -m mock_daemon)
src/mock_phone/
  __init__.py      MockPhone: subscribe to Centrifugo, respond to daemon requests
  __main__.py      entry point (python -m mock_phone)
systemd/           user service unit (fido-daemon.service)
tests/             pytest suite (fakes/ has an in-memory broker + stub phone)
  harness/         MockBrowser + MockPhone for integration testing (Phase 9)
```

## Setup

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -e ".[dev]"
pytest          # 77 tests
```

Dependencies: `fido2`, `cryptography`, `centrifuge-python` (dev: `pytest`,
`pytest-asyncio`).

## Configuration

All values are read from the environment by `Config.from_env()`.

| Variable                | Default                                        | Purpose |
|-------------------------|------------------------------------------------|---------|
| `FIDO2_REMOTE_SOCKET`   | `/run/user/<UID>/fido2-bridge.sock`            | Unix socket path |
| `FIDO2_RELAY_URL`       | `wss://relay.example.invalid/connection/websocket` | Centrifugo WebSocket endpoint |
| `FIDO2_CHANNEL_ID`      | `""`                                           | **derived** channel id (32 lowercase hex) |
| `FIDO2_SESSION_KEY_B64` | `""` (required to run)                         | 32-byte AES key, standard base64 |
| `FIDO2_RELAY_TOKEN`     | `""` (anonymous)                               | Centrifugo connection JWT |
| `FIDO2_REQUEST_TIMEOUT` | `30.0`                                         | Relay round-trip timeout (seconds) |

`FIDO2_RELAY_TOKEN` is attached on startup and on every reconnect (via the
SDK's `get_token` callback) and is never logged or leaked into the URL. Leave
it empty for an unprotected/anonymous Centrifugo during local development.

## Usage

Generate a pairing URI for the phone (printed to stdout for QR generation):

```bash
fido-daemon pair
# fidobridge://pair?channel=<32hex>&key=<base64url>
```

Run the daemon:

```bash
export FIDO2_CHANNEL_ID=<derived-32hex-id>
export FIDO2_SESSION_KEY_B64=<standard-base64-32-byte-key>
export FIDO2_RELAY_URL=wss://relay.example.com/connection/websocket
export FIDO2_RELAY_TOKEN=<jwt>          # omit for anonymous
python -m fido_daemon.cli
```

Or install the user service (supply secrets via a `0600` `EnvironmentFile`,
not baked into the unit):

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

The suite uses an in-memory `FakeBroker` and a `StubPhone` (`tests/fakes.py`)
so no live Centrifugo is required for unit tests. Coverage: config, crypto/codec,
pairing, socket server, CTAP2 parse/encode, relay (token, correlation, tamper,
timeout), and an end-to-end `getAssertion` loop.

For integration testing with a live Centrifugo, see Phase 9 of `DAEMON_PLAN.md`.
The harness (`tests/harness/`) provides `MockBrowser` and `MockPhone` to
validate the real relay path without a browser or Android device.

### Integration harness scripts

Two standalone scripts test the full Centrifugo relay path against a live
broker.  Both require a running Centrifugo instance and read the same env
vars as the daemon (`FIDO2_RELAY_URL`, `FIDO2_SESSION_KEY_B64`,
`FIDO2_CHANNEL_ID`, `FIDO2_RELAY_TOKEN`).

**Test the Android app** (acts as daemon, publishes requests):
```bash
FIDO2_RELAY_URL=wss://gary.andreparames.com:8000/connection/websocket \
FIDO2_SESSION_KEY_B64=<same-as-app> \
FIDO2_CHANNEL_ID=<32-hex-from-pairing> \
mock-daemon get-assertion
```

**Test the daemon** (acts as phone, responds to requests):
```bash
FIDO2_RELAY_URL=wss://gary.andreparames.com:8000/connection/websocket \
FIDO2_SESSION_KEY_B64=<same-as-daemon> \
FIDO2_CHANNEL_ID=<32-hex-from-pairing> \
mock-phone
```

## Status & next steps

Implemented (M1–M3 of `DAEMON_PLAN.md`): config, E2EE crypto + wire codec,
pairing + `derive_channel_id`, Unix socket server, CTAP2 interception + JSON
schema, Centrifugo relay with token auth + timeout, and CLI end-to-end wiring.

Not yet done:

- **Phase 8** (`DAEMON_PLAN.md` §8): systemd install + Definition-of-Done
  verification against a live Centrifugo and `https://webauthn.io`.
- **Phase 9** (`DAEMON_PLAN.md` §9): integration test harness with mocked
  browser/phone to validate the real Centrifugo relay path locally.
- **Pairing ergonomics gap:** `fido-daemon pair` currently prints only the
  pairing URI (raw channel hex + base64url key). To actually run the daemon you
  must separately compute `FIDO2_CHANNEL_ID` (via `derive_channel_id`) and
  `FIDO2_SESSION_KEY_B64` (standard-base64 of the key) from that URI. A helper
  to emit these directly would close the loop.
- **Cross-peer lock-step:** the Android side (`android-fido-client`) must use
  the same Centrifugo channel and token; `PROTOCOL.md` remains the source of
  truth for any change.

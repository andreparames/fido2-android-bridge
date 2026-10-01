# Integration Test Harness

Mocks the browser and Android phone peers so you can exercise the full
daemon → Centrifugo → phone path on a single machine, without a real
browser or Android device.

Unlike the unit tests in `tests/test_e2e.py` (which use an in-memory
`FakeBroker` and never touch the network), the harness tests run the real
`RelayClient` code path — seal → publish → open → correlate — over a
Centrifugo connection. The `FakeBroker` still routes the messages, but
every seal/open/correlation step in `relay.py` executes for real.

## Architecture

```
 MockBrowser ──(Unix socket)──> daemon ──(centrifuge SDK)──> MockPhone
                  ▲                                          │
                  └──── CTAP2 response (status + CBOR) ◄──────┘
```

| Component | Role |
|-----------|------|
| **MockBrowser** | Opens the daemon's Unix socket, writes a synthetic CTAP2 CBOR request (`getAssertion` or `makeCredential`), reads back the CTAP2 status + CBOR response. |
| **MockPhone** | Subscribes to `fidobridge.<channel_id>`, decrypts incoming sealed requests with the session key, calls a responder function, seals the result, and publishes it back. |
| **Daemon** | The real `fido_daemon.cli._run` — not mocked. Parses CTAP2, seals, relays, opens, encodes the reply. |

Both peers share the same `AesGcmCipher` and channel id derived from the
env/config, exactly as they would against a production Centrifugo instance.

## Prerequisites

The tests run against an injected broker (FakeBroker by default). To run
against a real Centrifugo:

1. **Centrifugo server** — self-hosted instance listening on a WebSocket
   endpoint (default `ws://localhost:8000/connection/websocket`).
2. **Session key** — any 32-byte AES-256 key, base64-encoded.
3. **Channel id** — derived from a 32-char lowercase hex channel string
   via `derive_channel_id()` (SHA-256, first 16 bytes, hex-encoded).

No env vars are required when using FakeBroker — the tests generate their
own key and channel.

## Running the tests

```bash
# Default suite (77 unit + e2e tests) — harness tests skipped
.venv/bin/pytest

# Full suite including harness integration tests
FIDO2_HARNESS=1 .venv/bin/pytest

# Just the harness tests
FIDO2_HARNESS=1 .venv/bin/pytest tests/test_harness.py -v
```

The harness tests are gated behind `FIDO2_HARNESS=1` so the default
`pytest` run stays fast and has no network dependencies.

## Test scenarios

| Test | What it verifies |
|------|------------------|
| `test_harness_get_assertion` | Full round-trip: MockBrowser sends `getAssertion`, daemon seals + relays, MockPhone signs, daemon opens + encodes, MockBrowser receives status `0x00` with correct CBOR fields. Asserts `rpId` arrived intact at the phone. |
| `test_harness_make_credential` | Same round-trip for `makeCredential`. Verifies `attestationObject` round-trips through seal/open. |
| `test_harness_timeout` | MockPhone's responder returns `None` (never publishes a reply). Daemon's `request_timeout` expires; MockBrowser receives a non-zero CTAP2 status byte. |
| `test_harness_tamper_detected` | MockPhone seals its reply with a **wrong AES key**. Daemon's relay detects the GCM tag failure, logs `SECURITY ALERT`, drops the message, and the pending request times out. MockBrowser receives an error. |

## CLI harness

For a quick smoke test during development:

```bash
# Run all scenarios with a real Centrifugo
FIDO2_RELAY_URL=ws://localhost:8000/connection/websocket \
FIDO2_SESSION_KEY_B64=$(python3 -c "import base64,secrets;print(base64.b64encode(secrets.token_bytes(32)).decode())") \
python -m fido_daemon.harness all

# Specific scenario
python -m fido_daemon.harness get-assertion
python -m fido_daemon.harness make-credential

# Verbose logging
python -m fido_daemon.harness all --verbose
```

The CLI:
- Generates a random session key and channel if `FIDO2_SESSION_KEY_B64`
  and `FIDO2_CHANNEL_ID` are not set.
- Uses a temp-dir socket path (or `FIDO2_REMOTE_SOCKET`).
- Starts the daemon in-process, runs MockBrowser/MockPhone, prints
  pass/fail per scenario, and exits `0` on success / `1` on failure.

## Extending

To add a new scenario:

1. Write a responder function in `tests/test_harness.py`:
   ```python
   def _my_responder(request: dict) -> dict:
       return {
           "version": 1,
           "type": "assertionResult",  # or makeCredentialResult
           "id": request["id"],
           "payload": { ... },
       }
   ```

2. Add a test that constructs `MockPhone(broker, config, _my_responder)`,
   starts the daemon via `_run(config, client_factory=broker.new_client)`,
   and drives `MockBrowser`.

3. If the scenario needs a broker-level fault (tamper, wrong key),
   subclass `MockPhone` and override `_handle`.

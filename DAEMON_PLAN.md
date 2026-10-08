# DAEMON_PLAN.md — Linux FIDO2 Daemon Build Plan (TDD)

This plan builds the `linux-fido-daemon` using a strict **Test-Driven Development** workflow. Every phase follows the Red → Green → Refactor cycle: write a failing test, implement the minimum to pass, then refactor. No phase is considered done until its tests pass.

Source of truth for security requirements: `AGENTS.md`. Mirror of `ANDROID_PLAN.md` for the daemon side of the bridge.

---

## 0. Guiding Rules

- **TDD everywhere:** no production code is written before a failing test defines its behavior.
- **Security-first:** the relay is untrusted; all wire data is AES-256-GCM sealed; private keys never leave the phone; GCM tag failure aborts with a security alert.
- **Toolchain:** Python 3.11+, package layout `src/fido_daemon/`, pytest + pytest-asyncio for tests.
- **Decisions (confirmed):** language = Python (no uv/poetry; plain setuptools + venv); relay = **Centrifugo** (self-hosted realtime broker) over WebSocket via the `centrifuge` Python SDK; relay auth = JWT connection token from env; crypto = `cryptography`; CTAP2 = `fido2`.
- **Verification gates:** each phase ends with `.venv/bin/pytest` green.

---

## 1. Project Scaffolding + Config (M1)

**Objective:** installable package, env-driven `Config`, and a test harness that runs.

> Scaffold (pyproject, `src/fido_daemon/`, systemd unit, smoke tests) is already in place from the initial setup; this phase verifies and completes the harness.

### Steps
1. Verify `pip install -e ".[dev]"` resolves `fido2`, `cryptography`, `websockets`.
2. `Config.from_env()` reads `FIDO2_REMOTE_SOCKET`, `FIDO2_RELAY_URL`, `FIDO2_CHANNEL_ID`, `FIDO2_SESSION_KEY_B64`, `FIDO2_RELAY_TOKEN`, `FIDO2_REQUEST_TIMEOUT`.
3. Wire `python -m fido_daemon.cli` and the `fido-daemon` console script to the same entry point.

### TDD Gate
- **Test** (`test_config.py`):
  1. Default `socket_path` is `/run/user/<uid>/fido2-bridge.sock` (uid from `os.getuid()`).
  2. `FIDO2_REMOTE_SOCKET` overrides the socket path.
  3. Missing `FIDO2_SESSION_KEY_B64` makes `main()` exit with code 2 and log an error.
  4. Invalid `FIDO2_REQUEST_TIMEOUT` (non-numeric) → `ValueError`.
  5. Absent `FIDO2_RELAY_TOKEN` → `relay_token == ""` (anonymous/unprotected relay; local dev keeps working); present → read verbatim.
- Existing `test_smoke.py` stays green (cipher/wire round-trips).

**Done when:** `.venv/bin/pytest` is green and `fido-daemon --help` exits 0.

---

## 2. E2EE Crypto + Wire Codec (M1)

**Objective:** `AesGcmCipher` and the wire envelope from `PROTOCOL.md` §3.

> Wire `channel_id` is the **derived** digest `lowercase hex(SHA-256(channel_hex_utf8)[:16])` (32 lowercase hex chars), per `PROTOCOL.md` §3.2. The raw 128-bit id never appears on the wire. The `derive_channel_id()` helper lands in Phase 3 (pairing); Phase 2 enforces the 32-hex shape and the exact key set.

### TDD (Red → Green → Refactor)
- **Tests first** (`test_crypto.py`, extends `test_smoke.py`):
  1. `SecretKey` with a non-32-byte material raises `ValueError`.
  2. `seal` emits a 12-byte nonce and a 16-byte tag (partially covered by smoke tests).
  3. Round-trip: `open(seal(p)) == p`.
  4. Two seals of the same plaintext differ (fresh nonce per message).
  5. Tampered ciphertext OR tag → `TagMismatchError`.
  6. `open` with the wrong key raises `TagMismatchError` (GCM cannot distinguish a wrong key from tampering; `PROTOCOL.md` §3.2 treats any tag failure as a security alert).
- **Tests first** (`test_codec.py`):
  1. `message_to_json`/`message_from_json` round-trip preserves all fields.
  2. Missing or malformed `nonce`/`tag`/`ciphertext` (invalid Base64) → `ValueError`, no silent nulls; unknown/duplicate JSON keys rejected.
  3. Encoded JSON keys are exactly `{channel_id, nonce, ciphertext, tag}`.
  4. `channel_id` must match `^[0-9a-f]{32}$`; otherwise `ValueError`.
- **Implement:** `crypto.py` (`AesGcmCipher`, `SecretKey`, `TagMismatchError`, `message_to_json`/`message_from_json`).
- **Refactor:** standard (unpadded) Base64 in one wire helper — `PROTOCOL.md` §3.2 requires base64url *only* in the pairing URI (Phase 3), so keep wire Base64 standard and add a separate `b64url` helper in Phase 3; freeze wire keys and the channel-id pattern as constants.

**Done when:** all cipher/codec tests pass.

---

## 3. Pairing + Session Key (M1)

**Objective:** generate the pairing secret and emit the `fidobridge://` QR/terminal string for the Android app to scan.

### TDD
- **Tests first** (`test_pairing.py`):
  1. `PairingGenerator.generate()` produces a 32-byte key and a 16-byte (128-bit) channel id.
  2. Key and channel are cryptographically random (two calls differ).
  3. `format_pairing_uri()` yields `fidobridge://pair?channel=<32_lowercase_hex>&key=<BASE64URL_32BYTE>` (per `PROTOCOL.md` §2.1: channel is hex, key is base64url, unpadded).
  4. The emitted key decodes (base64url) to exactly 32 bytes; the channel is exactly 32 lowercase hex chars.
  5. `derive_channel_id()` yields `lowercase hex(SHA-256(channel_hex_utf8)[:16])` (32 lowercase hex) — pinned per `PROTOCOL.md` §3.2.
  6. `parse_pairing_uri()` rejects malformed URIs (missing/duplicate params, non-hex channel, wrong key length, version mismatch) — round-trip guard for the Android-side parser.
- **Implement:**
  - `PairingGenerator` (secrets.token_bytes) + `pairing_uri.py` formatter/parser.
  - `derive_channel_id()` (SHA-256 over the 32-char lowercase hex channel string; first 16 bytes, hex-encoded).
  - CLI subcommand `fido-daemon pair` printing the URI to stdout for QR generation.
- **Refactor:** single constant for the `fidobridge://` scheme shared by both endpoints; a distinct `b64url` helper (no padding) separate from the wire's standard-base64 helper.

**Done when:** pairing tests pass.

---

## 4. Unix Socket Server (M2)

**Objective:** serve local CTAP2/WebAuthn requests on `/run/user/<UID>/fido2-bridge.sock` with `0600` perms, clean unlink on exit.

### TDD
- **Tests first** (`test_socket_server.py`, async):
  1. `start()` creates the socket file in the runtime dir with mode `0600`.
  2. A client connecting and writing bytes gets a response echoed back (using a stub handler).
  3. Handler exceptions close the connection without crashing the server.
  4. `close()` unlinks the socket file.
- **Implement:** `SocketServer` in `socket_server.py` using `asyncio.start_unix_server` + `os.chmod(path, 0o600)`.

**Done when:** socket tests pass (isolated in a temp runtime dir).

---

## 5. CTAP2 Interception + JSON Schema (M2)

**Objective:** translate raw `authenticatorGetAssertion` / `authenticatorMakeCredential` CBOR into the daemon JSON schema.

### TDD
- **Tests first** (`test_ctap2.py`, using `fido2.ctap2`):
  1. `authenticatorGetAssertion` request → JSON with `clientDataHash`, `rpId`, `allowCredentials`, `option`.
  2. `authenticatorMakeCredential` request → JSON with `clientDataHash`, `rpId`, `user`, `pubKeyCredParams`, `excludeCredentials`.
  3. Malformed/unknown command → `CTAP2_ERR_INVALID_COMMAND` (0x01) back to the socket client.
  4. Unknown credential / mismatched rpId → `CTAP2_ERR_OPERATION_DENIED` (0x27) after relay round-trip.
- **Implement:**
  - `ctap2.py`: decode CBOR (via `fido2` / `cbor2`) → frozen JSON dataclasses.
  - `protocol.py`: versioned JSON constants for the daemon↔phone schema.
- **Refactor:** freeze schemas as versioned constants mirrored by `ANDROID_PLAN.md` §9.

**Done when:** CTAP2 parse + schema tests pass.

---

## 6. Centrifugo Relay + E2EE + Token Auth + Timeout (M3)

**Objective:** carry sealed payloads to the phone over Centrifugo, authenticate
with a JWT connection token from env, and apply the 30s request timeout with
fail-safe alert behavior.

> The relay is a self-hosted **Centrifugo** broker. Both peers subscribe and
> publish to the channel `fidobridge:<channel_id>`; the PROTOCOL.md §3
> `WireMessage` envelope is the publication payload (the relay never sees
> plaintext). The token is a Centrifugo connection JWT supplied via
> `FIDO2_RELAY_TOKEN`; it is attached on startup and on every reconnect, and is
> never logged or leaked into process args/output. Absent token → anonymous
> connection (unprotected server) so local development still works.

### TDD
- **Tests first** (`test_relay_token.py`):
  1. `RelayClient` is built with the token from `Config.relay_token`.
  2. Token present → the underlying `centrifuge.Client` is configured with it;
     absent (empty) → configured with no token (anonymous).
  3. The token never appears in logs, exceptions, or the connection URL.
  4. After a reconnect the same token is re-attached (client reuses the
     configured token).
- **Tests first** (`test_relay.py`, against a fake `centrifuge.Client` /
  injected transport — no live broker in CI):
  1. Outbound `send(p)` publishes a valid `WireMessage` carrying the current
     channel id to `fidobridge:<channel_id>` (proves encrypt-before-publish).
  2. An inbound publication is opened and the plaintext is returned.
  3. `receive()` raises `TagMismatchError` on a tampered publication; connection
     is flagged and the message dropped (security alert log).
  4. Request exceeds `FIDO2_REQUEST_TIMEOUT` (default 30s, injectable clock) →
     timeout error returned to the socket client.
- **Implement:** `RelayClient` wrapping `centrifuge.Client` (connect with token,
  subscribe to `fidobridge:<channel_id>`, publish the sealed envelope, open
  inbound publications) with fake clock injection for deterministic timeout
  tests. Never log the token.

**Done when:** token + relay + timeout tests pass.

---

## 7. CLI + End-to-End Wiring (M3)

**Objective:** the full loop — socket request → CTAP2 parse → seal → relay → open → response → socket reply.

### TDD
- **Tests first** (`test_e2e.py`):
  1. Integration: run `SocketServer` + `RelayClient` against a stub "phone" peer that subscribes to `fidobridge:<channel_id>`, signs a canned assertion, and publishes the sealed `assertionResult`; a socket client receives the correct reply (matched back to the pending request by its `id`).
  2. `cli.main()` starts, serves one request, and shuts down cleanly on `KeyboardInterrupt`.
  3. `--socket` flag overrides the config value.
- **Implement:** `cli.py` wiring and lifecycle management (connect + subscribe to the relay channel before binding the socket; teardown order: socket unlink → unsubscribe → relay close).

**Done when:** E2E loop test passes.

---

## 8. systemd + Definition of Done Verification (M3)

> **STATUS: DONE** — `systemd/fido-daemon.service` (user unit) ships with the
> deb/rpm packages; the socket is created at
> `/run/user/<UID>/fido2-bridge.sock` with `0600` and unlinked on clean shutdown
> (unit-tested in `test_socket_server.py`). The daemon↔relay path is validated
> end-to-end by the Phase 9 integration harness and the emulator E2E harness.

**Objective:** satisfy `AGENTS.md` §6 for the daemon side.

> Secrets (`FIDO2_SESSION_KEY_B64`, `FIDO2_RELAY_TOKEN`) are supplied via a
> `0600` `EnvironmentFile=` (or `systemd --user` environment), never baked into
> the unit file or process args.

### Verification checklist
1. `systemd/fido-daemon.service` installed at `~/.config/systemd/user/`; `systemctl --user enable --now fido-daemon`.
2. Socket exists at `/run/user/<UID>/fido2-bridge.sock` with `0600` perms (`ls -l`).
3. `systemctl --user stop fido-daemon` unlinks the socket (no stale file).
4. Manual E2E with a stub phone + `https://webauthn.io` per `AGENTS.md` §6.3.

### Final commands
```bash
.venv/bin/pytest
.venv/bin/python -m fido_daemon.cli --help
systemctl --user status fido-daemon
ls -l /run/user/$UID/fido2-bridge.sock
```

---

## 9. Integration Test Harness — Mocked Browser, Live Centrifugo (M3)

> **STATUS: DONE** — `mock-daemon` + `mock-phone` extend `RelayClient`; both
> `get-assertion` and `make-credential` scenarios pass end-to-end through a
> live Centrifugo broker (see `linux-fido-daemon/README.md` "Integration
> harness").

**Objective:** validate the full daemon → Centrifugo → Android phone path on a
single machine by replacing the browser/client side with a mock that generates
synthetic CTAP2 requests and consumes the real daemon responses.

> This is **not** the unit-test FakeBroker from Phase 6 — those tests never
> touch Centrifugo. Phase 9 connects to a real (local) Centrifugo instance and
> exercises the daemon's relay layer end-to-end, but fakes the CTAP2 peer so
> the harness can be driven programmatically without a browser or a real phone.

### Architecture

```
 MockBrowser ──(Unix socket)──> daemon ──(Centrifugo, AES-GCM)──> MockPhone
                  ▲                                           │
                  └──── response (CTAP2 status + CBOR) ◄──────┘
```

- **MockBrowser:** opens the Unix socket, writes a synthetic `authenticatorGetAssertion`
  or `authenticatorMakeCredential` CBOR payload, reads the CTAP2 response.
- **MockPhone:** subscribes to `fidobridge:<channel_id>`, opens the sealed request,
  signs a canned assertion/credential, seals the result, and publishes it back.
- Both MockBrowser and MockPhone run in the same Python process as the test
  harness; the daemon under test is the real `fido_daemon.cli` started in a
  subprocess or as a task.

### Steps

1. **`tests/harness/__init__.py`** — exports `MockBrowser`, `MockPhone`, `HarnessConfig`.
2. **`MockPhone`** — thin wrapper around the existing `StubPhone` from
   `tests/fakes.py`, but connecting to a real Centrifugo broker over WebSocket.
   It derives `channel_id` and AES key from the same `FIDO2_CHANNEL_ID` /
   `FIDO2_SESSION_KEY_B64` env vars the daemon uses.
3. **`MockBrowser`** — opens the Unix socket, encodes a canned CTAP2 CBOR
   request (reusing `ctap2.py` helpers), writes the one-byte command + CBOR,
   and reads back the status + CBOR response.
4. **`tests/test_harness.py`** (TDD):
   1. `test_harness_get_assertion` — MockPhone subscribes, MockBrowser sends a
      `getAssertion` request, daemon relays it, MockPhone publishes the sealed
      response, MockBrowser receives the correct CTAP2 status + signed
      authenticatorData.
   2. `test_harness_make_credential` — same flow for `makeCredential`.
   3. `test_harness_timeout` — MockPhone intentionally does not respond; daemon
      returns `CTAP2_ERR_TIMEOUT` (0x3B) to MockBrowser after
      `FIDO2_REQUEST_TIMEOUT` seconds (shortened for test speed).
   4. `test_harness_tamper_detected` — MockPhone publishes a sealed payload
      with a corrupted ciphertext; daemon logs a security alert and returns an
      error to MockBrowser; the test asserts the alert was emitted.
5. **`harness.py` CLI** — `python -m fido_daemon.harness` starts the daemon in
   a subprocess, waits for the socket, then runs the selected mock scenario
   (`get-assertion`, `make-credential`, `all`). Returns 0 on success, non-zero
   on failure. Intended for quick smoke tests during development:

   ```bash
   FIDO2_RELAY_URL=wss://gary.andreparames.com:8000/connection/websocket \
   FIDO2_SESSION_KEY_B64=$(python -c "import base64,secrets;print(base64.b64encode(secrets.token_bytes(32)).decode())") \
   python -m fido_daemon.harness all
   ```

6. **Integration into `pytest`** — the harness tests are marked
   `@pytest.mark.integration` (skipped in CI unless `FIDO2_HARNESS=1` is set)
   so the default `pytest` run stays fast. Running them locally requires a live
   Centrifugo instance (or the self-hosted dev server from Phase 8).

### TDD Gate

- All four test cases in `test_harness.py` pass against a local Centrifugo
  instance.
- `python -m fido_daemon.harness all` exits 0.
- Default `pytest` (no `FIDO2_HARNESS=1`) skips the harness tests — no
  regression on the existing 77-test suite.

**Done when:** harness tests pass and the mock round-trip exercises the real
Centrifugo relay path.

---

## 10. UHID CTAPHID Frontend — Browser WebAuthn (M4)

> **STATUS: IN PROGRESS**

**Objective:** present the daemon to local browsers as a virtual FIDO2
security key (`/dev/hidraw*`) via the Linux `/dev/uhid` interface, so
`https://webauthn.io` and any WebAuthn-capable browser authenticate without a
socket shim or a libfido2 client. The uhid transport terminates in the same
CTAP2 → PROTOCOL.md handler core as the Unix socket; `PROTOCOL.md` and the
relay layer are untouched.

### Architecture

```
Browser (WebAuthn)
  │  64-byte CTAPHID reports on /dev/hidraw* (virtual device)
  ▼
uhid_device.py   — /dev/uhid transport + FIDO HID descriptor
  │  reassembled MSG/CBOR payloads + CTAPHID responses
  ▼
ctaphid.py       — CTAPHID framing + channel/transaction state machine (pure)
  │  [command_byte, cbor...]
  ▼
handle_ctap2_command() — shared handler core with the socket path (cli.py)
  │  Message (PROTOCOL.md §5)
  ▼
relay.py (unchanged) ── AES-256-GCM ──> phone
```

### Steps

1. **`ctaphid.py`** — pure CTAPHID packet codec (INIT/CONT), per-channel
   reassembly, CID allocation, and response fragmentation. No I/O; fully
   unit-testable. Freezes the CTAPHID constants (report size 64, broadcast
   CID, command bytes, error codes) next to `protocol.py`'s CTAP2 constants.
2. **`uhid_device.py`** — open `/dev/uhid`, `UHID_CREATE2` with the FIDO HID
   descriptor (usage page `0xF1D0`, usage `0x06`, three 64-byte reports), serve
   `UHID_OUTPUT` / `UHID_GET_REPORT`, respond via `UHID_INPUT2`. The uhid fd is
   pollable, so it is registered on the asyncio loop with `loop.add_reader` —
   no threads.
3. **`cli.py` refactor** — extract the socket handler body into a reusable
   `handle_ctap2_command(command: int, data: bytes) -> bytes` used by both
   transports; start `UhidDevice` alongside `SocketServer` under a `--uhid`
   flag / `FIDO2_UHID_ENABLED` env.
4. **Keepalive** — while a request is in flight to the phone, emit
   `CTAPHID_KEEPALIVE` (status `processing`) every ~1 s on the active channel
   so the browser's ~10 s transaction timeout does not fire during the phone's
   biometric prompt.
5. **Teardown** — on relay failure or uhid close, reply `CTAPHID_ERROR` to all
   active channels and clean the reassembly state.

### TDD

- **Tests first** (`test_ctaphid.py`):
  1. 64-byte INIT packet parse (CID/CMD/BCNT/data) and build round-trip.
  2. Continuation packet parse/build; init-vs-cont discrimination (bit 0x80).
  3. Broadcast-CID `CTAPHID_INIT` allocates a fresh non-broadcast CID and
     echoes the 17-byte nonce in the INIT response (per CTAPHID spec §7).
  4. Multi-packet request reassembly yields the exact `[cmd, cbor...]` frame
     consumed by `decode_request_frame` (cross-check with `ctap2.py`).
  5. Out-of-sequence continuation → `ERR_INVALID_SEQ` (0x04).
  6. Response fragmentation: INIT + CONT chunks reconstruct the full reply;
     responses ≤ 57 bytes fit in one INIT packet.
  7. Unsupported command on an established channel → `ERR_INVALID_CMD` (0x01).
- **Tests first** (`test_uhid_device.py`, fake uhid fd via socketpair):
  1. `UhidDevice.start()` writes a `UHID_CREATE2` event carrying the FIDO HID
     descriptor.
  2. A kernel `UHID_OUTPUT` containing a CTAPHID report is routed into the
     handler core and the `UHID_INPUT2` response carries the 64-byte reply.
  3. `UHID_GET_REPORT` (feature/input) is answered with a zeroed 64-byte
     report.
  4. `UhidDevice.close()` writes `UHID_DESTROY` and cleans up channels.
- **Tests first** (`test_e2e_uhid.py`): a fake browser sends a fragmented
  `authenticatorGetAssertion` through the uhid transport and receives the
  signed response via `StubPhone`. Hermetic — runs in default CI on the
  in-memory `FakeBroker` (no live Centrifugo, no root).

### Implement

- `ctaphid.py`: codec, `Channel`, `CidAllocator`, `FragmentedReader` /
  `FragmentedWriter`.
- `uhid_device.py`: UHID event codec + device loop + FIDO HID descriptor.
- `cli.py`: `handle_ctap2_command()` extraction + `--uhid` flag.
- `config.py`: `FIDO2_UHID_ENABLED` (default `false`), `FIDO2_UHID_NAME`.

### Refactor

- Reuse `decode_request_frame` / `encode_response` unchanged; the uhid path
  feeds exactly the same `[command_byte, cbor...]` bytes as the socket path.
- Keep the Unix socket default-on; uhid is opt-in until a udev rule grants the
  user's group access to `/dev/uhid` (document in README + systemd unit
  comment; `/dev/uhid` is root-only by default).

### Verification

- `.venv/bin/pytest` green (existing 77-test suite plus new ctaphid/uhid
  tests).
- With `FIDO2_UHID_ENABLED=1`: `ls /dev/hidraw*` shows the virtual device,
  `udevadm info` reports usage page `0xF1D0`, and `https://webauthn.io`
  authenticates with the phone (manual, per `AGENTS.md` §6.3).

---

## Milestones Recap

| Milestone | Content | Test gate |
|-----------|---------|-----------|
| M1 | Scaffold + config + cipher/codec + pairing | config/cipher/codec/pairing tests |
| M2 | Socket server + CTAP2 interception + JSON schema | socket + ctap2 tests |
| M3 | Centrifugo relay + token auth + timeout + CLI wiring + DoD + integration harness | relay + token + e2e tests + systemd verification + harness tests |
| M4 | UHID CTAPHID frontend (browser WebAuthn) | ctaphid + uhid-device tests + shared handler-core refactor |

Each milestone is only "done" when its tests pass — no implementation code precedes its failing test.
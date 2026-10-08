# Integration testing — daemon ↔ Centrifugo ↔ Android app

This describes how to run the **live integration harness**: the real Python
daemon, a real Centrifugo relay, and the real Android app code, all talking to
each other over an actual network. It is written from an end-to-end run that
passed both `get-assertion` and `make-credential`.

## What is actually being tested

```
 mock-daemon (Python relay, Noise RESPONDER)
        │  ik1 (phone→daemon)  /  ik2 (daemon→phone)
        ▼
   local Centrifugo v6.9.6  ◄── WebSocket (centrifuge-java / centrifuge-python)
        ▲
        │  data (Noise ciphertext both ways)
 Android IntegrationHarnessTest (JVM, Noise INITIATOR)
   = real CentrifugoTransport + RelayClient + Ctap2Processor
```

- The **phone side** is the app's real code — `CentrifugoTransport`,
  `RelayClient` (Noise initiator), `Ctap2Processor` — executed as a **JVM unit
  test** on the host (`app/src/test/.../harness/IntegrationHarnessTest`). No
  emulator, no APK, no `adb`. Keystore/biometric/UI are faked
  (`FakeCredentialStore`/`FakeKeyGenerator`/`FakeSigner`).
- The **daemon side** is the real Python relay code (`mock_daemon` extends
  `RelayClient`, the Noise responder). It publishes synthetic CTAP2 requests and
  validates the responses.
- **Centrifugo** is a real broker; both peers use real WebSocket clients.

There is a second, now-**automated** flavor — the **emulator E2E** that
installs the real APK and exercises a real `BiometricPrompt` via a virtual
fingerprint, driven by UI automation (`uiautomator2`). See
[`EMULATOR_E2E_TESTING.md`](EMULATOR_E2E_TESTING.md) (orchestrator runbook +
implementation) and `android-fido-client/plans/plan.md` §12. This doc covers the JVM harness that
CI runs.

## Prerequisites

- Daemon installed in a venv:
  ```bash
  cd linux-fido-daemon
  python3 -m venv .venv && .venv/bin/pip install -e ".[dev]"
  ```
- JDK 17+ and an Android SDK (compileSdk 34). Locally `local.properties` sets
  `sdk.dir`; in CI set `ANDROID_HOME`/`ANDROID_SDK_ROOT` instead.
- A **Centrifugo** server (binary or Docker; verified with **v6.9.6**).
- Network access to `jitpack.io` (the Android app depends on `noise-java` from
  JitPack) and the usual Maven/Google repos.

## 1. Start Centrifugo (local, anonymous)

The production relay authenticates with JWTs (`FIDO2_RELAY_TOKEN`); the harness
uses **anonymous** access, which Centrifugo v5+/v6 does **not** allow by
default. Use a config like:

```json
{
  "client": {
    "allow_anonymous_connect_without_token": true,
    "token": { "hmac_secret_key": "local-test-secret" }
  },
  "http_api": { "key": "local-test-api-key" },
  "admin": { "enabled": false },
  "log": { "level": "info" },
  "channel": {
    "without_namespace": {
      "allow_subscribe_for_client": false,
      "allow_publish_for_client": false,
      "allow_publish_for_subscriber": false
    },
    "namespaces": [
      {
        "name": "fidobridge",
        "allow_subscribe_for_client": true,
        "allow_subscribe_for_anonymous": true,
        "allow_publish_for_client": true,
        "allow_publish_for_anonymous": true,
        "allow_publish_for_subscriber": true
      }
    ]
  },
  "http_server": { "address": "127.0.0.1", "port": 9000 }
}
```

```bash
/path/to/centrifugo --config=/path/to/config.json &
# serving websocket, api endpoints on 127.0.0.1:9000
```

## 2. Generate pairing material

One channel + one daemon static key, shared by both sides. The daemon keeps the
private key file; the phone only needs the public key.

```bash
cd linux-fido-daemon
.venv/bin/python - <<'EOF'
import secrets
from pathlib import Path
from fido_daemon.noise import StaticKeyStore
from fido_daemon.pairing import derive_channel_id
from fido_daemon.crypto import b64encode

channel_hex = secrets.token_hex(16)          # 32 lowercase hex
key_path = Path("/tmp/daemon_static.pem")
priv = StaticKeyStore.generate()
StaticKeyStore.save(key_path, priv)          # 0600
print("CHANNEL_HEX        =", channel_hex)
print("CHANNEL_ID         =", derive_channel_id(channel_hex))
print("DAEMON_STATIC_KEY  =", key_path)
print("DAEMON_PUBLIC_B64  =", b64encode(StaticKeyStore.public_key(priv)))
EOF
```

`DAEMON_PUBLIC_B64` is **standard** (not URL) base64 — it must match what the
Android `HarnessConfig` reads with `Base64.decodeStandard`.

## 3. Start the daemon peer FIRST (Noise responder)

The daemon is the Noise **responder**: its requests block until the phone
initiates the handshake. Start it first and give it a **long** timeout — the
Android gradle test takes ~1 minute to compile/start, so the default 10 s
request timeout is far too short.

```bash
cd linux-fido-daemon
FIDO2_CHANNEL_ID=<CHANNEL_HEX from step 2> \
FIDO2_STATIC_KEY_PATH=/tmp/daemon_static.pem \
FIDO2_RELAY_URL=ws://localhost:9000/connection/websocket \
FIDO2_REQUEST_TIMEOUT=180 \
  .venv/bin/python -m mock_daemon all --timeout 180 > /tmp/mock_daemon.log 2>&1 &
```

It logs `connected to relay channel fidobridge:<channel_id>` then waits for the
phone's `ik1`.

## 4. Run the Android harness test (Noise initiator)

The phone initiates the handshake, so it must come second:

```bash
cd android-fido-client
FIDO2_HARNESS=1 \
FIDO2_CHANNEL_ID=<CHANNEL_HEX from step 2> \
FIDO2_DAEMON_PUBLIC_B64=<DAEMON_PUBLIC_B64 from step 2> \
FIDO2_RELAY_URL=ws://localhost:9000/connection/websocket \
FIDO2_HARNESS_TIMEOUT=200 \
  ./gradlew testDebugUnitTest --tests 'com.fidobridge.client.harness.IntegrationHarnessTest'
```

## 5. Verify

- The gradle test finishes **BUILD SUCCESSFUL**.
- `mock_daemon` log shows the full round trip and exits 0:
  ```
  Noise handshake established (remote static=…)
  get-assertion: OK
  make-credential: OK
  ```
- No `SECURITY ALERT` lines in the daemon log.

## Troubleshooting (from a real run)

| Symptom | Cause / fix |
|---|---|
| Clients disconnect with code **3501** `bad request` | Missing `client.allow_anonymous_connect_without_token`; the harness connects anonymously. Add it (or use a JWT via `FIDO2_RELAY_TOKEN`). |
| Subscribe fails with code **103** `permission denied` | The topic must live in the `fidobridge` namespace — use `fidobridge:<channel_id>` (colon, PROTOCOL.md §3.3); non-namespaced topics are refused. |
| Android test fails `relay did not connect within 15s` | The app's `CentrifugoTransport` no longer wires a `tokenGetter` when no token is set (empty-token getter aborted anonymous connects); verify Centrifugo is reachable at the URL. |
| `mock_daemon` keeps timing out | Its request waits for the handshake; raise `--timeout`/`FIDO2_REQUEST_TIMEOUT` (≥120) and start it **before** the Android test. |
| `SECURITY ALERT: Noise transport authentication failed` on the daemon | The daemon's own relay echo must be skipped **before** decrypt (Noise sender/receiver keys differ); already fixed in `relay.py`. |

## Where the pieces live

- Android harness (phone side): `android-fido-client/app/src/test/java/com/fidobridge/client/harness/`
  (`IntegrationHarnessTest.kt`, `HarnessConfig.kt`, `Fakes.kt`).
- Daemon peer (daemon side): `linux-fido-daemon/src/mock_daemon/__init__.py`
  (`MockDaemon extends RelayClient`, real responder).
- Daemon-side unit harness (in-memory broker, no Centrifugo):
  `linux-fido-daemon/tests/harness/` + `tests/harness/README.md`; run with
  `FIDO2_HARNESS=1 .venv/bin/pytest tests/test_harness.py`.
- Emulator E2E (real APK + real `BiometricPrompt`, automated via `uiautomator2`):
  [`EMULATOR_E2E_TESTING.md`](EMULATOR_E2E_TESTING.md) + the `emulator_harness`
  Python module (`linux-fido-daemon/src/emulator_harness/`).
- CI: the GitHub Actions workflow runs the 4 static/unit jobs on PRs, and on
  `main` adds the package build then this integration job.

## Cleanup

```bash
# stop mock_daemon (it exits 0 after OK anyway)
# stop Centrifugo
# remove /tmp/daemon_static.pem and /tmp/mock_daemon.log
```
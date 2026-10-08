# Emulator E2E — Real Android App vs. mock-daemon, driven by UI automation

**STATUS: IMPLEMENTED + VERIFIED** (2026-10-02) — `emulator_harness` runs
against the AVD `fido2` + local Centrifugo; `python -m emulator_harness all`
passes make-credential, clear-requests, get-assertion, a 5-request `multi`
flow (4 accepted + 1 rejected), and reset-then-repair — all with the real
`BiometricPrompt` satisfied/dismissed via the emulator, real UI assertions,
and per-step screenshots (captured via the emulator console so secure
surfaces are not black). Bring-up notes that changed the plan: see §A.4/A.6.

This document closes the automation gap left by `ANDROID_PLAN.md`
§12: the manual emulator E2E proved the full loop works once, but had no
driver, no assertions, and no repeatable harness. It specifies the
**automated** emulator E2E where the real debug APK runs on a headless
emulator against a live local Centrifugo + the existing `mock_daemon`, driven
end-to-end by **`uiautomator2`** (Python) over adb.

Companion docs: `INTEGRATION_TESTING.md` (the host-JVM harness this replaces
for UI coverage), `UI_TESTER_GUIDE.md` (screens, elements, adb recipes —
authoritative for assertions), `EMULATOR_ENV.md` (emulator/SDK install state),
`PROTOCOL.md`, `AGENTS.md`.

**Priority:** A (orchestrator) and C (docs) are the deliverable. B (CI job)
is a future, optional extra.

---

## What is actually being tested

```
 mock-daemon (Python relay, Noise RESPONDER)
        │  ik1 (phone→daemon)  /  ik2 (daemon→phone)
        ▼
   local Centrifugo            ◄── WebSocket (10.0.2.2:9000)
        ▲
        │  data (Noise ciphertext both ways)
        ▼
 real debug APK on AVD `fido2`  (real CentrifugoTransport + RelayClient
   initiator + Ctap2Processor + real BiometricPrompt in the emulator)
   driven + asserted by the Python orchestrator via uiautomator2
```

- The **phone side** is the **real app**, not fakes: real `RelayClient` (Noise
  initiator), real `Ctap2Processor`, real `BiometricPrompt` satisfied by the
  emulated fingerprint (`adb emu finger touch 1` / PIN fallback), real
  KeyStore signer (StrongBox falls back to TEE/software on the emulator).
  The whole UI is exercised: pairing screen → deep-link pairing → Home →
  request rows → biometric approval.
- The **daemon side** stays the existing `mock_daemon` (`RelayClient` Noise
  responder) — unchanged; it publishes synthetic CTAP2 requests and validates
  the responses (`get-assertion: OK` / `make-credential: OK`).
- **Centrifugo** is the real local broker; both peers use real WebSocket
  clients. The relay is configured for anonymous access, as in
  `INTEGRATION_TESTING.md`.

The existing host-JVM harness (`IntegrationHarnessTest`) keeps running in CI:
it is fast and exercises the same relay/crypto code. This emulator harness
adds what the JVM harness cannot prove: the real `BiometricPrompt` flow, the
real signer, the real UI (pairing, request history, badges, notifications).

---

## Design decisions

1. **Relay URL for the emulator.** `BuildConfig.RELAY_URL` is baked at build
   time from `FIDO2_RELAY_URL` (`app/build.gradle.kts`). The emulator reaches
   the host Centrifugo via `10.0.2.2`, which `network_security_config.xml`
   already allows for cleartext `ws://`. → The harness builds a debug APK with
   `FIDO2_RELAY_URL=ws://10.0.2.2:9000/connection/websocket`.
2. **Shared pairing material.** One `channel_hex` + one daemon static key feed
   both peers: `mock_daemon` via `FIDO2_CHANNEL_ID` + `FIDO2_STATIC_KEY_PATH`;
   the app via a deep-link pairing URI built with
   `fido_daemon.pairing_uri.format_pairing_uri(Pairing(...))`, whose `pubkey`
   is **base64url** (per `PROTOCOL.md` §2.1) — distinct from the standard-b64
   `DAEMON_PUBLIC_B64` the JVM harness uses.
3. **Ordering (Noise IK).** `mock_daemon` (responder) starts and subscribes
   **first**; the app (initiator) pairs via deep link, then a
   background/foreground cycle triggers `onResume` → `FidoBridgeService` →
   ik1 handshake (Phase 12 workaround for the first-pairing service gap).
   Give `mock_daemon` a long `--timeout` and keep its retries (late-subscribe
   tolerance).
4. **Approval.** Real `BiometricPrompt` satisfied by `adb emu finger touch 1`
   (PIN `1234` fallback if enrollment is unconfirmed). Set `locksettings
   set-pin 1234`, `svc power stayon true`, and keep the app **foregrounded**
   so the prompt (not the heads-up notification) is shown.
5. **UI automation with `uiautomator2`.** Drive and assert the UI through the
   `uiautomator2` Python client (a maintained wrapper around adb + an
   on-device accessibility test server) instead of hand-rolling
   `uiautomator dump` + `input tap`. Elements are matched by text /
   content-desc exactly as catalogued in `UI_TESTER_GUIDE.md` §4 — never
   hardcoded coordinates, never exact colors. Raw adb is kept only where
   `uiautomator2` does not reach: boot wait, `adb install`, and the
   emulator-console fingerprint command (`adb emu finger touch 1`).

---

## A. Python orchestrator (priority)

New module, mirroring `mock_daemon`'s CLI style and reusing the daemon venv.

### A.0 Dependency

Add `uiautomator2` to the **dev** extras in `linux-fido-daemon/pyproject.toml`:

```toml
dev = [
    "pytest>=8",
    "pytest-asyncio>=0.23",
    "ruff>=0.9",
    "uiautomator2>=3",
]
```

It is a dev-only dependency (never shipped with the daemon). On first
`u2.connect()` it installs two artifacts on the device (cached afterwards):
the `atx-agent` binary (`/data/local/tmp/atx-agent`, an on-device HTTP broker)
and the `com.github.uiautomator` accessibility test server — the "agent"
mentioned above. This is a one-time, per-device cost and is fine on a
disposable emulator.

### A.1 Location & layout

```
linux-fido-daemon/src/emulator_harness/
  __init__.py
  __main__.py      entry point (python -m emulator_harness)
  device.py        thin uiautomator2 wrapper + raw-adb leftovers
  material.py      pairing material + URI generation (fido_daemon.pairing_uri)
  main.py          CLI + scenario orchestration (mock_daemon subprocess)
```

Console entry (optional): `emulator-e2e = "emulator_harness:main"` in
`pyproject.toml`.

### A.2 CLI

```
python -m emulator_harness <scenario> [--apk PATH] [--emulator avd-name]
                             [--relay ws://10.0.2.2:9000/connection/websocket]
                             [--host-relay ws://localhost:9000/...]
                             [--timeout 300] [--count N] [--reject-indices 3]
                             [--skip-clear] [--skip-export] [--skip-reset]
                             [--keep-running]
  scenario: get-assertion | make-credential | multi | export | reset | all
```

`multi` sends `--count` (default 5) get-assertions and rejects the 1-based
indices in `--reject-indices` (default `3`); `all` chains make-credential →
clear → get-assertion → multi → export → reset. `export` drives the Home
"Export logs" button, accepts the Storage Access Framework save sheet, pulls
the produced zip, and validates its JSONL contents (`--skip-export` omits it
from `all`).

Env overrides: `FIDO2_RELAY_URL`, `FIDO2_STATIC_KEY_PATH`, `ANDROID_HOME`
(reuse the daemon's `HarnessConfig` conventions).

### A.3 Components

- **`material.py`** — generate `channel_hex = secrets.token_hex(16)`, write a
  daemon static key (0600), and build the pairing URI:
  `format_pairing_uri(Pairing(static_public, channel_hex))`. Emits the env the
  `mock_daemon` subprocess needs (`FIDO2_CHANNEL_ID` uses `channel_hex`;
  `FIDO2_STATIC_KEY_PATH`; `FIDO2_RELAY_URL`). The app's deep link is
  `fidobridge://pair?<uri-query>`.
- **`device.py`** — one thin `Device` class wrapping `uiautomator2`
  (`d = u2.connect(serial)`), taking full advantage of its features:
  - **App / shell lifecycle:** `d.app_start("com.fidobridge.client")`,
    `d.app_stop(...)`, `d.app_clear(...)` (replaces `pm clear`),
    `d.shell("locksettings set-pin 1234")`, `d.shell("svc power stayon true")`,
    `d.shell("am start -a android.intent.action.VIEW -d <uri>")` for the
    pairing deep link, `d.press("home")` for background/foreground toggles.
  - **Waits & asserts:** `d(text="Recent requests").wait(timeout=...)`,
    `d(textContains="Sign-in request from example.com").exists(timeout=...)`,
    `d(text="WebAuthn sign-in").wait(...)` — no dump-file round-trips, no
    manual `bounds` parsing.
  - **Actions:** `d(text="Reconnect").click()`, `d.press("back")` (reject),
    `d.screenshot(path)` and `d.dump_hierarchy()` for debug artifacts saved
    per step.
  - **Raw adb** (via `subprocess`/`adb`) only where `uiautomator2` does not
    reach:
    - boot wait (`adb wait-for-device` + poll `sys.boot_completed`)
    - `adb install -r app-debug.apk`
    - `adb emu finger touch 1` (emulator **console** command, not a device
      shell command — `uiautomator2` has no equivalent)
- **`main.py`** — orchestrates: boot → lock/stayon → build+install APK
  (unless `--apk` given) → clear app → start `mock_daemon` subprocess
  (capture log to a file) → deep-link pair → assert Home → run scenario →
  assert prompt → fingerprint → assert result → cleanup. Mirrors the
  `mock_daemon`/`HarnessConfig` flow and returns 0/1.

### A.4 Run flow (verified — `all` = make-credential → clear → get-assertion → multi → reset)

1. **Boot** the AVD `fido2` headless (per `EMULATOR_ENV.md`); raw-adb wait for
   `sys.boot_completed`, then `u2.connect(serial)` (installs the agent on
   first connect).
2. **Lock + stayon + unlock**: `locksettings set-pin 1234`,
   `svc power stayon true`, then enter the PIN via the keyguard UI to dismiss
   the secure keyguard (`wm dismiss-keyguard` alone cannot unlock a PIN).
3. **Build & install** the APK with the harness relay URL
   (`FIDO2_RELAY_URL=ws://10.0.2.2:9000/connection/websocket ./gradlew
   assembleDebug`); `adb install -r`. `--apk` skips the build.
4. **Reset app state**: `d.app_clear("com.fidobridge.client")`, then
    `pm grant com.fidobridge.client android.permission.POST_NOTIFICATIONS` (the
    first-launch notification dialog is racy to tap; a direct grant is
    deterministic).
5. **Start `mock_daemon`** with the shared material and the **host-side**
   relay URL (`--host-relay ws://localhost:9000/connection/websocket` — the
   `10.0.2.2` address only resolves *inside* the emulator; a host process
   given it loops reconnecting). Assert it logs `connected to relay channel
   fidobridge:<channel_id>`.
6. **Pair the app**: deep link (URI passed **single-quoted** through the
   device shell — `&`/`?` split it otherwise → the app reports `Missing
   pubkey`). The service starts on resume and the app immediately handshakes
   with the waiting `mock_daemon`, which publishes the request. **No relaunch
   on the first scenario** (a background/foreground toggle now force-stops
   the app mid-request).
7. **Await each request**: assert the `BiometricPrompt` (`WebAuthn sign-in`,
   `example.com`) or the pending row (`Waiting for your approval`).
8. **Approve**: retry `adb emu finger touch 1` while the prompt is still
   showing (the sensor may not be ready the instant the prompt appears); on
   failure tap `Use PIN` and type `1234`. Assert the request row — the
   outcome lives in the merged row `content-desc` (`…, accepted, …`), **not**
   a standalone `Accepted` text node.
9. **Reject** (only for configured indices): the systemui bottom-sheet prompt
    has no Cancel button and ignores BACK — cancel it with a raw adb tap on
    the scrim above the sheet. A user-cancel rejects the request without
    showing an app error dialog; then assert the `…, rejected, …` row.
10. **Verify daemon side**: `mock_daemon` logs
    `get-assertion: OK (N accepted, M rejected)` and exits 0. Between
    requests `mock_daemon` pauses (`--delay`, default 8 s) so each prompt is
    acted on deterministically; between request-steps the app is restarted so
    it performs a fresh Noise handshake with the next `mock_daemon`.
11. **Clear requests**: tap `Clear`, assert the list empties immediately
   (`No requests yet` — the undo snackbar was removed upstream).
12. **Export diagnostics**: scroll to the `Diagnostics` section, tap
   `Export logs`, assert the `Diagnostics exported.` dialog appears and dismiss
   it with `OK`. The Storage Access Framework save sheet is accepted as-is (the
   app's suggested `gatebridge-diagnostics-<epoch>.zip` name, default
   Downloads). Snapshot the device before the tap, diff the exported
   `gatebridge-diagnostics-*.zip` paths afterwards, `adb pull` the new file,
   and validate it: it must contain a non-empty `diagnostics.jsonl` (and
   `diagnostics.1.jsonl` after rotation) whose lines are well-formed
   `{"ts":<millis>,"message":"..."}` JSON. No new file → failure. The
   no-logs path (`No logs to export yet.`) is unit-covered
   (`AppViewModelTest`), not exercised here (a paired app always has entries).
13. **Reset → re-pair**: scroll to the danger zone, tap `Reset app`, confirm
   `Reset`, assert the pairing screen (match the subtitle — the title is not
   exposed to accessibility), then pair again with fresh material and assert
   Home.
14. **Screenshots at every step** are captured with the emulator console
   (`adb emu screenrecord screenshot`), which grabs the raw framebuffer on
   the host and therefore sees FLAG_SECURE surfaces that `screencap` renders
   black (e.g. the `BiometricPrompt`).
15. **Cleanup**: the orchestrator stops `mock_daemon` after every request
   step; `--keep-running` only skips `d.app_stop(...)` and the emulator
   shutdown (when the harness launched it).

### A.5 Assertions (authoritative elements from `UI_TESTER_GUIDE.md` §4)

| Step | Element | Assert |
|---|---|---|
| Pairing lands | HomeScreen | `d(text="Recent requests")`, status banner `Waiting for WebAuthn requests` |
| Request | `BiometricPrompt` | `d(text="WebAuthn sign-in")`, `d(text="example.com")` |
| Approve | Request row | `d(descriptionContains="accepted")` (merged row `content-desc`) |
| Reject | Request row | `d(descriptionContains="rejected")` |
| Clear | Home | `d(text="No requests yet")` (clears immediately, no undo) |
| Export | Home → SAF sheet → dialog | `d(text="Export logs")` → `d(textMatches="(?i)^save$")` → `d(text="Diagnostics exported.")`; pulled `gatebridge-diagnostics-*.zip` holds non-empty, well-formed `diagnostics*.jsonl` |
| Reset | Pairing screen | subtitle `Scan the pairing code from your computer to connect.` (title is not exposed) |
| Re-pair | HomeScreen | `d(text="Recent requests")` |
| State | daemon log | `make-credential: OK`, `get-assertion: OK (N accepted, M rejected)`, exit 0, no `SECURITY ALERT` |

Do **not** run `connectedDebugAndroidTest` — `isInsideSecureHardware` fails on
the emulator (software-backed); that stays device-only (Phase 12 note).

### A.6 Risks & mitigations (observed during bring-up)

- **Emulator timing** (boot, prompt, fingerprint readiness) → generous
  timeouts, `uiautomator2`'s built-in `wait(...)`, fingerprint retry loop,
  PIN fallback, retries on the daemon side.
- **ATX agent install** on first `u2.connect()` needs a network-reachable adb
  device and a working `atx-agent` binary for the emulator ABI (x86_64) —
  verified once during bring-up; artifacts are cached for later runs.
- **Deep-link URI mangling**: `am start -a VIEW -d <uri>` through the device
  shell splits on `&`, dropping `pubkey=` → the app shows `Missing pubkey`.
  The URI must be **single-quoted** (`shlex.quote`) when passed through the
  shell.
- **Two relay URLs**: the APK is baked with the emulator-facing
  `ws://10.0.2.2:9000/...`; host-side `mock_daemon` must get the
  host-facing `ws://localhost:9000/...` (`--host-relay`), or it loops
  reconnecting and never handshakes.
- **Secure keyguard**: after `locksettings set-pin`, `wm dismiss-keyguard` is
  a no-op — the PIN must be entered via the keyguard UI.
- **Accepted/Rejected state is in the row content-desc**, not a standalone
  badge text node — assert with `descriptionContains("accepted"/"rejected")`.
- **Cancelling the prompt** is a raw adb tap on the scrim above the sheet
  (the systemui bottom-sheet prompt has no Cancel button and ignores BACK).
  A user-cancel rejects the request without an app error dialog, so the
  rejected row is visible immediately.
- **POST_NOTIFICATIONS** is pre-granted via `pm grant` after `pm clear`
  (which revokes it) — tapping the system dialog races its animation.
- **SAF save sheet** is AOSP DocumentsUI (`com.android.documentsui`): the
  confirm button is rendered all-caps (`SAVE`), so match it case-insensitively
  (`d(textMatches="(?i)^save$")`, falling back to `descriptionMatches`) and
  keep the app's suggested filename (no filename typing). The new zip is found
  by diffing `gatebridge-diagnostics-*.zip` paths under `/sdcard/Download`/
  `Downloads`/`Documents` before/after the tap, pulled with `adb pull`, and
  validated with Python's `zipfile`. The emulator exposes only local providers,
  so the default destination is deterministic.
- **Screenshots of FLAG_SECURE surfaces** need the emulator console
  (`adb emu screenrecord screenshot`); `screencap` renders them black.
- **Pairing screen title** (`Connect your phone`) is not exposed to
  accessibility — match the subtitle instead.
- **`uiautomator2` `d.shell()` returns a `ShellResponse`**, not a string —
  read its `.output`.
- **One APK build per run** (`RELAY_URL` is baked) → document; `--apk` for
  reuse within a session.
- **Flaky prompt dismiss / reject** → rejection is covered inside `all` by the
  `multi` flow (request #3 is rejected); a separate `Rejected`-only scenario
  is not automated.

---

## C. Documentation (priority)

- **This doc** (`EMULATOR_E2E_TESTING.md`) is the plan + runbook: prereqs,
  install/build steps, the A.4 flow, troubleshooting, cleanup.
- **Cross-link** `INTEGRATION_TESTING.md` ("Emulator E2E" section) and the
  `ANDROID_PLAN.md` §12 status note to this doc, and add the new harness to
  the "Where the pieces live" list.
- **`UI_TESTER_GUIDE.md`**: no changes required (it already catalogs every
  element the orchestrator asserts); if the harness needs a new element, add
  it there first so the doc stays authoritative.

---

## B. CI job (future, optional)

Not part of the current deliverable. Sketch for later: a `emulator-e2e` job on
a KVM-enabled runner that installs the SDK/emulator/AVD per `EMULATOR_ENV.md`,
starts Centrifugo (same config/steps as the existing `integration` job),
builds the harness APK, and runs `python -m emulator_harness all`. Cost is
high (~5 GB SDK, slow emulator boot, atx-agent install); gate it on
`workflow_dispatch`/`main` rather than every PR.

---

## Verification (Definition of Done)

1. `mock_daemon` log shows `get-assertion: OK` and `make-credential: OK`,
   exit 0, no `SECURITY ALERT` lines.
2. `uiautomator2` dumps/screenshots (saved per step) show: paired Home screen,
   prompt with `example.com`, `Accepted` badge.
3. The export step pulls a non-empty `gatebridge-diagnostics-*.zip` into the
   artifact dir that unzips to `diagnostics.jsonl` (and `diagnostics.1.jsonl`
   after rotation) with well-formed `{"ts":<millis>,"message":"..."}` lines.
4. `adb logcat -d | grep -iE "fido|pipeline|bridge"` shows Connected → prompt
   → sign.
5. The orchestrator exits 0 and cleans up (`d.app_stop`, `mock_daemon`
   stopped).

## Cleanup

```bash
adb shell am force-stop com.fidobridge.client
# mock_daemon subprocess is stopped by the orchestrator after every request step;
# --keep-running only skips app-stop and emulator shutdown
# Centrifugo is stopped by the operator (as in INTEGRATION_TESTING.md)
```
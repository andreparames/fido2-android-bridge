# UI Tester Guide — FIDO Bridge Android App (adb)

Guide for an automated UI tester agent driving the Android client
(`com.fidobridge.client`) through `adb`. Covers the app's screens, flows, and
on-screen elements, plus concrete adb recipes. Test via the real APK on an
emulator/device; all assertions are done on `uiautomator` dumps / screenshots,
not on source.

Companion docs: `ANDROID_UI_UX_PLAN.md` (design intent), `EMULATOR_ENV.md`
(emulator setup), `PROTOCOL.md` (pairing URI + wire format), `AGENTS.md`.

---

## 1. App facts

| Thing | Value |
|---|---|
| Package / applicationId | `com.fidobridge.client` |
| Main activity | `com.fidobridge.client/.MainActivity` (`launchMode=singleTask`, exported) |
| Background service | `com.fidobridge.client/.networking.FidoBridgeService` (foreground, `dataSync`) |
| Deep-link scheme | `fidobridge://pair?channel=<32hex>&pubkey=<b64url>&[token=<..>][&v=3]` (v optional; must equal `3` if present) |
| Relay endpoint | baked into the APK at build time (`BuildConfig.RELAY_URL`, default `wss://gary.andreparames.com:8000/connection/websocket`) |
| minSdk / targetSdk | 26 / 34 |

What the app does: a remote WebAuthn request arrives over an E2EE WebSocket,
the app asks the user to approve with a system `BiometricPrompt`, signs in the
hardware key store, and returns the result. It is a **monitoring console** when
paired: the Home screen shows connection state and a recent-requests history.

---

## 2. Environment prerequisites

- Emulator AVD **`fido2`** (Pixel 5, `system-images;android-34;google_apis;x86_64`)
  already created per `EMULATOR_ENV.md`. Boot headless:
  ```bash
  SDK="$HOME/Android/Sdk"
  sg kvm -c "$SDK/emulator/emulator -avd fido2 -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect"
  adb wait-for-device && adb shell 'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 1; done'
  ```
- Device screen lock PIN **`1234`** (set: `adb shell locksettings set-pin 1234`).
  A lock screen must exist for `BiometricPrompt` (device-credential fallback).
- Virtual fingerprint: `adb emu finger touch 1`. (Enrollment may be unconfirmed;
  if the fingerprint does not satisfy the prompt, use the PIN fallback path.)
- Build & install:
  ```bash
  ./gradlew assembleDebug
  adb install -r app/build/outputs/apk/debug/app-debug.apk
  ```
- **Pairing URI**: generate with the daemon CLI (prints `fidobridge://pair?...`):
  ```bash
  cd linux-fido-daemon/src && ../.venv/bin/python -m fido_daemon.cli pair --no-qr
  ```
- **Receiving real requests** (to test the request list / approval flow) requires
  the daemon + relay to be live so WebAuthn requests reach the app — see
  `EMULATOR_ENV.md` and the daemon README. Without them the app shows
  `Disconnected`/`Connecting` and no requests arrive.
- **Reset app state** between tests:
  ```bash
  adb shell pm clear com.fidobridge.client   # wipes pairing + credentials
  ```

---

## 3. adb toolkit

```bash
A="com.fidobridge.client"
# launch / stop
adb shell am start -n $A/.MainActivity
adb shell am force-stop $A
# deep-link pairing (preferred over typing the URI)
adb shell am start -a android.intent.action.VIEW -d 'fidobridge://pair?channel=<..>&pubkey=<..>' $A
# background / foreground
adb shell input keyevent KEYCODE_HOME
adb shell am start -n $A/.MainActivity
# screenshot
adb exec-out screencap -p > /tmp/screen.png
# UI hierarchy (Compose: match on text / content-desc; resource-ids are usually empty)
adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml
# notifications
adb shell dumpsys notification --noredact
# service running?
adb shell dumpsys activity services $A
# fingerprint + key navigation
adb emu finger touch 1
adb shell input keyevent KEYCODE_BACK
adb shell input keyevent KEYCODE_ENTER
# network toggles (for connection-state tests)
adb shell svc wifi disable ; adb shell svc wifi enable
```

**Tap by text** (adb has no native "tap text"); parse bounds from the dump:

```python
import re, subprocess, xml.etree.ElementTree as ET
def tap_text(text):
    subprocess.run(["adb","shell","uiautomator","dump","/sdcard/ui.xml"])
    subprocess.run(["adb","pull","/sdcard/ui.xml","/tmp/ui.xml"])
    root = ET.parse("/tmp/ui.xml").getroot()
    for n in root.iter("node"):
        if n.get("text") == text or n.get("content-desc") == text:
            x1,y1,x2,y2 = map(int, re.findall(r"\d+", n.get("bounds")))
            subprocess.run(["adb","shell","input","tap",str((x1+x2)//2),str((y1+y2)//2)])
            return
    raise SystemExit(f"element not found: {text}")
```

`adb shell input text` mangles `& ? = :` in URIs — **prefer the deep-link intent**
for pairing; use `input text` only for short ASCII strings (spaces → `%s`).

---

## 4. Screens & elements

### 4.1 PairingScreen (route `pairing` — shown when not paired)

| Element | Type | Text / content-desc |
|---|---|---|
| Title | Text | `Connect your phone` |
| Subtitle | Text | `Scan the pairing code from your computer to connect.` |
| Camera rationale (permission not yet granted) | Card | `Allow camera access to scan the pairing code`, `Or paste the pairing URI below.`, button **`Allow camera`** |
| Camera denied | Card | `Camera access is needed to scan the pairing code`, `You can still pair by pasting the URI below.`, button **`Request again`** |
| QR scanner | AndroidView (no text node) | shown only when CAMERA granted |
| Manual URI field | TextField label | `Or paste pairing URI` (+ supporting text **`Invalid pairing URI`** when malformed) |
| Pair button | Button | `Pair` → `Pairing…` while submitting (disabled while blank or pairing) |
| Error banner | Card (errorContainer) | the failure message, e.g. version mismatch / malformed URI |

### 4.2 HomeScreen (route `home` — shown when paired)

**Connection status banner** (dot `content-desc` = the status title):

| Bridge state | dot desc / title | subtitle | actions |
|---|---|---|---|
| Connected | `Waiting for WebAuthn requests` | `Requests are approved with your fingerprint` | — |
| Connecting | `Connecting…` | — | — |
| Disconnected | `Not connected` | — | **`Reconnect`** |
| Error | the error message (e.g. `not paired: missing channel`) | — | **`Reconnect`** |
| SecurityAlert | `Approvals are paused` | `Corrupted messages were received on the relay connection. This can happen with network interference or a relay problem.` | **`Acknowledge`**, **`Reconnect`** |

**Request list:**
| Element | Type | Text / content-desc |
|---|---|---|
| List header | Text | `Recent requests` |
| Pending queue note | Text (only when >1 pending) | `N more waiting` |
| Clear | TextButton | `Clear` (disabled when list empty) |
| Row | Card (merged semantics) | content-desc = `Sign-in request from example.com, accepted, 2 minutes ago` (also `Register` / `Browser check`; outcome `rejected` / `pending`) |
| Row visible text | Text | rpId (primary); secondary `Sign-in · 2 min ago` — or **`Waiting for your approval`** for the pending row (full rpId, no ellipsis) |
| Outcome badge | Chip (icon+text, semantics) | `Accepted` / `Rejected` / `Pending` |
| Empty state | Column | `No requests yet` / `When your computer asks to sign in, the request will appear here.` |

**Actions & dialogs:**
| Element | Type | Text |
|---|---|---|
| Reset section | Text | `Danger zone` |
| Reset button | OutlinedButton | `Reset app` |
| Reset confirm | AlertDialog | title `Reset app?`, body `This erases your pairing key and all stored credentials. This can't be undone.`, buttons `Cancel` / `Reset` |
| Clear snackbar | Snackbar | `Request history cleared` + action **`Undo`** |
| Error dialog | AlertDialog (M3) | title `FIDO Bridge error`, body = message, button `OK` |

### 4.3 System surfaces

| Surface | Trigger | Content |
|---|---|---|
| `BiometricPrompt` | any sign-in/register request while app foregrounded | title `WebAuthn sign-in`, subtitle = the domain (or `Allow a website to use this authenticator?` for a Chromium probe) |
| Foreground-service notification | app paired (channel `fidobridge_relay`, id 1) | title `FIDO Bridge`, text mirrors bridge state (`Waiting for WebAuthn requests` / `Connecting…` / `Not connected` / `Security alert — approvals paused` / error) |
| Heads-up approval notification | request arrives while app **backgrounded** (channel `fidobridge_approval`, id 2) | title `Approve sign-in`, text `Sign-in requested by <domain> — open FIDO Bridge to approve`; tap opens the app |

---

## 5. Test flows (adb step-by-step)

### F1 — First-run pairing screen
```bash
adb shell pm clear com.fidobridge.client
adb shell am start -n com.fidobridge.client/.MainActivity
adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml
```
- Assert `Connect your phone` and `Scan the pairing code...` are present.
- **Camera permission**: fresh install → CAMERA not granted → the rationale card
  (`Allow camera access to scan the pairing code`) is shown, **not** the scanner.
  Tap `Allow camera` → system permission dialog → `Allow` → scanner appears.
  Repeat with `Don't allow` → the denied card + `Request again` is shown.
  Verify grant via: `adb shell dumpsys package com.fidobridge.client | grep -i camera`.

### F2 — Manual URI field + inline validation
- Type a malformed URI → supporting text `Invalid pairing URI` appears and the
  Pair button is enabled:
  ```bash
  adb shell input text 'fidobridge://pair?channel=zz'
  ```
  (Expect the `Invalid pairing URI` supporting text.)
- Type a valid URI (from the daemon `pair` CLI) → no error text; `Pair` → the
  button briefly shows `Pairing…`, then the app lands on HomeScreen (F4).

### F3 — Pair via deep link (cleanest)
```bash
URI=$(cd linux-fido-daemon/src && ../.venv/bin/python -m fido_daemon.cli pair --no-qr)
adb shell am start -a android.intent.action.VIEW -d "$URI" com.fidobridge.client
```
- Assert the app pairs and lands on HomeScreen (`Recent requests` present) and
  the foreground service started:
  `adb shell dumpsys activity services com.fidobridge.client`.

### F4 — Connection status states
- With daemon+relay **down**: Home shows `Not connected` + `Reconnect`.
- Enable network (`adb shell svc wifi enable`); relay auto-reconnects → the banner
  transitions `Connecting…` → `Waiting for WebAuthn requests`.
- To force a drop, `adb shell svc wifi disable`; banner flips back to `Not connected`.
- Tap `Reconnect` → banner returns to `Waiting for WebAuthn requests`.
- Assert the foreground notification text updates with the state
  (`dumpsys notification --noredact`).

### F5 — Request approval (happy path, needs live daemon + relay)
1. App foregrounded on Home; trigger a WebAuthn sign-in from the remote side
   (see `EMULATOR_ENV.md` / daemon README for the full E2E setup).
2. `BiometricPrompt` appears: title `WebAuthn sign-in`, subtitle = the domain.
   In the list, a `Pending` row with `Waiting for your approval` is highlighted.
3. Approve: `adb emu finger touch 1` (or PIN fallback) → the row's badge becomes
   `Accepted`; the remote sign-in completes.
4. Reject: trigger again and dismiss the prompt (`KEYCODE_BACK` or the system
   Cancel) → the row's badge becomes `Rejected` and the remote request fails
   with operation-denied.
5. If two requests arrive quickly, assert the `N more waiting` note.

### F6 — Clear with undo (needs requests in the list)
- With ≥1 request row, tap `Clear` → snackbar `Request history cleared` + `Undo`.
- Tap `Undo` → the rows are restored. `Clear` is disabled when the list is empty.

### F7 — Reset app
- From Home, scroll to bottom (`adb shell input swipe ...`), tap `Reset app` →
  dialog `Reset app?` with the destructive copy → tap `Reset`.
- Assert: returns to PairingScreen (`Connect your phone`), the foreground
  service is stopped (`dumpsys activity services` shows none), and after a
  force-stop + relaunch the app still starts on PairingScreen (state wiped).

### F8 — Backgrounded heads-up notification
1. Pair + be on Home so the service runs.
2. Background the app: `adb shell input keyevent KEYCODE_HOME`.
3. Trigger a sign-in request from the remote side.
4. Assert an `Approve sign-in` / `Sign-in requested by <domain> — open FIDO
   Bridge to approve` notification exists:
   `adb shell dumpsys notification --noredact | grep -A6 fidobridge_approval`.
5. Tap the notification → the app comes to the foreground and the pending
   request is awaiting approval.

### F9 — Security alert (best-effort)
Not reliably reproducible over adb (it requires repeated Noise integrity
failures). Covered by unit tests (`IntegrityFailureTrackerTest`,
`BridgePipelineTest`). If you can inject tampered frames via the relay, expect
the `Approvals are paused` banner with `Acknowledge`/`Reconnect`; approvals are
dropped while it is active, and `Acknowledge` returns to the normal state.

---

## 6. Gotchas

- **Compose nodes**: `uiautomator dump` exposes Compose text as `text` and
  semantics as `content-desc`, but `resource-id` is usually absent. Match on
  text/content-desc; never hardcode coordinates (parse `bounds`).
- **Request rows** merge their semantics: the row's `content-desc` is the full
  sentence (`Sign-in request from example.com, accepted, 2 minutes ago`); the
  badge is not a separate node.
- **`input text` and URIs**: special chars break. Use the deep-link intent for
  pairing; `input text` only for short ASCII (spaces as `%s`).
- **Dynamic color (Material You, API 31+)**: background/surface colors vary by
  wallpaper — assert on text/icons/`content-desc`, never exact colors. Semantic
  badge colors are fixed tokens but still don't assert RGB.
- **Fingerprint on the emulator**: enrollment may be unconfirmed; if
  `adb emu finger touch 1` does not satisfy the prompt, approve via the PIN
  fallback (`1234`). A lock screen must be set for any approval to work.
- **"Connected" is not guaranteed**: the app only shows `Connected` when the
  relay (`BuildConfig.RELAY_URL`) is reachable and the daemon handshake
  completes. Test connection states with network toggles; expect
  `Connecting…`/`Not connected` otherwise.
- **Real requests need a live daemon + relay**: request-list rows,
  approvals, and the heads-up notification cannot be produced by adb alone.
- **Clean state**: `adb shell pm clear com.fidobridge.client` wipes pairing and
  credentials — faster than the in-app reset for test isolation. Note it also
  resets notifications channels; re-run the app to recreate them.
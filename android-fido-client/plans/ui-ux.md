# HomeScreen, Request History & Reset (UI/UX)

This plan reworks the Android client's user-facing surfaces, folding in a senior
mobile-app-designer review of the target UI. Scope: a useful HomeScreen (waiting
status + recent request history + clear/reset actions), threshold-based handling
of GCM-tag failures, and pairing-screen polish. No changes to the wire protocol.

Source of truth for security requirements: `AGENTS.md` and `PROTOCOL.md`.

---

## 0. Status Quo

- `MainActivity.kt` hosts `BiometricPrompt` handling; a `FidoBridgeService`
  (foreground, `DATA_SYNC`) keeps the WebSocket alive while paired.
- `HomeScreen` (`ui/FidoBridgeApp.kt:51-57`) is a stub showing only the title.
- `AppViewModel` (`ui/AppViewModel.kt`) exposes only `isPaired`.
- `BridgePipeline` exposes `BridgeState` (Disconnected/Connecting/Connected/
  SecurityAlert/Error) but the UI renders none of it; `SecurityAlert` is
  transient — a later `Connected` event overwrites it.
- Requests flow: `RelayClient.inbound` → `BridgePipeline` → `Ctap2Processor`
  → `signer.sign` → `BiometricPromptCoordinator` → `MainActivity` prompt.
  Outcomes are decided in `Ctap2Processor`'s signer callbacks and early-return
  error paths.
- `PairingScreen` (`ui/pairing/PairingScreen.kt`) has QR + manual paste, no
  explanation, no permission rationale, plain-text error display.

---

## 1. Request History (in-memory, privacy-first)

**Files:** `ui/model/RequestRecord.kt` (new), `RequestLog.kt` (new),
`ctap/Ctap2Processor.kt`, `di/DataModule.kt`

- `RequestRecord(id, type, rpId, timestamp, outcome)` where:
  - `type`: `Sign-in` (getAssertion) | `Register` (makeCredential) | `Browser-check` (dummy probe).
  - `outcome`: `Pending | Accepted | Rejected`.
- `RequestLog` (`@Singleton`): in-memory `MutableStateFlow<List<RequestRecord>>`,
  newest first, capped at ~50; `record()`, `markAccepted(id)`,
  `markRejected(id)`, `clear()`.
- **Never persisted** — it is a log of visited domains; in-memory only.
- Add default `NoOpRequestLog` constructor param to `Ctap2Processor` so the 12
  existing test call sites compile unchanged; `DataModule` injects the real one.

---

## 2. Outcome Recording (`Ctap2Processor`)

**File:** `ctap/Ctap2Processor.kt`

- At the start of `handleGetAssertion` / `handleMakeCredential`: `record(type, rpId)`
  → row starts `Pending`.
- Mark `Accepted`/`Rejected` at every terminal point:
  - `signer.sign` callbacks (`:61`, `:120`): success → Accepted, failure → Rejected.
  - Early-return errors (no credentials, invalid option, malformed, etc.) → Rejected.
- `ping` is not logged.
- Dummy probes (`DUMMY_RP_ID`, `:103`) are logged as `Browser-check`; the UI
  labels them "Browser check / Allow this site to use this authenticator", never
  the raw `.dummy`.

---

## 3. Integrity-Failure → Threshold-Based, Sticky, Neutral

> Note: the client now uses the Noise IK transport (`PROTOCOL.md` §3/§6), so the
> "GCM-tag failure" of the original review manifests as a Noise authentication
> failure (`NoiseSession.AuthenticationException`) in `RelayClient`.

**Files:** `bridge/IntegrityFailureTracker.kt` (new), `bridge/BridgePipeline.kt`

- Keep current behavior for **isolated** failures: drop the message, log, stay
  quiet (crypto already prevented anything bad).
- `IntegrityFailureTracker`: **sliding-window failure counter** (constants in one
  `companion object`, defaults `≥3` failures within `60s`). Threshold is
  generous — isolated corruption on a flaky link must never trip it. (TLS already
  provides transport integrity, so an app-layer integrity failure is already
  unexpected; the counter is for *sustained* patterns, not single blips.)
- Only when the threshold is crossed: raise `SecurityAlert` and make it
  **sticky** — `BridgePipeline`'s `statusJob` / disconnection collector must not
  overwrite it on the next `Connected`/`Disconnected` event — and **pause
  approvals** (inbound requests are dropped) until acknowledged.
- **Neutral user-facing copy — never claim an attack.** Example:
  > "Corrupted messages were received on the relay connection. This can happen
  > with network interference or a relay problem. Approvals are paused for your
  > safety — reconnect or re-pair."
- `acknowledgeSecurityAlert()` resets the counter and returns the UI to the
  current connection state. (Technical detail can stay in logs.)

---

## 4. Connection Status & Reconnect

**Files:** `bridge/BridgePipeline.kt`, `ui/AppViewModel.kt`

- Render all states as **dot + icon + label**, never color alone:
  - `Connected` — green, `check_circle`, "Waiting for requests".
  - `Connecting` — amber, pulsing, "Connecting…" / "Reconnecting…".
  - `Disconnected` — gray, `link_off`, "Not connected".
  - `SecurityAlert` — red banner, `shield`, sticky (see §3).
  - `Error` — red, `error`, with message.
- Transport already auto-reconnects; add `BridgePipeline.reconnect()` (= `stop()` +
  `start()`), exposed as a **"Reconnect"** button on Disconnected/Error.

---

## 5. Reset / Re-Pair

**Files:** `pairing/IdentityStore.kt`, `pairing/EncryptedIdentityStore.kt`,
`ctap/Credential.kt`, `ctap/PersistentCredentialStore.kt`,
`security/KeystoreManager.kt`, `pairing/AppResetManager.kt` (new), `di/DataModule.kt`

- Add `clear()` to `IdentityStore` + `EncryptedIdentityStore` (wipe the
  EncryptedSharedPreferences: phone key, daemon key, channel, relay token).
- Add `clear()` to `CredentialStore` + `PersistentCredentialStore`.
- Add `KeystoreManager.deleteAllSigningKeys()` — delete `fido-cred-*` aliases
  from `AndroidKeyStore` (private keys must not linger after unpairing).
- **`AppResetManager`** (`@Singleton`) aggregates the above + `requestLog.clear()`.
- Reset flow: destructive **modal** ("This erases your key and all stored
  credentials. Can't be undone."), destructive-styled confirm → stop the
  foreground service → navigate to `PAIRING` → **"Ready to pair" empty state**
  (no silent jump).

---

## 6. AppViewModel

**File:** `ui/AppViewModel.kt`

Inject `RequestLog`, `BridgePipeline`, `AppResetManager`. Expose:
- `requests: StateFlow<List<RequestRecord>>`
- `bridgeState: StateFlow<BridgeState>`
- `clearLog()`, `acknowledgeSecurityAlert()`, `reconnect()`, `reset()`

---

## 7. HomeScreen UI

**File:** `ui/FidoBridgeApp.kt` (replace `HomeScreen` stub)

- **Compact status header**: status dot + one-line "Waiting for WebAuthn requests"
  (+ subtle pulse when Connected; optional secondary line "Requests are approved
  with your fingerprint"). Reserve strong visuals for abnormal states.
- **Edge-to-edge**: apply `WindowInsets` correctly (list not under status bar;
  Reset not hidden by nav bar); cap content width ~600dp on tablets/foldables.
- **List rows**: leading icon in a tonal circle (`login` / `person_add` / probe
  icon); rpId primary (middle-ellipsis); secondary "Sign-in · 2 min ago"
  (relative time); trailing outcome badge — **icon + text** (`check` /
  `close` / `hourglass`) in semantic colors.
- **Pending row pinned/highlighted** ("Waiting for your approval", elevated/
  outlined) so the user can cross-check the domain against the system prompt
  (verify-before-approve). Full, untruncated rpId on the pending row. Queue
  surfaced as "2 more requests waiting".
- **Empty state** distinct from Disconnected ("No requests yet. When your
  computer asks to sign in, the request will appear here.").
- **Clear**: section-header action on the list, disabled when empty,
  **Snackbar + Undo** (no dialog).
- **Reset**: bottom, visually separated "Danger zone", destructive.
- **Semantic color tokens** (success/error/neutral/amber) defined once in the
  theme, verified for contrast (4.5:1 text, 3:1 graphics) and color-blind
  safety; TalkBack semantics on every dot/badge/icon and merged per-row
  descriptions.

---

## 8. PairingScreen

**File:** `ui/pairing/PairingScreen.kt`

- First-run explanation line ("Scan the pairing code from your computer to connect").
- Camera **permission rationale** before requesting; **denied-camera fallback**
  (message + manual field promoted).
- **Inline paste validation** (`isError` on the field as they paste, not only on Pair).
- M3 error banner instead of the plain colored text; "Pairing…" + disabled state
  on the button while negotiating.
- (Optional, low) subtle "Remove all data" footer for half-paired/corrupt states.

---

## 9. Backgrounded-Request Heads-Up Notification

**Files:** `RequestNotifier.kt` (new), `FidoBridgeService.kt`, `MainActivity.kt`,
`gradle/libs.versions.toml` (+`androidx.lifecycle:lifecycle-process`)

- **`RequestNotifier`**: when a signing request is enqueued and the app is not
  foregrounded, post a **HIGH-importance heads-up** notification
  ("Sign-in requested by example.com — open FIDO Bridge to approve") with a
  `PendingIntent` to `MainActivity`.
- Foreground detection via `ProcessLifecycleOwner` or an app-level foreground
  flag maintained by `MainActivity`.
- **No FCM involved**: the WebSocket already delivers the request; the
  notification only surfaces the app so `BiometricPrompt` can show. (FCM would
  only matter if the process died and the socket dropped.)
- Update the static foreground-service notification to reflect
  Connected/Disconnected state.

---

## 10. Platform Misc

- Migrate the platform `AlertDialog` in `MainActivity.kt:195-201` to M3
  `AlertDialog` (or route through the Home error banner).
- Low priority: enable dynamic color (Material You) in `ui/theme/Theme.kt`.

---

## 11. DI Wiring

**File:** `di/DataModule.kt`

Provide `RequestLog`, `AppResetManager`, `RequestNotifier`; add
`lifecycle-process` dependency; pass `RequestLog` into `Ctap2Processor`.

---

## 12. Tests & Verification

- `RequestLogTest` — Pending→Accepted/Rejected transitions, cap, order, clear.
- `AppViewModelTest` — clear/reset/acknowledge/reconnect, using fakes.
- Tag-failure **threshold test** — isolated failures stay quiet; sustained
  failures within the window raise the sticky alert; once acknowledged, normal
  state resumes.
- `Ctap2Processor` recording test — accepted vs rejected vs probe; existing call
  sites untouched via the default param.
- UI smoke: pending-row highlighting, empty state, clear-with-undo, reset modal.
- Run `./gradlew test`, `./gradlew assembleDebug`, and lint.

---

## 13. Scope Notes

- Section 9 (heads-up notification) and sections 3–4, 8 are behavioral additions
  beyond the original three features (waiting status, request list + clear,
  reset). They were folded in per request and can be deferred individually.
- Open question: exact GCM-failure threshold constants — pick values defensively
  (e.g. ≥3 within 60s) and tune from real-world telemetry.
# TODO

Tracking follow-up work. See `ANDROID_UI_UX_PLAN.md` and `UI_TESTER_GUIDE.md` for
context, and the CodeRabbit review on PR #1 for the source of these items.

---

## In progress

*(none)*

## Backlog

### [ ] Fix signing-request notification race when the app is leaving the foreground

**Source:** CodeRabbit review, PR #1 — `notifications/ForegroundStateProvider.kt`
("Use immediate activity visibility for signing-request delivery").

**Problem:** `RequestNotifier` gates the heads-up notification on
`ProcessLifecycleOwner.currentState.isAtLeast(RESUMED)`, but that process-level
state only drops to `STARTED` on the last `onStop` (asynchronously). Meanwhile
`MainActivity.collectSigningRequests()` uses a bare `lifecycleScope.launch {
collect }` which keeps consuming requests while the activity is paused/stopped.
In the `onPause`→`onStop` window the gate says "foregrounded" (notification
skipped) but `BiometricPrompt` can't be shown, so the request is dismissed and
never re-queued — a missed sign-in with no notification and no retry.

**Fix direction:**
- Gate notification delivery on MainActivity's own immediate resumed state
  (a `@Volatile` flag or a state flow updated in `onResume`/`onPause`), instead
  of `ProcessLifecycleOwner`.
- Consume signing requests only while resumed
  (`repeatOnLifecycle(Lifecycle.State.RESUMED)` or a lifecycle check before
  `authenticate()`), and requeue the request when the prompt is dismissed
  because the app left the foreground.

**Files:** `networking/../notifications/ForegroundStateProvider.kt`,
`notifications/RequestNotifier.kt`, `MainActivity.kt`,
`security/BiometricPromptCoordinator.kt`.

### [ ] Make INTEGRATION_TESTING.md `cd` commands robust to the shell's current directory

**Source:** CodeRabbit review, PR #1 — `INTEGRATION_TESTING.md` ("Use the
repository root for each directory change").

**Problem:** The doc's shell snippets `cd` using paths relative to the repo
root (`cd linux-fido-daemon` at lines 40/88/118, `cd android-fido-client` at
line 134), but the shell's working directory drifts as the steps run in order.
From inside `linux-fido-daemon/`, the later `cd linux-fido-daemon` and
`cd android-fido-client` fail, so step 4 cannot find `./gradlew` (and the
daemon step may run from the wrong directory if the shell continues past a
failed `cd`). It only works because a real run started from the repo root each
time.

**Fix direction:**
- Resolve each target from the git worktree root:
  `cd "$(git rev-parse --show-toplevel)/linux-fido-daemon"` and
  `cd "$(git rev-parse --show-toplevel)/android-fido-client"`.
- Or state up front "run every snippet from the repo root".

**Files:** `INTEGRATION_TESTING.md`.

---

## Done

### [x] Store Android logs locally, export them as a zip, and gate relay logging by build

Implemented per `ANDROID_LOGGING_PLAN.md`:

- Local persistence: `DiagnosticLogStore` / `FileDiagnosticLogStore` (JSON Lines,
  size-based rotation, always written — never dropped when the relay is down).
- `DiagnosticLogSink` fans out to the local store and an optional
  `RelayLogPublisher`; entries persist regardless of relay state.
- Build split: `BuildConfig.DIAGNOSTIC_RELAY_ENABLED` (debug `true`, release
  `false`); prod/release builds never publish diagnostics.
- Export: `DiagnosticsExporter` zips the `.jsonl` files and writes them through
  the Storage Access Framework (`Export logs` button on Home); `Reset app`
  also clears the on-device store.
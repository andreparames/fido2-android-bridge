# ANDROID_LOGGING_PLAN — Local diagnostics persistence + export

Covers the three TODOs added to `TODO.md`:

1. Store Android logs locally, in addition to publishing them to Centrifugo.
2. Add a button to export the logs to local storage (SD card, Google Drive, etc).
3. Distinguish debug vs prod builds; prod does **not** log to Centrifugo, only locally.

Wire formats are unaffected — `PROTOCOL.md` still governs the relay. This plan
only changes where diagnostics are persisted and whether they are published.

---

## 1. Current state (as-is)

- `DiagnosticLogSink` (`networking/DiagnosticLogSink.kt`) is a `@Singleton` with
  **no `Context`**. It opens one Centrifugo subscription to
  `fidobridge:log:<channel_id>` and `publish`es each entry.
- `log()` **drops the entry** when the relay subscription is not connected
  (`DiagnosticLogSink.kt:55-60`) — the core bug behind TODO 1.
- Entries are `@Serializable data class Entry(ts: Long, message: String)`
  encoded as JSON and published as bytes.
- Call sites: `BridgePipeline` (start/stop, relay state, security alerts,
  disconnects) and `MainActivity` (lifecycle + biometric prompt). Both use a
  nullable `logSink` / injected `logSink`.
- `DiagnosticLogSink.start(channelId, relayUrl, relayToken)` is called from
  `BridgePipeline.startInternal()` (`BridgePipeline.kt:78`); `stop()` from
  `BridgePipeline.stop()`.
- The separate `RequestLog` (`ui/model/`) is **in-memory UI history only** and
  is unrelated — do not merge the two.
- `AppResetManager.reset()` clears keystore keys, identity, credentials, and the
  request log. It does **not** know about diagnostics.
- Build has `debug`/`release` build types (`app/build.gradle.kts:51-62`) and
  `BuildConfig` is enabled (`:73-76`). There is no diagnostic build flag today.

---

## 2. Design decisions

| Decision | Choice |
|---|---|
| Storage location | App-private `context.filesDir/diagnostics/diagnostics.jsonl` |
| Format | JSON Lines (one `Entry(ts, message)` per line) — append-only, greppable, tail-friendly, matches the existing serialized shape |
| Rotation | Size-based: rotate to `diagnostics.1.jsonl` at a cap (default 1 MiB), keep 2 files total (~2 MiB ceiling) |
| Thread safety | Serialize writes behind a lock + append; writes dispatched on `Dispatchers.IO` |
| Local history | Local write is **always** performed and **never** dropped; relay publish is best-effort and optional |
| Relay gating | `BuildConfig.DIAGNOSTIC_RELAY_ENABLED` (debug `true`, release `false`) |
| Export mechanism | Zip the `.jsonl` files in-memory, then Storage Access Framework `ActivityResultContracts.CreateDocument` → user picks SD card, Google Drive, any document provider |
| Reset behavior | `AppResetManager` also clears the in-app diagnostics file (exported copies are outside the sandbox and unaffected) |

Secrets: no session key, relay token, Noise key, or pairing URI may enter a log
line. Audit all `logSink.log(...)` call sites; add a scrubber for the relay
token as defense-in-depth. Local `filesDir` is already sandboxed, but logs are
exportable, so treat them as shareable.

---

## 3. Components

### 3.1 `DiagnosticLogStore` (new, `networking/DiagnosticLogStore.kt`)

```kotlin
interface DiagnosticLogStore {
    fun append(message: String)
    fun readAll(): String
    fun exportZipTo(output: java.io.OutputStream)
    fun clear()
    fun sizeBytes(): Long
}
```

- `FileDiagnosticLogStore(dir: File, maxBytes: Long = 1L shl 20, clock: () -> Long = System::currentTimeMillis)`.
- `append`: create dir, rotate if `size + line > maxBytes`, append
  `{"ts":<millis>,"message":"<escaped>"}\n` (reuse `kotlinx.serialization` on the
  existing `Entry` shape; share the type in one place).
- `readAll`: concatenate rotated + current file oldest→newest; skip malformed
  lines rather than throwing.
- `exportZipTo`: stream one `ZipOutputStream` over the rotated + current files,
  entries named `diagnostics.1.jsonl` / `diagnostics.jsonl` (oldest first, empty
  files omitted). Stays in `FileDiagnosticLogStore` so file naming stays
  encapsulated and the zip is JVM-testable.
- Constructor takes a `File` (not `Context`) so it is JVM-unit-testable with a
  `tmp` dir. Hilt provides `File(context.filesDir, "diagnostics")` via
  `@ApplicationContext`.

### 3.2 `DiagnosticLogSink` (refactor, `networking/DiagnosticLogSink.kt`)

- Inject `DiagnosticLogStore` and a `relayEnabled: Boolean` (from
  `BuildConfig.DIAGNOSTIC_RELAY_ENABLED`).
- `log(message)`: **always** `store.append(message)`; if `relayEnabled` and the
  subscription is connected, publish as today. Remove the early-return drop.
- `start(...)`: no-op relay connect when `!relayEnabled` (keeps local logging
  working with no relay credentials). `stop()` only tears down the relay.
- Keep the class name and public methods so `BridgePipeline`/`MainActivity` need
  no signature changes beyond DI.

### 3.3 Build gating (`app/build.gradle.kts`)

- `debug { buildConfigField("boolean", "DIAGNOSTIC_RELAY_ENABLED", "true") }`
- `release { buildConfigField("boolean", "DIAGNOSTIC_RELAY_ENABLED", "false") }`
- `DataModule` passes the flag into the sink. (A future `productFlavors` split
  for the Play/OSS tracks in `playstore/BILLING_PLAN.md` can refine this; not a
  dependency here.)

### 3.4 Export (UI + ViewModel)

- `AppViewModel`: `fun exportDiagnostics(uri: Uri, onDone: (Boolean) -> Unit)`
  using `viewModelScope` + `Dispatchers.IO`; open the `contentResolver` output
  stream for `uri` and hand it to `store.exportZipTo(...)` (no temp file, no full
  copy in memory); post a message via `UserMessageBus`.
- `HomeScreen`: add an "Export logs" `OutlinedButton` in a new "Diagnostics"
  section above the Danger zone, wired to
  `rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip"))`
  with suggested name `gatebridge-diagnostics-<epoch>.zip`.
- Empty store → disabled button / "No logs yet" message.
- Works for SD card, Google Drive, and any SAF provider with no storage
  permission and no `MANIFEST` change.

### 3.5 Reset integration

- Add `DiagnosticLogStore` to `AppResetManager` and call `clear()` in `reset()`;
  update `DataModule.provideAppResetManager`.
- Update `playstore/PLAN.md` §Technical facts / privacy wording to state that
  Reset app clears on-device diagnostics (already-exported files remain).

---

## 4. TDD milestones

Per the repo rule ("no implementation code precedes its failing test"):

- **M1 — Store.** Red: `FileDiagnosticLogStoreTest` (tmp dir) for append/readAll,
  rotation at cap, `exportZipTo` (valid zip, expected entry names/contents,
  empty-file omission), clear, malformed-line tolerance, concurrent appends.
  Green:
  `FileDiagnosticLogStore` + `DiagnosticLogStore`.
- **M2 — Fan-out + build gate.** Red: `DiagnosticLogSinkTest` with a fake
  publisher: local write happens when relay disabled; local write still happens
  when relay disconnected (regression for the current drop); relay publish only
  when enabled + connected. Green: refactor `DiagnosticLogSink`, add
  `BuildConfig.DIAGNOSTIC_RELAY_ENABLED`, update `DataModule`.
- **M3 — Export + reset.** Red: `AppResetManagerTest` clears the store; a
  ViewModel export test (fake resolver/store) writes a valid zip. Green:
  `AppViewModel.exportDiagnostics`, `HomeScreen` button + SAF launcher, reset
  wiring.
- **M4 — Docs + E2E.** Update `UI_TESTER_GUIDE.md` (button + file output),
  `playstore/` privacy/plan wording, and add an emulator-harness assertion that
  the export produces a non-empty file. Run `./gradlew test lint assembleDebug`.

---

## 5. Verification

- `./gradlew test lint assembleDebug` green (unit-testable layers).
- `./gradlew assembleRelease` compiles with `DIAGNOSTIC_RELAY_ENABLED=false`.
- Manual/instrumented: trigger a request, confirm `diagnostics.jsonl` grows with
  the relay offline; Export logs → pick Downloads/Drive → the `.zip` unzips to
  the expected `.jsonl` file(s) with the entries; Reset app empties the store.
- Privacy check: grep the export for token/key/pairing-URI material — must be
  absent.

---

## 6. Files touched

| File | Change |
|---|---|
| `networking/DiagnosticLogStore.kt` | new interface + `FileDiagnosticLogStore` |
| `networking/DiagnosticLogSink.kt` | local fan-out, relay gating, no-drop |
| `di/DataModule.kt` | provide store + flag; sink/reset wiring |
| `app/build.gradle.kts` | `DIAGNOSTIC_RELAY_ENABLED` per build type |
| `ui/AppViewModel.kt` | `exportDiagnostics(uri)` |
| `ui/home/HomeScreen.kt` | Export logs button + SAF launcher |
| `pairing/AppResetManager.kt` | clear diagnostics on reset |
| `playstore/PLAN.md`, `playstore/privacy-policy.md` | accurate local-persistence wording |
| `UI_TESTER_GUIDE.md` | export button + expected file |
| tests | `FileDiagnosticLogStoreTest`, `DiagnosticLogSinkTest`, `AppResetManagerTest`, ViewModel test |

---

## 7. Risks / notes

- **Main-thread I/O:** route all file work through `Dispatchers.IO`; keep the
  write path allocation-light (no per-call JSON pretty printing).
- **Rotation race:** appends can arrive from `Dispatchers.Default`
  (`BridgePipeline`) and the main thread (`MainActivity`); guard with a single
  lock in the file store.
- **Provider variance:** some SAF providers reject `application/zip`; use
  `CreateDocument` with a `.zip` suggestion and fall back to
  `application/octet-stream` if needed.
- **Release build:** since release has no relay publish, an unpaired/release
  build still logs locally — this is intended and must be reflected in the
  privacy policy ("release/Play builds store diagnostics on-device only").

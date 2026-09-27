# Emulator Development Environment — Installed State & Cleanup

This document records everything that was installed/configured to run the
**Phase 12 Emulator E2E** (real APK against live Centrifugo + `mock-daemon` on a
headless Android emulator). Use the Cleanup section to fully revert.

As-of: 2026-09-27 (host `gary`, user `andre`, x86_64, Debian).

## What was installed / created

### 1. Android SDK packages (via `sdkmanager`)
| Package | Size | Installed to |
|---------|------|--------------|
| `emulator` | ~821 MB | `$HOME/Android/Sdk/emulator/` |
| `system-images;android-34;google_apis;x86_64` | ~4.2 GB | `$HOME/Android/Sdk/system-images/android-34/google_apis/x86_64/` |

All SDK package licenses were accepted (`sdkmanager --licenses`).

These were **pre-existing** (not installed by us): `cmdline-tools/latest`
(`sdkmanager`, `avdmanager`), `platform-tools` (`adb`), `platforms;android-34`,
`build-tools`, JDK 21.

### 2. AVD
- Name: **`fido2`** — device profile `pixel_5`, system image
  `system-images;android-34;google_apis;x86_64`
- Location: `$HOME/.android/avd/` (`fido2.avd/` + `fido2.ini`)

### 3. System / group changes
- User `andre` was added to the **`kvm`** group
  (`sudo gpasswd -a andre kvm`) so the emulator can use `/dev/kvm`
  (hardware acceleration). The emulator is launched via
  `sg kvm -c "<emulator ...>"` to apply the group to the current session
  without re-login.

### 4. Running processes
- Headless emulator for AVD `fido2`, running as qemu
  (`qemu-system-x86_64-headless`). Advertised as `emulator-5554`.
  Launch flags: `-no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect`.
  Log: `/tmp/emulator.log`.

### 5. Device (emulator) configuration
- Screen lock PIN set to **`1234`** (`adb shell locksettings set-pin 1234`).
- Virtual fingerprint verified responsive via `adb emu finger touch 1`.
- Note: fingerprint enrollment status is unconfirmed; if `finger touch 1` does
  not satisfy a `BiometricPrompt`, use the PIN fallback path.

### 6. Temporary files
| Path | Purpose |
|------|---------|
| `/tmp/emulator.log` | emulator boot log |
| `/tmp/sdk_install.log` | sdkmanager install log |
| `/tmp/sdk_licenses.log` | license-acceptance log |
| `/tmp/mock_daemon_run.log` | mock-daemon E2E log |
| `/tmp/fido2_harness_env.sh` | earlier JVM-harness env (Phase 11) |
| `/tmp/fido2_harness.properties` | JVM-harness config (deleted; regenerated per-run) |

## Cleanup

Run in order. Everything below fully reverts the environment.

```bash
SDK="$HOME/Android/Sdk"

# 1. Stop the emulator
adb -s emulator-5554 emu kill || kill $(pgrep -f 'qemu-system-x86_64-headless.*-avd fido2')

# 2. Delete the AVD
"$SDK/cmdline-tools/latest/bin/avdmanager" delete avd -n fido2
rm -rf "$HOME/.android/avd/fido2.avd" "$HOME/.android/avd/fido2.ini"

# 3. Uninstall SDK packages (skip to keep the ~5 GB for future use)
"$SDK/cmdline-tools/latest/bin/sdkmanager" --uninstall "emulator" "system-images;android-34;google_apis;x86_64"
rm -rf "$SDK/emulator" "$SDK/system-images"

# 4. Remove the kvm group membership (optional; harmless to keep for future emulator use)
sudo gpasswd -d andre kvm

# 5. Remove temp files
rm -f /tmp/emulator.log /tmp/sdk_install.log /tmp/sdk_licenses.log \
      /tmp/mock_daemon_run.log /tmp/fido2_harness_env.sh /tmp/fido2_harness.properties
```

### Notes
- `/dev/kvm` and the `kvm` group were **pre-existing** (only `andre`'s
  membership is new).
- If you plan to run the emulator again, skip steps 3–4 (they save a re-download
  and a `gpasswd` round-trip).
- The emulator AVD keeps a running config under `~/.android/avd/`; deleting the
  AVD (step 2) does not remove the SDK packages.
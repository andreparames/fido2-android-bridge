"""Thin device driver: uiautomator2 for UI, raw adb for the rest.

The emulator harness drives the real app through ``uiautomator2`` (a
maintained wrapper around adb + an on-device accessibility test server, the
ATX agent). Raw adb is kept only where uiautomator2 has no reach: boot wait,
APK install, and the emulator-console fingerprint command (``adb emu
finger touch``). Element selectors mirror ``UI_TESTER_GUIDE.md`` §4.
"""

from __future__ import annotations

import logging
import shlex
import subprocess
import time

import uiautomator2 as u2

logger = logging.getLogger(__name__)

APP_PACKAGE = "com.fidobridge.client"

PROMPT_TITLE = "WebAuthn sign-in"
HOME_TITLE = "Recent requests"
PENDING_TEXT = "Waiting for your approval"
ACCEPTED_TEXT = "Accepted"
DEFAULT_PIN = "1234"


class DeviceError(RuntimeError):
    """Raised when a device or UI automation step fails."""


class Device:
    def __init__(self, serial: str | None = None, boot_timeout: float = 240.0) -> None:
        """Wait for boot and connect UI automation to the selected adb device.

        An empty or omitted serial selects ``emulator-5554``. ``boot_timeout``
        is in seconds for each boot-wait phase; boot and connection errors propagate.
        """
        self.serial = serial or "emulator-5554"
        self._adb = ["adb", "-s", self.serial]
        self.wait_booted(boot_timeout)
        self.d = u2.connect(self.serial)
        logger.info("uiautomator2 connected to %s", self.serial)

    def adb(self, *args: str) -> str:
        """Run a raw adb command; return stdout without trailing newlines.

        Raise DeviceError on a nonzero exit or after 180 seconds. Process-launch
        errors, including FileNotFoundError when adb is missing, propagate.
        """
        try:
            proc = subprocess.run(
                self._adb + list(args), capture_output=True, text=True, timeout=180
            )
        except subprocess.TimeoutExpired as exc:
            raise DeviceError(f"adb {' '.join(args)} timed out") from exc
        if proc.returncode != 0:
            raise DeviceError(f"adb {' '.join(args)} failed: {proc.stderr.strip()}")
        return proc.stdout.rstrip("\n")

    def wait_booted(self, timeout: float = 240.0) -> None:
        """Wait for adb connectivity, then Android's boot-completed flag.

        ``timeout`` is in seconds and applies separately to both phases; each
        boot-property query also has the adb command timeout. Raise DeviceError
        on command failure or timeout; process-launch errors propagate.
        """
        try:
            subprocess.run(
                ["adb", "-s", self.serial, "wait-for-device"], check=True, timeout=timeout
            )
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as exc:
            raise DeviceError(f"adb wait-for-device failed: {exc}") from exc
        deadline = time.time() + timeout
        while time.time() < deadline:
            if self.adb("shell", "getprop", "sys.boot_completed").strip() == "1":
                logger.info("device booted: %s", self.serial)
                return
            time.sleep(1.0)
        raise DeviceError(f"device {self.serial} did not finish booting within {timeout}s")

    def prepare(self, pin: str = DEFAULT_PIN) -> None:
        """Lock-screen PIN + stay-awake + unlock the keyguard.

        A lock screen must exist for ``BiometricPrompt`` (device-credential
        fallback); ``svc power stayon true`` keeps the screen on during the
        prompt (UI_TESTER_GUIDE.md §2). Setting the PIN leaves the secure
        keyguard up, so it is unlocked here before the app is launched.
        """
        self.shell(f"locksettings set-pin {pin}")
        self.shell("svc power stayon true")
        self.unlock(pin)

    def unlock(self, pin: str = DEFAULT_PIN) -> None:
        """Attempt to dismiss a secure keyguard by entering the lock PIN via its UI.

        Do nothing if the keyguard is not showing. A keyguard that remains
        visible after the attempt does not itself raise an error.
        """
        if "isKeyguardShowing=true" not in self.shell("dumpsys window"):
            logger.info("keyguard not showing; no unlock needed")
            return
        self.shell("wm dismiss-keyguard")
        self.shell("input keyevent 82")
        self.shell("input swipe 540 1800 540 500 200")
        time.sleep(1.0)
        self.shell(f"input text {pin}")
        self.shell("input keyevent 66")
        time.sleep(1.0)
        if "isKeyguardShowing=true" in self.shell("dumpsys window"):
            logger.warning("keyguard still showing after PIN unlock attempt")
        else:
            logger.info("keyguard dismissed")

    def tap_allow(self) -> None:
        """Grant the POST_NOTIFICATIONS dialog if it is showing."""
        allow = self.d(text="Allow")
        if allow.exists(timeout=3.0):
            allow.click()
            time.sleep(1.0)
            logger.info("granted POST_NOTIFICATIONS")

    def shell(self, command: str) -> str:
        """Run a device shell command and return its output without checking exit status."""
        result = self.d.shell(command)
        return getattr(result, "output", str(result))

    def install_apk(self, apk_path: str) -> None:
        """Install or replace the app from a host APK path; propagate adb errors."""
        self.adb("install", "-r", apk_path)
        logger.info("installed %s", apk_path)

    def clear_app(self) -> None:
        """Clear the app's data, including pairing state and runtime permissions."""
        self.d.app_clear(APP_PACKAGE)

    def grant_notification(self) -> None:
        """Pre-grant POST_NOTIFICATIONS so the first-launch dialog never shows.

        ``pm clear`` revokes runtime permissions, so this runs after
        ``clear_app``. Tapping the system dialog is racy; a direct grant is
        deterministic.
        """
        self.adb("shell", "pm", "grant", APP_PACKAGE, "android.permission.POST_NOTIFICATIONS")

    def launch_app(self) -> None:
        """Launch the app without first stopping an existing instance."""
        self.d.app_start(APP_PACKAGE, stop=False)

    def relaunch_app(self) -> None:
        """Restart the app so the relay performs a fresh Noise handshake."""
        self.d.app_stop(APP_PACKAGE)
        time.sleep(1.0)
        self.launch_app()
        time.sleep(1.0)

    def stop_app(self) -> None:
        """Force-stop the app on the selected device."""
        self.d.app_stop(APP_PACKAGE)

    def pair(self, uri: str) -> None:
        """Open the pairing deep link without waiting for pairing to complete."""
        # The URI carries `&`/`?`/`=`; pass it through the device shell
        # single-quoted so the shell does not split it (UI_TESTER_GUIDE.md §3).
        self.shell("am start -a android.intent.action.VIEW -d " + shlex.quote(uri))

    def touch_fingerprint(self) -> None:
        """Simulate a touch from emulator fingerprint ID 1; propagate adb errors."""
        self.adb("emu", "finger", "touch", "1")

    def press_back(self) -> None:
        """Send the Android Back action to the device."""
        self.d.press("back")

    def tap(self, x: int, y: int) -> None:
        """Tap screen coordinates in pixels using adb; propagate adb errors."""
        self.adb("shell", "input", "tap", str(x), str(y))

    def approve_pin(self, pin: str = DEFAULT_PIN) -> None:
        """Device-credential fallback: tap 'Use PIN' on the prompt, type the PIN."""
        use_pin = self.d(text="Use PIN")
        if use_pin.exists(timeout=5.0):
            use_pin.click()
            time.sleep(1.0)
        self.shell(f"input text {pin}")
        self.shell("input keyevent 66")

    def reset_app(self) -> None:
        """Tap 'Reset app' in the danger zone and confirm; return on the pairing screen.

        Raise DeviceError if a reset control or the resulting pairing screen
        is missing. UI automation errors propagate.
        """
        for _ in range(3):
            self.d.swipe(540, 1800, 540, 500, duration=0.2)
        reset = self.d(text="Reset app")
        if not reset.exists(timeout=5.0):
            raise DeviceError("'Reset app' button not found on Home")
        reset.click()
        confirm = self.d(text="Reset")
        if not confirm.exists(timeout=5.0):
            raise DeviceError("reset confirmation dialog did not appear")
        confirm.click()
        # The pairing screen's title is not exposed to accessibility; match the
        # subtitle text instead.
        if not self.wait_text("Scan the pairing code from your computer to connect.", timeout=20.0):
            raise DeviceError("app did not return to the pairing screen after reset")

    def clear_requests(self) -> bool:
        """Tap 'Clear' on Home if present; return whether the empty state appears.

        Clearing is immediate (the undo snackbar was removed upstream).
        Return False if 'No requests yet' is absent after a 10-second wait.
        """
        clear = self.d(text="Clear")
        if clear.exists(timeout=5.0):
            clear.click()
        return self.wait_text("No requests yet", timeout=10.0)

    # --- selectors (elements per UI_TESTER_GUIDE.md §4) ---------------------

    def wait_text(self, text: str, timeout: float = 30.0) -> bool:
        """Wait up to ``timeout`` seconds for exact text; return False on timeout."""
        return self.d(text=text).wait(timeout=timeout)

    def wait_desc_contains(self, desc: str, timeout: float = 30.0) -> bool:
        """Wait up to ``timeout`` seconds for a content-description substring.

        Return whether a matching element appeared before the timeout.
        """
        return self.d(descriptionContains=desc).wait(timeout=timeout)

    # --- diagnostics ----------------------------------------------------------

    def window_screenshot(self, path: str) -> None:
        """Capture the emulator window via the emulator console.

        ``adb emu screenrecord screenshot`` grabs the raw framebuffer on the
        host, so it sees FLAG_SECURE surfaces (e.g. the BiometricPrompt) that
        Android's ``screencap`` renders black.
        """
        self.adb("emu", "screenrecord", "screenshot", path)

    def dump_hierarchy(self, path: str) -> None:
        """Write the UI hierarchy as UTF-8, replacing ``path`` on the host.

        File I/O and UI automation errors propagate.
        """
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(self.d.dump_hierarchy())
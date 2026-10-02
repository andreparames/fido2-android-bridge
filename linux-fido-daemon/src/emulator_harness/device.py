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
        self.serial = serial or "emulator-5554"
        self._adb = ["adb", "-s", self.serial]
        self.wait_booted(boot_timeout)
        self.d = u2.connect(self.serial)
        logger.info("uiautomator2 connected to %s", self.serial)

    def adb(self, *args: str) -> str:
        """Run a raw adb command; return stdout without the trailing newline."""
        proc = subprocess.run(self._adb + list(args), capture_output=True, text=True)
        if proc.returncode != 0:
            raise DeviceError(f"adb {' '.join(args)} failed: {proc.stderr.strip()}")
        return proc.stdout.rstrip("\n")

    def wait_booted(self, timeout: float = 240.0) -> None:
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
        """Dismiss a secure keyguard by entering the lock PIN via its UI."""
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
        result = self.d.shell(command)
        return getattr(result, "output", str(result))

    def install_apk(self, apk_path: str) -> None:
        self.adb("install", "-r", apk_path)
        logger.info("installed %s", apk_path)

    def clear_app(self) -> None:
        self.d.app_clear(APP_PACKAGE)

    def grant_notification(self) -> None:
        """Pre-grant POST_NOTIFICATIONS so the first-launch dialog never shows.

        ``pm clear`` revokes runtime permissions, so this runs after
        ``clear_app``. Tapping the system dialog is racy; a direct grant is
        deterministic.
        """
        self.adb("shell", "pm", "grant", APP_PACKAGE, "android.permission.POST_NOTIFICATIONS")

    def launch_app(self) -> None:
        self.d.app_start(APP_PACKAGE, stop=False)

    def relaunch_app(self) -> None:
        """Restart the app so the relay performs a fresh Noise handshake."""
        self.d.app_stop(APP_PACKAGE)
        time.sleep(1.0)
        self.launch_app()
        time.sleep(1.0)

    def stop_app(self) -> None:
        self.d.app_stop(APP_PACKAGE)

    def pair(self, uri: str) -> None:
        # The URI carries `&`/`?`/`=`; pass it through the device shell
        # single-quoted so the shell does not split it (UI_TESTER_GUIDE.md §3).
        self.shell("am start -a android.intent.action.VIEW -d " + shlex.quote(uri))

    def touch_fingerprint(self) -> None:
        self.adb("emu", "finger", "touch", "1")

    def press_back(self) -> None:
        self.d.press("back")

    def tap(self, x: int, y: int) -> None:
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
        """Tap 'Reset app' in the danger zone and confirm; returns on the pairing screen."""
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
        """Tap 'Clear' on Home; return True once the list is empty.

        Clearing is immediate (the undo snackbar was removed upstream).
        """
        clear = self.d(text="Clear")
        if clear.exists(timeout=5.0):
            clear.click()
        return self.wait_text("No requests yet", timeout=10.0)

    # --- selectors (elements per UI_TESTER_GUIDE.md §4) ---------------------

    def wait_text(self, text: str, timeout: float = 30.0) -> bool:
        return self.d(text=text).wait(timeout=timeout)

    def wait_desc_contains(self, desc: str, timeout: float = 30.0) -> bool:
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
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(self.d.dump_hierarchy())
"""Emulator E2E orchestrator.

Boots (optionally) the AVD, builds/installs the real debug APK (built with
the harness relay URL ``ws://10.0.2.2:9000``), pairs it via a deep link, and
drives the full WebAuthn loop against ``mock_daemon`` (real Centrifugo + real
Noise responder) using ``uiautomator2`` for UI automation.

Run from the daemon venv (installed with ``pip install -e ".[dev]"``):

    python -m emulator_harness all

See ``EMULATOR_E2E_TESTING.md`` (repo root) for the runbook.
"""

from __future__ import annotations

import argparse
import logging
import os
import subprocess
import sys
import tempfile
import time
from pathlib import Path

from emulator_harness.device import (
    PENDING_TEXT,
    PROMPT_TITLE,
    Device,
    DeviceError,
)
from emulator_harness.material import generate_material

logger = logging.getLogger(__name__)

DEFAULT_RELAY = "ws://10.0.2.2:9000/connection/websocket"
DEFAULT_HOST_RELAY = "ws://localhost:9000/connection/websocket"
DEFAULT_TIMEOUT = 300.0
REQUEST_DELAY = 8.0
DEFAULT_AVD = "fido2"
DEFAULT_SERIAL = "emulator-5554"
RETRIES = 5
CONNECT_NEEDLE = "connected to relay channel"
RP_ID = "example.com"


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="emulator-harness")
    parser.add_argument(
        "scenario",
        choices=["get-assertion", "make-credential", "multi", "reset", "all"],
        nargs="?",
        default="all",
        help="which scenario to run (default: all)",
    )
    parser.add_argument("--apk", help="prebuilt APK path (skips the gradle build)")
    parser.add_argument(
        "--emulator",
        default=DEFAULT_AVD,
        help="AVD name to launch (with --launch-emulator)",
    )
    parser.add_argument(
        "--serial",
        default=DEFAULT_SERIAL,
        help="adb device serial (default: emulator-5554)",
    )
    parser.add_argument(
        "--launch-emulator",
        action="store_true",
        help="boot the AVD headless (otherwise an emulator must already run)",
    )
    parser.add_argument(
        "--relay",
        default=DEFAULT_RELAY,
        help="Centrifugo endpoint baked into the APK (default: %(default)s)",
    )
    parser.add_argument(
        "--host-relay",
        default=DEFAULT_HOST_RELAY,
        help="Centrifugo endpoint for the host-side mock_daemon "
        "(default: %(default)s)",
    )
    parser.add_argument(
        "--timeout",
        type=float,
        default=DEFAULT_TIMEOUT,
        help="per-scenario request timeout in seconds (default: %(default)s)",
    )
    parser.add_argument(
        "--count",
        type=int,
        default=5,
        help="number of get-assertion requests for the 'multi' flow (default: 5)",
    )
    parser.add_argument(
        "--reject-indices",
        default="3",
        help="1-based indices of requests to reject for 'multi', "
        "comma-separated (default: 3)",
    )
    parser.add_argument(
        "--skip-clear",
        action="store_true",
        help="skip the clear-requests step in 'all'",
    )
    parser.add_argument(
        "--skip-reset",
        action="store_true",
        help="skip the reset-and-repair step in 'all'",
    )
    parser.add_argument(
        "--keep-running",
        action="store_true",
        help="leave the emulator and mock_daemon running after the run",
    )
    parser.add_argument(
        "--static-key-path",
        default="/tmp/emulator_harness_static.pem",
        help="where to write the daemon static key",
    )
    parser.add_argument(
        "--log-dir",
        help="artifact directory (default: a fresh temp dir)",
    )
    parser.add_argument("--verbose", action="store_true")
    return parser


def _parse_reject_indices(value: str) -> set[int]:
    if not value.strip():
        return set()
    return {int(part) for part in value.split(",") if part.strip()}


def build_plan(args: argparse.Namespace) -> list[tuple[str, dict]]:
    """Ordered list of ``(step, params)`` for the run."""
    if args.scenario == "all":
        steps: list[tuple[str, dict]] = [
            ("make-credential", {"count": 1, "reject": set()}),
            ("clear", {}),
            ("get-assertion", {"count": 1, "reject": set()}),
            ("multi", {"count": args.count, "reject": _parse_reject_indices(args.reject_indices)}),
        ]
        if not args.skip_reset:
            steps.append(("reset", {}))
        return steps
    if args.scenario == "multi":
        return [("multi", {"count": args.count, "reject": _parse_reject_indices(args.reject_indices)})]
    if args.scenario == "reset":
        return [("reset", {})]
    # single request scenarios (make-credential / get-assertion)
    return [(args.scenario, {"count": 1, "reject": set()})]


def _repo_root() -> Path:
    override = os.environ.get("FIDO2_REPO_ROOT")
    if override:
        return Path(override)
    for parent in Path(__file__).resolve().parents:
        if (parent / "android-fido-client").is_dir() and (
            parent / "linux-fido-daemon"
        ).is_dir():
            return parent
    raise DeviceError("cannot locate the repo root; set FIDO2_REPO_ROOT")


def _read_log(path: str) -> str:
    try:
        return Path(path).read_text()
    except OSError:
        return ""


def _wait_for_log(log_path: str, needle: str, timeout: float) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        if needle in _read_log(log_path):
            return True
        time.sleep(0.5)
    return False


def _find_emulator() -> str:
    candidates = [
        os.environ.get("ANDROID_HOME"),
        os.environ.get("ANDROID_SDK_ROOT"),
        str(Path.home() / "Android" / "Sdk"),
    ]
    for root in candidates:
        if not root:
            continue
        binary = Path(root) / "emulator" / "emulator"
        if binary.is_file():
            return str(binary)
    return "emulator"


def _start_emulator(avd: str, log_path: str) -> subprocess.Popen:
    cmd = [
        _find_emulator(),
        "-avd",
        avd,
        "-no-window",
        "-no-audio",
        "-no-boot-anim",
        "-no-snapshot",
        "-gpu",
        "swiftshader_indirect",
    ]
    log_fh = open(log_path, "wb")
    try:
        proc = subprocess.Popen(cmd, stdout=log_fh, stderr=subprocess.STDOUT)
    except FileNotFoundError as exc:
        log_fh.close()
        raise DeviceError(
            f"emulator binary not found ({cmd[0]}); install the Android SDK emulator"
        ) from exc
    logger.info("emulator starting: %s", " ".join(cmd))
    return proc


def _build_apk(android_dir: Path, relay_url: str) -> str:
    env = dict(os.environ)
    env["FIDO2_RELAY_URL"] = relay_url
    proc = subprocess.run(
        ["./gradlew", "assembleDebug"],
        cwd=android_dir,
        env=env,
        capture_output=True,
        text=True,
    )
    if proc.returncode != 0:
        raise DeviceError(
            f"gradle assembleDebug failed:\n{proc.stdout}\n{proc.stderr}"
        )
    apk = android_dir / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
    if not apk.is_file():
        raise DeviceError(f"APK not produced: {apk}")
    return str(apk)


def _snapshot(device: Device, log_dir: str, name: str) -> None:
    """Best-effort emulator-window screenshot (never fails the run).

    Uses the emulator console so FLAG_SECURE surfaces (the BiometricPrompt)
    are captured instead of rendered black.
    """
    try:
        device.window_screenshot(os.path.join(log_dir, name))
    except Exception as exc:  # noqa: BLE001 - diagnostics are best-effort
        logger.warning("screenshot %s failed: %s", name, exc)


def _hierarchy(device: Device, log_dir: str, tag: str) -> str:
    path = os.path.join(log_dir, f"{tag}-fail.xml")
    try:
        device.dump_hierarchy(path)
        return Path(path).read_text()[:4000]
    except Exception:
        return "(hierarchy unavailable)"


def _await_request(device: Device, log_dir: str, tag: str, timeout: float) -> None:
    if device.wait_text(PROMPT_TITLE, timeout=timeout):
        if not device.wait_text(RP_ID, timeout=10.0):
            raise DeviceError(
                f"{tag}: prompt shown but rpId {RP_ID!r} missing:\n"
                f"{_hierarchy(device, log_dir, tag)}"
            )
        logger.info("%s: BiometricPrompt shown for %s", tag, RP_ID)
        _snapshot(device, log_dir, f"{tag}-prompt.png")
        return
    if device.wait_text(PENDING_TEXT, timeout=timeout):
        logger.info("%s: pending row shown (prompt not reachable via accessibility)", tag)
        return
    raise DeviceError(
        f"{tag}: request did not surface (no {PROMPT_TITLE!r}, no {PENDING_TEXT!r}); "
        f"hierarchy:\n{_hierarchy(device, log_dir, tag)}"
    )


def _approve(device: Device, log_dir: str, tag: str) -> None:
    # The outcome lives in the merged row content-desc (e.g. "Sign-in request
    # from example.com, accepted, just now"), not a standalone "Accepted" text
    # node (UI_TESTER_GUIDE.md §4.2). The fingerprint sensor may not be ready
    # the instant the prompt appears, so retry while the prompt is still
    # showing; stop as soon as it is gone (the next request may already be
    # queued, and a stray touch would approve it instead).
    for attempt in range(1, 4):
        device.touch_fingerprint()
        if device.wait_desc_contains("accepted", timeout=8.0):
            logger.info("%s: approved via fingerprint (attempt %d)", tag, attempt)
            _snapshot(device, log_dir, f"{tag}-accepted.png")
            return
        if not device.d(text=PROMPT_TITLE).exists(timeout=1.0):
            logger.warning("%s: prompt closed without an accepted row", tag)
            break
        time.sleep(1.0)
    logger.warning("%s: fingerprint did not satisfy the prompt; PIN fallback", tag)
    device.approve_pin()
    if not device.wait_desc_contains("accepted", timeout=45.0):
        raise DeviceError(
            f"{tag}: approval did not complete (no accepted row); "
            f"hierarchy:\n{_hierarchy(device, log_dir, tag)}"
        )
    logger.info("%s: approved via PIN fallback", tag)
    _snapshot(device, log_dir, f"{tag}-accepted.png")


def _finish(proc: subprocess.Popen, log_path: str, ok_line: str, timeout: float) -> None:
    deadline = time.time() + timeout
    while time.time() < deadline:
        rc = proc.poll()
        if rc is not None:
            content = _read_log(log_path)
            if rc != 0:
                raise DeviceError(f"mock_daemon exit {rc}; log:\n{content}")
            if "SECURITY ALERT" in content:
                raise DeviceError(f"mock_daemon log has SECURITY ALERT:\n{content}")
            if ok_line not in content:
                raise DeviceError(f"mock_daemon log missing {ok_line!r}:\n{content}")
            return
        time.sleep(1.0)
    raise DeviceError(
        f"mock_daemon did not finish within {timeout}s; log:\n{_read_log(log_path)}"
    )


def _reject(device: Device, log_dir: str, tag: str) -> None:
    """Dismiss the BiometricPrompt -> CTAP2 operation-denied -> Rejected row.

    The systemui bottom-sheet prompt has no Cancel button; cancel it by
    tapping the scrim above the sheet (raw adb tap) and/or BACK, retrying
    until the prompt is gone. A user-cancel also posts a "FIDO Bridge error"
    dialog (the app surfaces the biometric error), which must be dismissed
    with OK before the rejected row is visible.
    """
    for _ in range(4):
        if not device.d(text=PROMPT_TITLE).exists(timeout=1.0):
            break
        device.tap(540, 800)  # scrim above the sheet
        time.sleep(1.5)
        if not device.d(text=PROMPT_TITLE).exists(timeout=1.0):
            break
        device.press_back()
        time.sleep(1.5)
    ok = device.d(text="OK")
    if ok.exists(timeout=3.0):
        ok.click()
        time.sleep(1.0)
    if not device.wait_desc_contains("rejected", timeout=5.0):
        raise DeviceError(
            f"{tag}: rejection did not register (no rejected row); "
            f"hierarchy:\n{_hierarchy(device, log_dir, tag)}"
        )
    logger.info("%s: rejected", tag)
    _snapshot(device, log_dir, f"{tag}-rejected.png")


def _run_request_step(
    device: Device,
    material,
    host_relay: str,
    kind: str,
    count: int,
    reject: set[int],
    timeout: float,
    log_dir: str,
    first: bool,
) -> None:
    scenario = "get-assertion" if kind in ("multi", "get-assertion") else kind
    log_path = os.path.join(log_dir, f"{kind}.log")
    env = dict(os.environ)
    env["FIDO2_CHANNEL_ID"] = material.channel_hex
    env["FIDO2_STATIC_KEY_PATH"] = material.static_key_path
    env["FIDO2_RELAY_URL"] = host_relay
    env["FIDO2_REQUEST_TIMEOUT"] = str(int(timeout))
    cmd = [
        sys.executable,
        "-m",
        "mock_daemon",
        scenario,
        "--count",
        str(count),
        "--delay",
        str(REQUEST_DELAY),
        "--timeout",
        str(int(timeout)),
        "--retries",
        str(RETRIES),
        "--verbose",
    ]
    ok_line = (
        "get-assertion: OK" if scenario == "get-assertion" else "make-credential: OK"
    )
    proc = None
    log_fh = open(log_path, "wb")
    try:
        proc = subprocess.Popen(cmd, env=env, stdout=log_fh, stderr=subprocess.STDOUT)
        if not _wait_for_log(log_path, CONNECT_NEEDLE, timeout=60.0):
            raise DeviceError(
                f"{kind}: mock_daemon did not reach the relay:\n{_read_log(log_path)}"
            )
        if first:
            # Pair via deep link; the service starts on resume and the app
            # immediately performs the Noise handshake with the waiting
            # mock_daemon, which then publishes the first request.
            device.pair(material.pairing_uri)
            device.tap_allow()  # POST_NOTIFICATIONS dialog on first launch
        else:
            # Restart the app so it performs a fresh Noise handshake with the
            # new mock_daemon (responder) instance.
            device.relaunch_app()
        _snapshot(device, log_dir, f"{kind}-0-home.png")
        for index in range(1, count + 1):
            tag = f"{kind}#{index}"
            _await_request(device, log_dir, tag, timeout=timeout)
            if index in reject:
                _reject(device, log_dir, tag)
            else:
                _approve(device, log_dir, tag)
        _finish(proc, log_path, ok_line, timeout=timeout)
    finally:
        if proc is not None and proc.poll() is None:
            proc.kill()
        log_fh.close()
    logger.info("%s: PASS", kind)


def _clear_step(device: Device, log_dir: str) -> None:
    if not device.clear_requests():
        raise DeviceError(
            f"clear: list not empty; hierarchy:\n{_hierarchy(device, log_dir, 'clear')}"
        )
    logger.info("clear: PASS")
    _snapshot(device, log_dir, "clear-done.png")


def _reset_step(device: Device, args: argparse.Namespace, log_dir: str) -> None:
    if not device.d(text="Recent requests").exists(timeout=5.0):
        material = generate_material(args.static_key_path)
        device.pair(material.pairing_uri)
        device.tap_allow()
        if not device.wait_text("Recent requests", timeout=40.0):
            raise DeviceError("reset: app not on Home before reset")
    device.reset_app()
    logger.info("reset: back on pairing screen")
    _snapshot(device, log_dir, "reset-pairing.png")

    material = generate_material(args.static_key_path)
    device.pair(material.pairing_uri)
    device.tap_allow()
    if not device.wait_text("Recent requests", timeout=40.0):
        raise DeviceError(
            f"reset: re-pair did not land on Home; "
            f"hierarchy:\n{_hierarchy(device, log_dir, 'reset')}"
        )
    logger.info("reset: re-paired on Home")
    _snapshot(device, log_dir, "reset-repaired-home.png")


def _run(args: argparse.Namespace) -> int:
    log_dir = args.log_dir or tempfile.mkdtemp(prefix="emulator-e2e-")
    Path(log_dir).mkdir(parents=True, exist_ok=True)
    logger.info("artifacts in %s", log_dir)

    emulator_proc = None
    if args.launch_emulator:
        emulator_proc = _start_emulator(
            args.emulator, os.path.join(log_dir, "emulator.log")
        )

    device = None
    try:
        device = Device(args.serial)
        device.prepare()

        apk = args.apk or _build_apk(_repo_root() / "android-fido-client", args.relay)
        device.install_apk(apk)
        device.clear_app()
        device.grant_notification()  # pm clear revokes it; grant before first launch

        material = generate_material(args.static_key_path)
        first_request = True
        for kind, params in build_plan(args):
            if kind == "reset":
                _reset_step(device, args, log_dir)
            elif kind == "clear":
                _clear_step(device, log_dir)
            else:
                _run_request_step(
                    device,
                    material,
                    args.host_relay,
                    kind,
                    params["count"],
                    params["reject"],
                    args.timeout,
                    log_dir,
                    first=first_request,
                )
                first_request = False
        return 0
    finally:
        if device is not None and not args.keep_running:
            device.stop_app()
        if emulator_proc is not None and not args.keep_running:
            emulator_proc.terminate()


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )
    try:
        return _run(args)
    except DeviceError as exc:
        logger.error("%s", exc)
        return 1
    except Exception:
        logger.exception("emulator harness failed")
        return 1


if __name__ == "__main__":
    sys.exit(main())
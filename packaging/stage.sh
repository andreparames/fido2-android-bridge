#!/usr/bin/env bash
set -euo pipefail

# Build the app tree + staging tree consumed by the deb/rpm builders
# (packaging/PLAN.md §2/§4).
#
# We do NOT bundle a Python interpreter. The package runs on the distro's
# `python3` (>= 3.11) via a small wrapper, with pure-Python + abi3 wheels under
# /usr/lib/fido-daemon/pylib. This keeps ONE package usable across distros and
# across python 3.11/3.12/3.13/3.14 (no `libpython` pin, which previously made
# the rpm uninstallable on CentOS Stream 10 / newer Fedora).
#
# Native modules kept must be abi3 (stable ABI). cffi is dropped and provided
# by the distro (python3-cffi): cryptography's Rust module imports
# `_cffi_backend` at load, but cffi's _cffi_backend is Python-minor-specific, so
# it cannot be bundled. websockets' C speedups are optional (pure fallback).

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SELF_DIR/.." && pwd)"
DAEMON_DIR="$REPO_ROOT/linux-fido-daemon"
STAGE="$SELF_DIR/stage"
PYLIB="$STAGE/usr/lib/fido-daemon/pylib"

PYTHON_BIN="${PYTHON_BIN:-python3}"
PIP_INDEX_URL="${PIP_INDEX_URL:-https://pypi.org/simple}"

rm -rf "$STAGE"
mkdir -p \
  "$PYLIB" \
  "$STAGE/usr/bin" \
  "$STAGE/usr/lib/systemd/system" \
  "$STAGE/usr/lib/udev/rules.d" \
  "$STAGE/etc/fido-daemon" \
  "$STAGE/usr/share/doc/fido-daemon"

echo "[stage] installing app + deps (--target) with $PYTHON_BIN"
BUILDER="$(mktemp -d)"
"$PYTHON_BIN" -m venv "$BUILDER"
"$BUILDER/bin/pip" install -q --upgrade pip
"$BUILDER/bin/pip" install -q --index-url "$PIP_INDEX_URL" --target "$PYLIB" "$DAEMON_DIR"
rm -rf "$BUILDER" "$PYLIB/bin"   # console scripts replaced by our wrapper

# Keep only version-independent native code.
rm -rf "$PYLIB"/cffi "$PYLIB"/cffi-*.dist-info "$PYLIB"/_cffi_backend*.so
rm -rf "$PYLIB"/pycparser "$PYLIB"/pycparser-*.dist-info
rm -f  "$PYLIB"/websockets/speedups*.so

# Guard: only abi3 native modules may remain.
if find "$PYLIB" -name '*.so' ! -name '*.abi3.so' | grep -q .; then
    echo "[stage] ERROR: non-abi3 native module present (would pin a python minor):" >&2
    find "$PYLIB" -name '*.so' ! -name '*.abi3.so' >&2
    exit 1
fi

# Drop exec bits on data files (no console scripts are shipped; the wrapper is
# created below).
find "$PYLIB" -type f -exec chmod -x {} + 2>/dev/null || true

# Wrapper: run the distro python3 against the bundled pylib.
cat > "$STAGE/usr/bin/fido-daemon" <<'WRAP'
#!/bin/sh
PYTHONPATH=/usr/lib/fido-daemon/pylib exec /usr/bin/python3 -m fido_daemon.cli "$@"
WRAP
chmod 0755 "$STAGE/usr/bin/fido-daemon"

echo "[stage] wiring systemd unit, udev rules, config, docs"
cp "$DAEMON_DIR/systemd/fido-daemon.service"           "$STAGE/usr/lib/systemd/system/"
cp "$DAEMON_DIR/systemd/70-fido2-bridge-uhid.rules"    "$STAGE/usr/lib/udev/rules.d/"
cp "$SELF_DIR/config/fido-daemon.toml.example"         "$STAGE/etc/fido-daemon/"
cp "$DAEMON_DIR/README.md"                             "$STAGE/usr/share/doc/fido-daemon/README.md"
cp "$REPO_ROOT/CHANGELOG.md"                           "$STAGE/usr/share/doc/fido-daemon/CHANGELOG.md"
cp "$DAEMON_DIR/LICENSE"                               "$STAGE/usr/share/doc/fido-daemon/LICENSE"

# Verify with the distro python3 that will actually run it.
PYTHONDONTWRITEBYTECODE=1 PYTHONPATH="$PYLIB" "$PYTHON_BIN" - <<'PY'
import importlib
for module in ("fido_daemon", "fido_daemon.cli", "centrifuge", "cryptography",
               "noise", "segno", "tomlkit", "websockets", "google.protobuf"):
    importlib.import_module(module)
print("[stage] all runtime imports OK")
PY

# No bytecode. Must run LAST: the import check above would otherwise recreate
# __pycache__ (lintian's package-installs-python-pycache-dir on Debian 12).
find "$PYLIB" -type d -name '__pycache__' -exec rm -rf {} + 2>/dev/null || true
find "$PYLIB" -name '*.pyc' -delete 2>/dev/null || true

echo "[stage] staging tree ready at $STAGE"
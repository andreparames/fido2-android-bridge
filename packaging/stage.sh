#!/usr/bin/env bash
set -euo pipefail

# Build the bundled /opt/fido-daemon venv and assemble the staging tree that
# both the .deb and .rpm builders consume (packaging/PLAN.md §4/§9).
#
# The venv is NOT relocatable across Python versions: it links the build
# machine's python3, so build each package on a machine whose python version
# matches the target (CI builds per-distro containers). PYTHON_BIN lets you
# pick an older interpreter, e.g. PYTHON_BIN=python3.11.

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SELF_DIR/.." && pwd)"
DAEMON_DIR="$REPO_ROOT/linux-fido-daemon"
STAGE="$SELF_DIR/stage"

PYTHON_BIN="${PYTHON_BIN:-python3}"
PIP_INDEX_URL="${PIP_INDEX_URL:-https://pypi.org/simple}"

rm -rf "$STAGE"
mkdir -p \
  "$STAGE/usr/lib/fido-daemon/venv" \
  "$STAGE/usr/bin" \
  "$STAGE/usr/lib/systemd/system" \
  "$STAGE/usr/lib/udev/rules.d" \
  "$STAGE/etc/fido-daemon" \
  "$STAGE/usr/share/doc/fido-daemon"

VENV="$STAGE/usr/lib/fido-daemon/venv"

echo "[stage] creating venv with $PYTHON_BIN"
"$PYTHON_BIN" -m venv "$VENV"
"$VENV/bin/pip" install -q --upgrade pip
"$VENV/bin/pip" install -q --index-url "$PIP_INDEX_URL" "$DAEMON_DIR"

# pip is only needed to build the venv, not to run the daemon. Drop it to
# shrink the payload and remove its vendored scripts from lint scans.
"$VENV/bin/pip" uninstall -q -y pip
rm -f "$VENV/bin/pip" "$VENV/bin/pip3" "$VENV/bin/pip3.13"

# Console-script shebangs embed the *build* path of the venv; the venv is
# installed at /usr/lib/fido-daemon/venv, so rewrite them to that canonical
# path. Only touch regular files (bin/python* are symlinks to the system
# python).
for f in "$VENV/bin"/*; do
    [ -f "$f" ] && sed -i \
        "1s|^#!$VENV/bin/python3|#!/usr/lib/fido-daemon/venv/bin/python3|" \
        "$f"
done

# Debian's venv *copies* the interpreter into bin/python3/python3.13 instead
# of symlinking it; keep a single copy (bin/python) and point the others at it
# to avoid shipping ~14MB of duplicate binaries. The copies are identical.
ln -sf python "$VENV/bin/python3"
ln -sf python "$VENV/bin/python3.13"

# Wheels ship files with the executable bit set (protobuf/pip/etc.); those are
# not scripts, so drop exec bits everywhere except bin/ (real console scripts
# with shebangs). This keeps lintian/rpmlint clean.
find "$VENV" -type f ! -path "$VENV/bin/*" -exec chmod -x {} +

# Some wheels also ship a `#!` first line on files that are not meant to be
# run (e.g. segno's cli module). Strip those shebangs so rpmlint does not flag
# them as non-executable scripts. Only applies under lib/ (bin/ keeps its
# console-script shebangs).
find "$VENV/lib" -type f -exec sh -c '
    if head -c2 "$1" | grep -q "^#!"; then sed -i "1d" "$1"; fi
' _ {} \;

echo "[stage] wiring bin, systemd unit, udev rules, config, docs"
ln -s /usr/lib/fido-daemon/venv/bin/fido-daemon "$STAGE/usr/bin/fido-daemon"
rm -f "$VENV/.gitignore"                     # pip venv artifact; not payload
cp "$DAEMON_DIR/systemd/fido-daemon.service"           "$STAGE/usr/lib/systemd/system/"
cp "$DAEMON_DIR/systemd/70-fido2-bridge-uhid.rules"    "$STAGE/usr/lib/udev/rules.d/"
cp "$SELF_DIR/config/fido-daemon.toml.example"         "$STAGE/etc/fido-daemon/"
cp "$DAEMON_DIR/README.md"                             "$STAGE/usr/share/doc/fido-daemon/README.md"
cp "$REPO_ROOT/CHANGELOG.md"                           "$STAGE/usr/share/doc/fido-daemon/CHANGELOG.md"
cp "$DAEMON_DIR/LICENSE"                               "$STAGE/usr/share/doc/fido-daemon/LICENSE"

"$VENV/bin/python" - <<'PY'
import importlib
for module in ("fido_daemon", "centrifuge", "cryptography", "noise", "segno", "tomlkit"):
    importlib.import_module(module)
print("[stage] all runtime imports OK")
PY

# Ship no bytecode: Python regenerates .pyc at runtime, and build-time .pyc
# mtimes trip rpmlint (python-bytecode-inconsistent-mtime on Fedora) and
# lintian (package-installs-python-pycache-dir). Must run AFTER the import
# check above, which would otherwise regenerate __pycache__.
find "$VENV" -type d -name '__pycache__' -exec rm -rf {} + 2>/dev/null || true
find "$VENV" -name '*.pyc' -delete 2>/dev/null || true

echo "[stage] staging tree ready at $STAGE"
#!/usr/bin/env bash
set -euo pipefail

# Locally sign all artifacts in packaging/out with the release key
# (packaging/PLAN.md §9). Uses a throwaway keyring holding the CI-protected
# copy of the key, with the CI passphrase preset into a throwaway gpg-agent,
# so the native signing tools never need an interactive pinentry.
#
# CI does the equivalent with crazy-max/ghaction-import-gpg (see PLAN.md).

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PKG_DIR="$(cd "$SELF_DIR/.." && pwd)"
RELEASE=65E4D9A0F8F86048
# The keyring copy is locked with the master passphrase (the CI passphrase is
# used for the copy that lives in GitHub). Either unlocks the same key.
PASSPHRASE="$(pass show fido-daemon/master-passphrase)"

PASS_FILE="$(mktemp)"; printf '%s' "$PASSPHRASE" > "$PASS_FILE"
SIGN_HOME="$(mktemp -d)"
SECRET_FILE="$(mktemp)"
chmod 700 "$SIGN_HOME"
trap 'rm -rf "$SIGN_HOME" "$PASS_FILE" "$SECRET_FILE"' EXIT

# 1. export the release key secret from the real keyring (locked with master)
gpg --batch --pinentry-mode loopback --passphrase-file "$PASS_FILE" \
    --export-secret-keys "$RELEASE" > "$SECRET_FILE"

# 2. import it into a throwaway keyring + preset the passphrase in a throwaway agent
echo "allow-preset-passphrase" > "$SIGN_HOME/gpg-agent.conf"
export GNUPGHOME="$SIGN_HOME"
gpgconf --kill gpg-agent 2>/dev/null || true
gpg --batch --import "$SECRET_FILE"
KEYGRIP="$(gpg --with-colons --list-secret-keys | awk -F: '/^grp/{print $10; exit}')"
/usr/lib/gnupg/gpg-preset-passphrase --preset "$KEYGRIP" < "$PASS_FILE"
echo "[sign] key $RELEASE loaded in throwaway keyring (keygrip $KEYGRIP)"

# deb
for deb in "$PKG_DIR"/out/deb/*.deb; do
    debsigs --sign=origin --default-key="$RELEASE" "$deb"
    debsigs --verify "$deb" && echo "[sign] debsigs OK: $(basename "$deb")"
done

# rpm
RPM_DB="$SIGN_HOME/rpmdb"
mkdir -p "$RPM_DB"
rpmsign --define "_gpg_name $RELEASE" --define "_signature gpg" \
    --define "_dbpath $RPM_DB" \
    --addsign "$PKG_DIR"/out/rpm/*.rpm 2>&1 | grep -vE 'GPG_TTY|Inappropriate|already contains identical' | tail -3
gpg --export --armor "$RELEASE" > "$SIGN_HOME/pub.asc"
rpm --dbpath "$RPM_DB" --import "$SIGN_HOME/pub.asc" 2>/dev/null || true
for rpm in "$PKG_DIR"/out/rpm/*.rpm; do
    rpm --dbpath "$RPM_DB" --checksig "$rpm" 2>&1 | grep -E 'OK|NOT OK|SIGNATURES'
done
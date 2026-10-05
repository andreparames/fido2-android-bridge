#!/usr/bin/env bash
set -euo pipefail

# One-time release-key provisioning (packaging/PLAN.md §9).
#
# Reads the passphrases from `pass` (your interactive gpg agent/pinentry
# unlocks them), then:
#   1. verifies the release key is locked with the master passphrase
#   2. writes the master-key backup + encrypted revocation cert to
#      ~/.local/share/fido-daemon/backup
#   3. creates the CI copy (distinct passphrase) and sets the GitHub secrets
#   4. restores the master passphrase on the local keyring
#
# Safe to re-run (idempotent). Run it in your normal terminal:
#   bash packaging/scripts/provision-release-key.sh

RELEASE=65E4D9A0F8F86048
MASTER="$(pass show fido-daemon/master-passphrase)"
REVOKE="$(pass show fido-daemon/revoke-passphrase)"
CI="$(pass show fido-daemon/ci-passphrase)"
BACKUP="$HOME/.local/share/fido-daemon/backup"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

need_pass() { # $1 = secret, $2 = label
    if [ -z "$1" ]; then echo "[fail] $2 is empty — check pass fido-daemon/$2"; exit 1; fi
}
need_pass "$MASTER" master-passphrase
need_pass "$REVOKE" revoke-passphrase
need_pass "$CI" ci-passphrase

# 1. verify the release key unlocks with MASTER
gpg --batch --pinentry-mode loopback --passphrase-file <(printf '%s' "$MASTER") \
    --export-secret-keys "$RELEASE" >/dev/null 2>&1 \
    && echo "[ok] release key is locked with the master passphrase" \
    || { echo "[fail] release key not unlocked by master passphrase"; exit 1; }

# 2. backups (overwrites; safe — regenerated from the release key)
mkdir -p "$BACKUP"
REV_CERT="$HOME/.gnupg/openpgp-revocs.d/5594D19A6472A45765F0EB8765E4D9A0F8F86048.rev"
gpg --batch --yes --pinentry-mode loopback --passphrase-file <(printf '%s' "$REVOKE") \
    --symmetric --cipher-algo AES256 -o "$BACKUP/fido-revoke.asc.gpg" "$REV_CERT"
gpg --batch --pinentry-mode loopback --passphrase-file <(printf '%s' "$MASTER") \
    --export-secret-keys "$RELEASE" > "$BACKUP/fido-master.gpg"
echo "[ok] backups written:"
ls -l "$BACKUP"

# 3. CI copy: swap to CI passphrase, export, restore master
printf '%s\n%s\n%s\nsave\nquit\n' "$MASTER" "$CI" "$CI" |
    gpg --batch --pinentry-mode loopback --command-fd 0 --edit-key "$RELEASE" passwd >/dev/null 2>&1
gpg --batch --armor --pinentry-mode loopback --passphrase-file <(printf '%s' "$CI") \
    --export-secret-keys "$RELEASE" | base64 -w0 > /tmp/ci-key.b64
printf '%s\n%s\n%s\nsave\nquit\n' "$CI" "$MASTER" "$MASTER" |
    gpg --batch --pinentry-mode loopback --command-fd 0 --edit-key "$RELEASE" passwd >/dev/null 2>&1
gpg --batch --pinentry-mode loopback --passphrase-file <(printf '%s' "$MASTER") \
    --export-secret-keys "$RELEASE" >/dev/null 2>&1 \
    && echo "[ok] master passphrase restored on the keyring" \
    || { echo "[fail] could not restore master passphrase — re-run this script"; exit 1; }

gh secret set RELEASE_GPG_PRIVATE_KEY --repo gatebridgeapp/fido2-android-bridge < /tmp/ci-key.b64
printf '%s' "$CI" | gh secret set RELEASE_GPG_PASSPHRASE --repo gatebridgeapp/fido2-android-bridge
rm -f /tmp/ci-key.b64
echo "[ok] GitHub secrets set (RELEASE_GPG_PRIVATE_KEY, RELEASE_GPG_PASSPHRASE)"

echo
echo "== backup checklist (move off-machine) =="
echo "  $BACKUP/fido-revoke.asc.gpg   (decrypt with: pass fido-daemon/revoke-passphrase)"
echo "  $BACKUP/fido-master.gpg       (unlock with: pass fido-daemon/master-passphrase)"
echo "  pass fido-daemon/{master,revoke,ci}-passphrase"
echo
echo "fingerprint: 5594D19A6472A45765F0EB8765E4D9A0F8F86048"
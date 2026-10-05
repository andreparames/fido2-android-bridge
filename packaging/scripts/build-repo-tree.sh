#!/usr/bin/env bash
set -euo pipefail

# Build the Debian (apt) and RHEL-family (dnf/yum) repository trees served via
# GitHub Pages (packaging/PLAN.md §9.2). Produces:
#
#   <out>/
#     debian/<suite>/          flat apt repo per suite: Packages, Release,
#                              InRelease (signed), *.deb
#     repo/<distro>/<arch>/    dnf repo per distro: *.rpm, repodata/
#     fido-daemon.gpg          release public key (binary, for apt signed-by)
#     RPM-GPG-KEY              release public key (armored, for rpm gpgkey)
#     index.html, .nojekyll    Pages: no autoindex, no Jekyll
#
# Usage:
#   build-repo-tree.sh \
#     --keyid <gpg-keyid>            (optional: sign apt InRelease with this key)
#     --apt <suite> <deb-dir>        (repeatable)
#     --rpm <distro/arch> <rpm-dir>  (repeatable)
#     <out_dir>
#
# Requires: apt-ftparchive (apt-utils), gzip, createrepo_c (createrepo-c), gpg.

SELF_DIR="$(cd "$(dirname "$0")" && pwd)"
usage() { sed -n '2,20p' "$0"; exit 2; }

KEYID=""
APT=()
RPM=()
OUT=""

while [ $# -gt 0 ]; do
    case "$1" in
        --keyid) KEYID="$2"; shift 2 ;;
        --apt)   APT+=("$2"); APT+=("$3"); shift 3 ;;
        --rpm)   RPM+=("$2"); RPM+=("$3"); shift 3 ;;
        --)      shift; OUT="$1"; shift ;;
        -h|--help) usage ;;
        *) OUT="$1"; shift ;;
    esac
done

[ -n "$OUT" ] || usage
command -v apt-ftparchive >/dev/null || { echo "missing apt-ftparchive (apt-utils)" >&2; exit 1; }
command -v createrepo_c >/dev/null || { echo "missing createrepo_c (createrepo-c)" >&2; exit 1; }

rm -rf "$OUT"
mkdir -p "$OUT/debian" "$OUT/repo"

# --- apt: flat repo per suite ---
for ((i = 0; i < ${#APT[@]}; i += 2)); do
    suite="${APT[$i]}"; debdir="${APT[$((i + 1))]}"
    dir="$OUT/debian/$suite"
    mkdir -p "$dir"
    find "$debdir" -maxdepth 1 -name '*.deb' ! -name '*dbgsym*' -exec cp {} "$dir/" \;
    (
        cd "$dir"
        apt-ftparchive packages . > Packages
        gzip -k -9 Packages
        apt-ftparchive \
            -o APT::FTPArchive::Release::Origin="Fido2Android" \
            -o APT::FTPArchive::Release::Label="fido-daemon" \
            -o APT::FTPArchive::Release::Suite="$suite" \
            -o APT::FTPArchive::Release::Codename="$suite" \
            -o APT::FTPArchive::Release::Architectures="amd64" \
            -o APT::FTPArchive::Release::Components="main" \
            -o APT::FTPArchive::Release::Description="fido-daemon packages" \
            release . > Release
        if [ -n "$KEYID" ]; then
            gpg --batch --yes --armor --default-key "$KEYID" --output InRelease --clearsign Release
        else
            echo "WARNING: no --keyid; apt repo for '$suite' left unsigned (use [trusted=yes])" >&2
        fi
    )
    echo "[repo] apt suite '$suite': $(find "$dir" -maxdepth 1 -name '*.deb' | wc -l) packages"
done

# --- rpm: dnf repo per distro/arch ---
for ((i = 0; i < ${#RPM[@]}; i += 2)); do
    name="${RPM[$i]}"; rpmdir="${RPM[$((i + 1))]}"
    dir="$OUT/repo/$name"
    mkdir -p "$dir"
    find "$rpmdir" -maxdepth 1 -name '*.rpm' -exec cp {} "$dir/" \;
    createrepo_c --quiet "$dir"
    echo "[repo] dnf repo '$name': $(find "$dir" -maxdepth 1 -name '*.rpm' | wc -l) packages"
done

# --- public keys ---
gpg --batch --export "$KEYID" > "$OUT/fido-daemon.gpg" 2>/dev/null || \
    gpg --batch --export > "$OUT/fido-daemon.gpg"
gpg --batch --armor --export "$KEYID" > "$OUT/RPM-GPG-KEY" 2>/dev/null || \
    gpg --batch --armor --export > "$OUT/RPM-GPG-KEY"

# --- Pages niceties ---
touch "$OUT/.nojekyll"
echo "packages.gatebridge.app" > "$OUT/CNAME"
install -m 0755 "$SELF_DIR/../install.sh" "$OUT/install.sh"
cat > "$OUT/index.html" <<'EOF'
<!doctype html>
<html><head><meta charset="utf-8"><title>fido-daemon package repository</title></head>
<body>
<h1>fido-daemon package repository</h1>
<p>One-line install (auto-detects the distribution):</p>
<pre>curl -fsSL https://packages.gatebridge.app/install.sh | sudo sh</pre>
<p>Debian/Ubuntu: <code>deb [signed-by=fido-daemon.gpg] https://packages.gatebridge.app/debian/&lt;suite&gt;/ ./</code></p>
<p>RHEL-family: add a repo with <code>baseurl=https://packages.gatebridge.app/repo/&lt;distro&gt;/&lt;arch&gt;/</code> and <code>gpgkey=.../RPM-GPG-KEY</code>.</p>
<p>See <a href="https://github.com/gatebridgeapp/fido2-android-bridge/blob/master/packaging/README.md">packaging/README.md</a>.</p>
</body></html>
EOF

echo "[repo] tree ready at $OUT"
find "$OUT" -maxdepth 3 -type d | sort
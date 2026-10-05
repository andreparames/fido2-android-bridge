#!/bin/sh
# fido-daemon installer.
#
# Detects the current Linux distribution, adds the project's signed apt/dnf
# repository, and installs the fido-daemon package. Modelled on the Tailscale
# install script (distro detection via /etc/os-release, curl/wget + sudo/doas
# fallbacks, whole body wrapped in main() so a truncated download can't run
# half a script).
#
# Usage:
#   curl -fsSL https://andreparames.github.io/fido2-android-bridge/install.sh | sudo sh
#
# Environment variables:
#   FIDO_DAEMON_BASE_URL   Repository base URL (default: the GitHub Pages site).
#   FIDO_DAEMON_VERSION    Pin a specific package version (e.g. "0.2.1").
#
# This script only adds the repo and installs the package; it never enables the
# service, because pairing must happen first (see the printed instructions). If
# no Python >= 3.11 is present (e.g. Ubuntu 22.04), it offers to install
# python3.11 first.
set -eu

BASE_URL="${FIDO_DAEMON_BASE_URL:-https://andreparames.github.io/fido2-android-bridge}"
# NB: do not call this VERSION — /etc/os-release (sourced below) sets VERSION to
# the human-readable distro version (e.g. "13 (trixie)").
DAEMON_VERSION="${FIDO_DAEMON_VERSION:-}"

main() {
    # --- Step 1: detect the distribution and package system -----------------
    OS=""
    VERSION_CODENAME=""
    UBUNTU_CODENAME=""
    DEBIAN_CODENAME=""
    VERSION_ID=""
    VERSION_MAJOR=""
    ID=""
    ID_LIKE=""
    PACKAGETYPE=""
    REPO=""

    if [ ! -r /etc/os-release ]; then
        echo "error: /etc/os-release not found; cannot detect the distribution." >&2
        exit 1
    fi
    # /etc/os-release is a shell fragment that sets ID, VERSION_ID, etc.
    . /etc/os-release
    VERSION_MAJOR="${VERSION_ID%%.*}"

    case "$ID" in
        debian)
            PACKAGETYPE=apt
            ;;
        ubuntu)
            PACKAGETYPE=apt
            VERSION_CODENAME="${UBUNTU_CODENAME:-$VERSION_CODENAME}"
            ;;
        fedora)
            PACKAGETYPE=dnf
            REPO=fedora
            ;;
        centos)
            PACKAGETYPE=dnf
            REPO=centos
            ;;
        rocky|almalinux|rhel|ol|oracle)
            PACKAGETYPE=dnf
            REPO=rockylinux
            ;;
        # Debian/Ubuntu derivatives: prefer the upstream codename they track.
        linuxmint|pop|neon|elementary|zorin|tuxedo|kali|raspbian|devuan|parrot|deepin|pureos)
            PACKAGETYPE=apt
            if [ -n "$UBUNTU_CODENAME" ]; then
                VERSION_CODENAME="$UBUNTU_CODENAME"
            elif [ -n "$DEBIAN_CODENAME" ]; then
                VERSION_CODENAME="$DEBIAN_CODENAME"
            fi
            ;;
        *)
            # Fall back to ID_LIKE for less common derivatives.
            case "$ID_LIKE" in
                *debian*|*ubuntu*)
                    PACKAGETYPE=apt
                    if [ -n "$UBUNTU_CODENAME" ]; then
                        VERSION_CODENAME="$UBUNTU_CODENAME"
                    elif [ -n "$DEBIAN_CODENAME" ]; then
                        VERSION_CODENAME="$DEBIAN_CODENAME"
                    fi
                    ;;
                *rhel*|*fedora*|*centos*)
                    PACKAGETYPE=dnf
                    REPO=rockylinux
                    ;;
                *)
                    echo "error: unsupported distribution '$ID'." >&2
                    echo "This installer supports Debian/Ubuntu and RHEL/Fedora/Rocky/CentOS." >&2
                    exit 1
                    ;;
            esac
            ;;
    esac

    # --- Step 2: validate the detected distro against what we publish ------
    if [ "$PACKAGETYPE" = apt ]; then
        case "$VERSION_CODENAME" in
            bookworm|trixie|noble|jammy) ;;
            *)
                echo "error: no apt repository for '$ID $VERSION_ID' (suite '$VERSION_CODENAME')." >&2
                echo "Published suites: bookworm (Debian 12), trixie (Debian 13), noble (Ubuntu 24.04), jammy (Ubuntu 22.04)." >&2
                exit 1
                ;;
        esac
        ARCH="$(dpkg --print-architecture 2>/dev/null || echo amd64)"
        if [ "$ARCH" != amd64 ]; then
            echo "error: only amd64 .deb packages are published (detected '$ARCH')." >&2
            exit 1
        fi
    else
        ARCH="$(uname -m)"
        if [ "$ARCH" != x86_64 ]; then
            echo "error: only x86_64 .rpm packages are published (detected '$ARCH')." >&2
            exit 1
        fi
        case "$VERSION_MAJOR" in
            ''|*[!0-9]*) ;;
            *)
                if [ "$REPO" = fedora ] && [ "$VERSION_MAJOR" -lt 41 ]; then
                    echo "warning: packages target Fedora 41+; 'fedora $VERSION_ID' is untested." >&2
                elif [ "$REPO" = rockylinux ] && [ "$VERSION_MAJOR" -lt 10 ]; then
                    echo "warning: packages target the el10 line (RHEL/Rocky/Alma 10); '$ID $VERSION_ID' is untested." >&2
                fi
                ;;
        esac
    fi

    # --- Step 3: work out how to run privileged commands -------------------
    SUDO=""
    if [ "$(id -u)" != 0 ]; then
        if command -v sudo >/dev/null 2>&1; then
            SUDO=sudo
        elif command -v doas >/dev/null 2>&1; then
            SUDO=doas
        else
            echo "error: this installer needs root." >&2
            echo "Re-run as root, or install 'sudo' / 'doas'." >&2
            exit 1
        fi
    fi

    # --- Step 4: pick a downloader ----------------------------------------
    CURL=""
    if command -v curl >/dev/null 2>&1; then
        CURL="curl -fsSL"
    elif command -v wget >/dev/null 2>&1; then
        CURL="wget -qO-"
    fi

    echo "fido-daemon installer: $ID $VERSION_ID ($PACKAGETYPE, $ARCH)"
    if [ -n "$DAEMON_VERSION" ]; then
        echo "pinning version: $DAEMON_VERSION"
    fi

    # --- Step 5: make sure a usable Python >= 3.11 is present --------------
    ensure_python

    # --- Step 6: add the repo and install ---------------------------------
    case "$PACKAGETYPE" in
        apt) install_apt ;;
        dnf) install_dnf ;;
        *)
            echo "error: internal: unknown package type '$PACKAGETYPE'." >&2
            exit 1
            ;;
    esac

    cat <<'EOF'

fido-daemon installed. To pair and start:

  sudo -u fido-daemon fido-daemon pair -c /var/lib/fido-daemon/config.toml
  sudo systemctl enable --now fido-daemon

To also expose a virtual FIDO2 HID device for browser WebAuthn:
  sudo systemctl edit fido-daemon        # add --uhid to ExecStart
EOF
}

# --- Python >= 3.11 handling -------------------------------------------------

# True if interpreter $1 exists and reports Python >= 3.11.
python_ge_311() {
    command -v "$1" >/dev/null 2>&1 &&
        "$1" -c 'import sys; raise SystemExit(0 if sys.version_info >= (3, 11) else 1)' 2>/dev/null
}

# True if interpreter $1 can import cffi (the bundled cryptography needs
# _cffi_backend at load).
python_has_cffi() {
    command -v "$1" >/dev/null 2>&1 && "$1" -c 'import _cffi_backend' 2>/dev/null
}

# Ensure an interpreter that can actually run the daemon. On a distro python3
# >= 3.11, cffi arrives via the package dependency (python3-cffi). The
# python3.11 fallback (e.g. deadsnakes on Ubuntu 22.04) ships no cffi, so we
# install it with pip. If no >= 3.11 interpreter exists, offer to install one.
ensure_python() {
    if python_ge_311 python3; then
        return 0
    fi
    if python_ge_311 python3.11; then
        python_has_cffi python3.11 || install_cffi python3.11
        return 0
    fi

    printf 'The script requires Python 3.11+, which is not available. Should I install it? [y/N] ' >&2
    reply=""
    if [ -e /dev/tty ]; then
        # Read from the terminal so the prompt works under `curl ... | sh`.
        reply="$( { r=""; read -r r < /dev/tty || true; printf '%s' "$r"; } 2>/dev/null )"
    fi
    case "$reply" in
        [Yy]|[Yy][Ee][Ss]) ;;
        *) echo "aborting: Python 3.11+ is required." >&2; exit 1 ;;
    esac

    install_python311
    install_cffi python3.11
}

# Install a >= 3.11 interpreter from the distro (deadsnakes on Debian/Ubuntu).
install_python311() {
    case "$PACKAGETYPE" in
        apt)
            DEBIAN_FRONTEND=noninteractive $SUDO apt-get install -y -qq software-properties-common >/dev/null
            $SUDO add-apt-repository -y ppa:deadsnakes/ppa
            $SUDO apt-get update -qq
            DEBIAN_FRONTEND=noninteractive $SUDO apt-get install -y -qq \
                python3.11 python3.11-venv python3.11-dev >/dev/null
            ;;
        dnf)
            $SUDO dnf install -y python3.11
            ;;
    esac
}

# Install cffi for interpreter $1 (needed by the bundled cryptography).
install_cffi() {
    $SUDO "$1" -m ensurepip --upgrade >/dev/null 2>&1 || true
    $SUDO "$1" -m pip install --upgrade cffi
}

install_apt() {
    [ -n "$CURL" ] || { echo "error: need 'curl' or 'wget' to download files." >&2; exit 1; }
    $SUDO apt-get update -qq
    DEBIAN_FRONTEND=noninteractive $SUDO apt-get install -y -qq ca-certificates gnupg >/dev/null

    $SUDO install -d -m 0755 /etc/apt/keyrings
    keyfile="$(mktemp)"
    $CURL "$BASE_URL/fido-daemon.gpg" > "$keyfile"
    $SUDO install -m 0644 "$keyfile" /etc/apt/keyrings/fido-daemon.gpg
    rm -f "$keyfile"

    echo "deb [signed-by=/etc/apt/keyrings/fido-daemon.gpg] $BASE_URL/debian/$VERSION_CODENAME/ ./" \
        | $SUDO tee /etc/apt/sources.list.d/fido-daemon.list >/dev/null

    $SUDO apt-get update -qq
    DEBIAN_FRONTEND=noninteractive $SUDO apt-get install -y "fido-daemon${DAEMON_VERSION:+=$DAEMON_VERSION}"
}

install_dnf() {
    [ -n "$CURL" ] || { echo "error: need 'curl' or 'wget' to download files." >&2; exit 1; }

    keyfile="$(mktemp)"
    $CURL "$BASE_URL/RPM-GPG-KEY" > "$keyfile"
    $SUDO rpm --import "$keyfile"
    rm -f "$keyfile"

    $SUDO tee /etc/yum.repos.d/fido-daemon.repo >/dev/null <<EOF
[fido-daemon]
name=fido-daemon
baseurl=$BASE_URL/repo/$REPO/x86_64/
enabled=1
gpgcheck=1
gpgkey=$BASE_URL/RPM-GPG-KEY
EOF

    if command -v dnf >/dev/null 2>&1; then
        $SUDO dnf install -y "fido-daemon${DAEMON_VERSION:+-$DAEMON_VERSION}"
    else
        $SUDO yum install -y "fido-daemon${DAEMON_VERSION:+-$DAEMON_VERSION}"
    fi
}

main "$@"

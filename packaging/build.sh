#!/usr/bin/env bash
set -euo pipefail

# Orchestrates a package build (packaging/PLAN.md §9):
#   stage -> deb|rpm -> lint -> (sign) -> out/
#
# Usage: ./build.sh [all|deb|rpm]     (default: all)
#
# Version is single-sourced from linux-fido-daemon/pyproject.toml (§5).
#
# Build the .deb with dpkg-buildpackage (Debian/Ubuntu) and the .rpm with
# rpmbuild (works from Debian's rpm package too). The venv links the build
# machine's python3, so for a distro-correct package run this inside the
# target distro (CI builds one container per distro; see §10).

TARGET="${1:-all}"

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SELF_DIR"

VERSION="$(python3 -c "import tomllib;print(tomllib.load(open('../linux-fido-daemon/pyproject.toml','rb'))['project']['version'])")"
echo "[build] version=$VERSION"

[ -f stage/usr/lib/fido-daemon/venv/bin/python ] || ./stage.sh
PYMIN="$(PYTHONDONTWRITEBYTECODE=1 "$SELF_DIR/stage/usr/lib/fido-daemon/venv/bin/python" -c "import sys;print(f'{sys.version_info[0]}.{sys.version_info[1]}')")"
echo "[build] venv python=$PYMIN"

mkdir -p out/deb out/rpm

build_deb() {
    echo "[build] building .deb ..."
    sed "s/@PYMIN@/$PYMIN/g" debian/control.in > debian/control
    {
        printf 'fido-daemon (%s) unstable; urgency=medium\n\n' "$VERSION"
        printf '  * Package build for version %s.\n\n' "$VERSION"
        printf ' -- Fido Daemon Team <noreply@example.com>  %s\n' "$(date -R)"
    } > debian/changelog
    dpkg-buildpackage -b -us -uc -d 2>&1 | tee /tmp/deb-build.log
    mv -f ../fido-daemon_*.deb out/deb/ 2>/dev/null || true
    rm -f ../fido-daemon_*.changes ../fido-daemon_*.buildinfo ../fido-daemon-dbgsym_*.deb

    echo "[build] lintian ..."
    for deb in out/deb/*.deb; do
        lintian --fail-on error --no-tag-display-limit "$deb" && echo "  lintian OK: $deb"
    done
}

build_rpm() {
    echo "[build] building .rpm ..."
    RPM_TOP="$SELF_DIR/rpm/RPMBUILD"
    mkdir -p "$RPM_TOP/SOURCES" "$RPM_TOP/SPECS" "$RPM_TOP/BUILD" "$RPM_TOP/RPMS" "$RPM_TOP/SRPMS" "$RPM_TOP/db"
    sed -e "s/@VERSION@/$VERSION/g" -e "s/@PYMIN@/$PYMIN/g" \
        rpm/fido-daemon.spec.in > "$RPM_TOP/SPECS/fido-daemon.spec"
    tar -czf "$RPM_TOP/SOURCES/fido-daemon-$VERSION.tar.gz" -C "$SELF_DIR/stage" .
    rpmbuild --define "_topdir $RPM_TOP" --define "_dbpath $RPM_TOP/db" \
        -ba "$RPM_TOP/SPECS/fido-daemon.spec" 2>&1 | tee /tmp/rpm-build.log
    find "$RPM_TOP/RPMS" -name '*.rpm' -exec mv -f {} out/rpm/ \;

    echo "[build] rpmlint ..."
    for rpmf in out/rpm/*.rpm; do
        rpmlint -c "$SELF_DIR/rpm/rpmlint.toml" "$rpmf" && echo "  rpmlint OK: $rpmf" || true
    done
}

case "$TARGET" in
    all) build_deb; build_rpm ;;
    deb) build_deb ;;
    rpm) build_rpm ;;
    *) echo "usage: $0 [all|deb|rpm]" >&2; exit 2 ;;
esac

echo "[build] done:"
ls -l out/deb out/rpm
# fido-daemon packaging

Builds installable `.deb` and `.rpm` packages for the daemon. See `PLAN.md`
for the full design (bundled venv, dedicated `fido-daemon` system user, signing).

## Build

Requires (Debian/Ubuntu): `debhelper dpkg-dev lintian rpm rpmlint`. Debian's
`rpm` package provides `rpmbuild`.

```sh
./build.sh          # stage -> deb -> rpm -> lint -> out/{deb,rpm}
```

The venv links the build machine's python3, so for a distro-correct package
build inside the target distro (CI builds a per-distro matrix; see §10).

## Install

### Debian / Ubuntu

```sh
sudo apt install ./out/deb/fido-daemon_*.deb
sudo -u fido-daemon fido-daemon pair -c /var/lib/fido-daemon/config.toml   # scan QR with the Android app
sudo systemctl enable --now fido-daemon
```

### RHEL / Fedora / Rocky / Alma

```sh
sudo dnf install ./out/rpm/fido-daemon-*.rpm
sudo -u fido-daemon fido-daemon pair -c /var/lib/fido-daemon/config.toml
sudo systemctl enable --now fido-daemon
```

## Optional: virtual FIDO2 HID device (browser WebAuthn)

The packaged service defaults to the Unix socket only. To let browsers use the
daemon as a security key, add `--uhid`:

```sh
sudo systemctl edit fido-daemon
# add --uhid to ExecStart, then:
sudo systemctl restart fido-daemon
```

This requires the udev rules (`/usr/lib/udev/rules.d/70-fido2-bridge-uhid.rules`)
and the `fido-daemon` user in the `uhid` group — both set up by the package.

## Verifying signatures

```sh
# deb
debsigs --verify out/deb/fido-daemon_*.deb
# rpm
rpm --import packaging/KEYS
rpm -K out/rpm/fido-daemon-*.rpm
```
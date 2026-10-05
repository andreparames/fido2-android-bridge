# fido-daemon packaging

Builds installable `.deb` and `.rpm` packages for the daemon. See `PLAN.md`
for the full design (bundled pure/abi3 wheels run on the distro `python3`,
dedicated `fido-daemon` system user, signing).

## Build

Requires (Debian/Ubuntu): `debhelper dpkg-dev lintian rpm rpmlint`. Debian's
`rpm` package provides `rpmbuild`.

```sh
./build.sh          # stage -> deb -> rpm -> lint -> out/{deb,rpm}
```

The payload is version-independent (pure-Python + abi3 wheels, no bundled
interpreter), so one package per arch works on any distro with `python3 >= 3.11`.
CI still builds/smoke-tests inside each target distro (see §10).

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

## Installing from the hosted repos

Packages are served from this repo's GitHub Pages site, published on tags /
`workflow_dispatch` by the `publish` job in `packaging.yml`.

The easiest way is the one-line installer, which detects the distro, adds the
repo, and installs the package (on Ubuntu 22.04 it offers to install
`python3.11` first):

```sh
curl -fsSL https://andreparames.github.io/fido2-android-bridge/install.sh | sudo sh
```

### Debian / Ubuntu

```sh
sudo install -d -m 0755 /etc/apt/keyrings
sudo curl -fsSL https://andreparames.github.io/fido2-android-bridge/fido-daemon.gpg \
  -o /etc/apt/keyrings/fido-daemon.gpg
echo "deb [signed-by=/etc/apt/keyrings/fido-daemon.gpg] https://andreparames.github.io/fido2-android-bridge/debian/trixie/ ./" \
  | sudo tee /etc/apt/sources.list.d/fido-daemon.list
sudo apt update && sudo apt install fido-daemon
```

Suite per distro: `bookworm` (Debian 12), `trixie` (Debian 13), `noble`
(Ubuntu 24.04), `jammy` (Ubuntu 22.04). The daemon runs on the distro
`python3` if it is >= 3.11, otherwise on `python3.11`.

### RHEL / Fedora / Rocky / Alma

```sh
sudo rpm --import https://andreparames.github.io/fido2-android-bridge/RPM-GPG-KEY
sudo tee /etc/yum.repos.d/fido-daemon.repo >/dev/null <<'EOF'
[fido-daemon]
name=fido-daemon
baseurl=https://andreparames.github.io/fido2-android-bridge/repo/fedora/x86_64/
enabled=1
gpgcheck=1
gpgkey=https://andreparames.github.io/fido2-android-bridge/RPM-GPG-KEY
EOF
sudo dnf install fido-daemon
```

### Verifying signatures

```sh
# deb
debsigs --verify out/deb/fido-daemon_*.deb
# rpm
rpm --import packaging/KEYS
rpm -K out/rpm/fido-daemon-*.rpm
```
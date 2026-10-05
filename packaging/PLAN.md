# Packaging Plan — `fido-daemon` (.deb + .rpm)

Scope: produce installable **Debian/Ubuntu `.deb`** and **RHEL-family `.rpm`**
packages for `linux-fido-daemon`, from one shared pipeline in this directory.
Both packages bundle the systemd **user** unit, the udev rules, an example
config, and docs — and both are produced from the **same staging tree** so the
two formats never drift apart.

---

## 1. Best-practice sources reviewed

- **FPM docs** (`fpm.readthedocs.io`) — packaging from a staging dir, metadata
  flags, `.fpm` shared config.
- **Debian Policy** (systemd integration, udev rules location, `conffiles`,
  `changelog`, `copyright`).
- **Fedora Packaging Guidelines** (systemd user units, udev rules dir,
  `%pyproject_*` macros, `BuildArch` rules).
- **FHS** (`/opt` for third-party add-on software).
- Local ground truth: Debian 13 (trixie) package availability checked via
  `apt-cache policy` (below).

Web search engines were rate-limited during research, so distro-repo facts were
verified directly against the local Debian 13 package index and PyPI metadata
(centrifuge-python 0.6.0).

---

## 2. Key decision — Python dependency strategy

| PyPI dependency | Debian 13 (trixie) | Fedora/RHEL | Note |
|---|---|---|---|
| `fido2` | ✅ `python3-fido2 1.2.0` | likely | |
| `cryptography` | ✅ `python3-cryptography 43` | ✅ | compiled (manylinux abi3 wheel) |
| `segno` | ✅ `python3-segno 1.6.6` | likely | |
| `tomlkit` | ✅ `python3-tomlkit 0.13.2` | ✅ | |
| `noiseprotocol` | ✅ `python3-noiseprotocol 0.3.1` | unclear | |
| `centrifuge-python` | ❌ **not packaged** | ❌ | needs `websockets>=15`, `protobuf>=5.29` |

**Decision: bundle a self-contained virtualenv under `/usr/lib/fido-daemon/venv`.**
Install **all** dependencies (pinned) into it at build time; the package just
unpacks it.

Why:
- The one dependency **no distro ships** (`centrifuge-python`) is the core
  relay client — there is no Debian/RedHat story without bundling or vendoring.
- A bundled venv is **distro-agnostic**: identical behaviour on Ubuntu, Rocky,
  Fedora, openSUSE — no per-distro dependency mapping to maintain.
- `cryptography` ships `cp37-abi3-manylinux` wheels for x86_64 **and** aarch64,
  so no compiler/toolchain is needed on the target (wheels built once at
  packaging time).
- This is the established pattern for commercial cross-distro agents/tools
  (Datadog agent, AWS CLI, GitHub Actions runners).

Trade-offs (accepted):
- Package becomes **arch-specific** (contains `.so` from the venv): build for
  `amd64` + `arm64`, not `all`/`noarch`.
- Package is larger (~10–20 MB compressed).
- Needs `python3 >= 3.11` at runtime (the venv links the system interpreter; it
  is not fully hermetic).
- Fedora's official packaging guidelines *discourage* bundling Python modules —
  but those rules govern packages shipped **in Fedora's own repos**, not
  third-party self-hosted distribution. We are the latter; bundling is the
  right call for a commercial daemon.

Rejected alternative: declare distro `python3-*` dependencies. Only fully works
on Debian (where one wheel would still need vendoring) and is fragile on RPM.

---

## 3. Build tooling

**Decision: native tooling per family, fed from one shared staging tree.**

- **deb** → `debhelper` (dh), compat **13**. No `dh-python` needed (venv is
  bundled, nothing installed into `dist-packages`). `dh_installudev` +
  install of the **system** unit + `/etc` conffile.
- **rpm** → `rpmbuild` with a single `fido-daemon.spec` using standard macros
  (`%{_unitdir}`, `%{_udevrulesdir}`, `%{_sysconfdir}`, `%{_prefix}`).
- Shared build: `packaging/build.sh` runs `stage.sh` (builds the venv + staging
  tree) once, then hands the tree to each format builder.

Why not **FPM** for both? FPM is fine for quick artifacts but expresses
Debian/RedHat-specific policy poorly (conffiles, maintainer scriptlets, group
creation, lintian/rpmlint expectations). Native tooling is the best practice
for anything meant to be installed on real servers. FPM is noted as the
fallback if a single-tool pipeline is preferred (see §11).

Reproducible builds: build inside pinned containers via `podman`/`docker`
(`debian:13` for deb, `rockylinux:9` or `fedora:41` for rpm). `rpmbuild` needs
rpm tooling that the host (Debian) lacks.

---

## 4. Directory layout

```
packaging/
  PLAN.md                         # this document
  README.md                       # per-distro install instructions
  build.sh                        # orchestrate: stage -> deb -> rpm (containers)
  stage.sh                        # build /usr/lib/fido-daemon venv + staging tree
  debian/
    control
    rules
    install
    changelog
    copyright
    source/format                 # "3.0 (native)" or quilt — see §7
  rpm/
    fido-daemon.spec
  systemd/
    fido-daemon.service           # distro-clean unit (fixed, see §6)
    70-fido2-bridge-uhid.rules
  config/
    fido-daemon.toml.example      # installed to /etc/fido-daemon/
  out/                            # build artifacts (gitignored)
    deb/  rpm/
```

Staging tree produced by `stage.sh`:

```
stage/
  opt/fido-daemon/venv/           # venv + all deps + fido_daemon package
  usr/bin/fido-daemon             # symlink -> /usr/lib/fido-daemon/venv/bin/fido-daemon
  usr/lib/systemd/system/fido-daemon.service
  usr/lib/udev/rules.d/70-fido2-bridge-uhid.rules
  etc/fido-daemon/fido-daemon.toml.example
  usr/share/doc/fido-daemon/{README.md,CHANGELOG.md,LICENSE}
```

---

## 5. Versioning

Single source of truth: `linux-fido-daemon/pyproject.toml` (`[project] version`).
- `build.sh` extracts it with `tomllib` and injects into `debian/changelog`
  (generated) and `%{version}` in the spec.
- Artifact names:
  - `fido-daemon_<ver>_<arch>.deb`
  - `fido-daemon-<ver>-1.<arch>.rpm`
- No duplicated version literals in packaging files.

---

## 6. Required daemon/source changes before packaging

1. **Replace the user unit with a system service** under a dedicated,
   non-privileged `fido-daemon` user (decision §11). This matches the
   "remote Linux server" deployment (survives logout, headless) and how
   OS-level virtual-HID emulators run. The unit
   (`linux-fido-daemon/systemd/fido-daemon.service`, now a **system** unit):
   ```
   [Unit]
   Description=Remote WebAuthn Android Bridge Daemon
   After=network-online.target
   Wants=network-online.target

   [Service]
   Type=simple
   User=fido-daemon
   Group=fido-daemon
   RuntimeDirectory=fido-daemon          # -> /run/fido-daemon (chowned to the
   RuntimeDirectoryMode=0700             #    service user by systemd)
   ExecStart=/usr/lib/fido-daemon/venv/bin/fido-daemon \
       -c /var/lib/fido-daemon/config.toml \
       --socket %t/fido-daemon/fido2-bridge.sock
   Environment=PYTHONUNBUFFERED=1
   Environment=FIDO2_STATIC_KEY_PATH=/var/lib/fido-daemon/static_key.pem
   Restart=on-failure
   NoNewPrivileges=true
   PrivateTmp=true
   ProtectHome=true
   ProtectSystem=strict
   ReadWritePaths=/var/lib/fido-daemon

   [Install]
   WantedBy=multi-user.target
   ```
   - `--uhid` is **off by default**: `UhidDevice.start()` raises if `/dev/uhid`
     is unavailable, which would fail the service. Document adding it via a
     drop-in (`systemctl edit`) once the udev rule + group are in place.
   - Socket 0600 owned by `fido-daemon`: local clients must run as `fido-daemon`
     or be granted group access — see §11 "socket reachability".
2. **Writable config lives in `/var/lib/fido-daemon/`, not `/etc`**: the daemon
   rewrites its config at runtime when it pins the phone's public key
   (trust-on-first-use, `cli.py` `_persist_phone_key`). That is state, so it
   must be writable by the service user:
   - actual config: `/var/lib/fido-daemon/config.toml` (created by postinst,
     owned by `fido-daemon`)
   - read-only reference example: `/etc/fido-daemon/fido-daemon.toml.example`
     (conffile)
   - static identity key: `/var/lib/fido-daemon/static_key.pem`
     (`FIDO2_STATIC_KEY_PATH`)
   - this also makes `ProtectSystem=strict` hardening work (`ReadWritePaths=
     /var/lib/fido-daemon`).
3. **udev `uhid` group + daemon membership**: the rule uses `GROUP="uhid"`.
   The package must create the group **and** add `fido-daemon` to it
   (`usermod -aG uhid fido-daemon`) so the non-root service user can open
   `/dev/uhid` when `--uhid` is enabled.
4. **Pairing** is done as the service user:
   `sudo -u fido-daemon fido-daemon pair -c /var/lib/fido-daemon/config.toml`.
5. No functional code changes are required beyond honoring `FIDO2_REMOTE_SOCKET`
   and `FIDO2_STATIC_KEY_PATH` (both already supported); this is unit-file +
   packaging work.

---

## 7. deb specifics

- Name `fido-daemon`, Section `utils`, Priority `optional`,
  **Architecture: `amd64` / `arm64`** (venv is arch-specific).
- `Depends: python3 (>= 3.11), systemd, udev, passwd, adduser` — nothing else.
- `debian/install`:
  - `stage/usr/lib/fido-daemon/venv → /usr/lib/fido-daemon/`
  - `usr/bin/fido-daemon → /usr/bin/`
  - `usr/lib/systemd/system/fido-daemon.service → /usr/lib/systemd/system/`
  - `usr/lib/udev/rules.d/70-fido2-bridge-uhid.rules → /usr/lib/udev/rules.d/`
    (Debian Policy: vendor rules live in `/usr/lib/udev`, admin ones in `/etc`)
  - config example → `/etc/fido-daemon/` (**auto-treated as conffile** by dh)
  - docs → `/usr/share/doc/fido-daemon/`
- `debian/rules`: minimal `dh $@`; drop `dh_python3` (nothing to process).
- Maintainer scripts:
  - `postinst`: create `uhid` group if missing (guarded); create the
    `fido-daemon` **system user** if missing (guarded `adduser --system
    --group --home /var/lib/fido-daemon fido-daemon`); create
    `/var/lib/fido-daemon` owned by the service user and seed
    `/var/lib/fido-daemon/config.toml` from the `/etc` example if absent; add
    the user to the `uhid` group (`usermod -aG uhid fido-daemon`). **Do not
    auto-enable** the unit (pairing must happen first); print next steps
    (`sudo -u fido-daemon fido-daemon pair -c /var/lib/fido-daemon/config.toml`
    then `systemctl enable --now fido-daemon`).
  - No `prerm`/`postrm` logic beyond defaults; do **not** delete the user or
    the group (state may persist / other tools may rely on it).
- `debian/changelog` generated by `build.sh` from `pyproject.toml` + git log.
- `debian/copyright` — MIT, with the venv's bundled wheels documented
  (Debian asks for per-component copyright for vendored code; keep a list of
  bundled wheels and their licenses).
- Target lintian: zero errors.

---

## 8. rpm specifics

- `Name: fido-daemon`, `Version`/`Release` from §5, `License: MIT`,
  `BuildArch` omitted → per-`%{_arch}` (venv).
- `Requires: python3 >= 3.11, systemd, systemd-udev, passwd, shadow-utils`.
- `%install`:
  - copy venv → `%{buildroot}/usr/lib/fido-daemon`
  - system unit → `%{_unitdir}` (`/usr/lib/systemd/system`)
  - udev rules → `%{_udevrulesdir}` (`/usr/lib/udev/rules.d`)
  - config example → `%{_sysconfdir}/fido-daemon/`
  - `%{_bindir}/fido-daemon` symlink → venv console script
- `%files` mirrors the above; mark `/etc/fido-daemon/fido-daemon.toml.example`
  as `%config(noreplace)`.
- Scriptlets:
  - `%pre`: create `uhid` group + `fido-daemon` system user (guarded
    `groupadd --system uhid || :`, `useradd --system --home-dir
    /var/lib/fido-daemon --no-create-home fido-daemon || :`); create
    `/var/lib/fido-daemon` owned by the user.
  - `%post`: `usermod -aG uhid fido-daemon` (idempotent); seed
    `/var/lib/fido-daemon/config.toml` from the `/etc` example if absent;
    **no `%systemd_post`** — consistent with deb, no auto-enable. (If
    auto-enable is later wanted, use the `%systemd_post`/`%systemd_preun`
    macros.)
  - Do **not** delete the user/group on uninstall.
- **SELinux (resolved: docs only)**: a *system* service opening `/dev/uhid`
  may be blocked by the default `uhid_device_t` policy. We ship **no** policy
  module; instead the README documents the issue and a minimal local policy
  module users can install if they hit a denial.
- Target rpmlint: zero errors.

---

## 9. Build pipeline (`build.sh`)

```
VERSION  := tomllib(pyproject.toml)
1. stage.sh            # venv + staging tree (pinned requirements, pip wheel cache)
2. deb                 # debian:13 container: dpkg-buildpackage -b -us -uc
3. rpm                 # rockylinux:9 container: rpmbuild -ba fido-daemon.spec
4. checks              # lintian + rpmlint on artifacts (fail on errors)
5. sign                # debsigs for .deb; rpmsign --addsign for .rpm
6. out/{deb,rpm}/      # signed artifacts + SHA256SUMS + SIGSUMS
```

Container images pinned by digest for reproducibility. `stage.sh` builds the
venv against the **oldest** supported Python (3.11) so the venv's
abi3/wheel tags are maximally compatible with newer runtimes.

### Signing (decision: include)

- **deb**: `debsigs --sign=origin fido-daemon_*.deb` (packaged in Debian;
  `dpkg-sig` is not in trixie); verification is
  `debsigs --verify <file>.deb`.
- **rpm**: `rpmsign --addsign fido-daemon-*.rpm`; verification is
  `rpm -K --nosignature` / `rpm --checksig`.
- Key management: a dedicated release GPG key. The private
  key lives only in the packaging/CI secret store, never in the repo.
  `packaging/KEYS` ships the public key so admins can verify artifacts.
- The signing step is **separate from the build containers**: artifacts are
  built unsigned, then signed in a step that has access to the key (isolated
  secret scope, build hosts stay key-less).
- Verify step re-checks signatures in §10 before artifacts are released.

#### Release key generation & lifecycle

One-time setup on a **trusted machine you control** (your dev box — the key
point is: **not** on a CI runner or inside the build pipeline):

Rationale — the key will end up in GitHub secrets regardless, so "offline" is
not about hiding it from GitHub. It is about:
1. **Provenance at birth**: if the key were generated inside the build/CI
   environment it later protects, a compromised pipeline could exfiltrate it
   at creation or substitute its own public key. Generating on a machine you
   control means you personally saw the fingerprint you publish.
2. **Custody + revocability outside GitHub**: the master key and its
   revocation certificate must exist somewhere a GitHub account/org compromise
   or accidental secret deletion cannot touch — otherwise a lost account
   permanently destroys the signing identity and makes revocation impossible.

Steps (commands verified on GnuPG 2.4.7):

```bash
# 1. Generate the key (interactive; set MASTER passphrase; ~3yr expiry, ed25519)
gpg --full-generate-key
KEYID=$(gpg --batch --with-colons --list-secret-keys | awk -F: '/^sec/{print $5; exit}')
gpg --list-secret-keys --with-fingerprint "$KEYID"        # record fingerprint

# 2. Publish the public key (repo, public)
gpg --armor --export "$KEYID" >> packaging/KEYS

# 3. Revocation cert -> encrypted file in <BACKUP_DEST>, a location NOT on this
#    machine (see note below). Encrypted with gpg --symmetric (AES256); the
#    file's passphrase is stored in `pass` so it is not tied to this machine.
pass insert fido-daemon/revoke-passphrase
gpg --gen-revoke "$KEYID" > /tmp/fido-revoke.asc
gpg --symmetric --cipher-algo AES256 --passphrase-file <(pass fido-daemon/revoke-passphrase) \
    -o "$BACKUP_DEST/fido-revoke.asc.gpg" /tmp/fido-revoke.asc
rm /tmp/fido-revoke.asc                                  # only the encrypted copy remains

# 4. Master-key backup -> same <BACKUP_DEST>. Export is already protected by the
#    MASTER passphrase; store that passphrase in `pass` (not in the backup).
pass insert fido-daemon/master-passphrase
gpg --export-secret-keys "$KEYID" > "$BACKUP_DEST/fido-master.gpg"

# 5. CI copy with a DIFFERENT passphrase than the master (so GitHub never holds
#    the master passphrase). Swap the key's passphrase to a CI-only one, export,
#    then swap back. Verified on GnuPG 2.4.7 (no --quick-set-passphrase there).
pass insert fido-daemon/ci-passphrase
printf '%s\n%s\n%s\nsave\nquit\n' "$MASTER" "$CI" "$CI" |
  gpg --batch --pinentry-mode loopback --command-fd 0 --edit-key "$KEYID" passwd
gpg --batch --pinentry-mode loopback --passphrase "$CI" \
    --export-secret-keys "$KEYID" | base64 -w0 > /tmp/ci-key.b64   # -> secret RELEASE_GPG_PRIVATE_KEY
printf '%s\n%s\n%s\nsave\nquit\n' "$CI" "$MASTER" "$MASTER" |
  gpg --batch --pinentry-mode loopback --command-fd 0 --edit-key "$KEYID" passwd   # restore
```

> **`<BACKUP_DEST>` for now:** a local dir is fine for getting started —
> `export BACKUP_DEST=$HOME/.local/share/fido-daemon/backup && mkdir -p "$BACKUP_DEST"`.
> It only guards against accidental deletion (not disk compromise), so run the
> checklist at the end of this section to move the two files off-machine.

Then provision CI:
- `RELEASE_GPG_PRIVATE_KEY` = contents of `/tmp/ci-key.b64` (GitHub secret).
- `RELEASE_GPG_PASSPHRASE` = the CI-only passphrase (GitHub secret).
- Record `KEYID` + fingerprint in the repo (public info) so CI can select the
  key deterministically.

Key passphrases, summarized:
- **Master** (locks the keyring key you keep): stored in `pass`
  (`fido-daemon/master-passphrase`).
- **Revocation-cert encryption** (separate file passphrase): stored in `pass`
  (`fido-daemon/revoke-passphrase`).
- **CI copy**: a distinct passphrase, stored in `pass`
  (`fido-daemon/ci-passphrase`) *and* in the GitHub secret — GitHub holds the
  CI value only, never the master.

> **Interactive requirement:** `pass` unlocks via your interactive gpg agent
> (pinentry), which non-interactive automation cannot use. Provisioning is
> therefore a **one-time interactive script** (`packaging/scripts/
> provision-release-key.sh`) you run in a normal terminal; the script reads the
> passphrases, regenerates backups, provisions the CI copy + GitHub secrets,
> and prints the off-machine backup checklist.

#### Off-machine backup checklist (do this manually once you have a USB / second box)

Two files + one pass store to relocate off the dev machine:

| Item | Source path | Destination |
|---|---|---|
| Encrypted revocation cert | `$BACKUP_DEST/fido-revoke.asc.gpg` | USB stick / other machine / encrypted cloud |
| Master-key backup | `$BACKUP_DEST/fido-master.gpg` | same |
| Passwords | `pass fido-daemon/{master,revoke,ci}-passphrase` | your normal password-manager backup |

Rotation/revocation policy:
- Rotate when the ~3-year expiry approaches, or immediately on suspected
  compromise; `ghaction-import-gpg` reads the current secrets, so rotation is:
  generate new key → update the two secrets → append new pubkey to
  `packaging/KEYS` with a changelog note.
- On compromise: decrypt the stored revocation cert and publish it in the
  release notes; bump `packaging/KEYS`.

`packaging/KEYS` is the canonical distribution point — admins fetch it once to
verify all artifacts until it is revoked.

#### CI signing tooling (researched)

No turnkey, widely-adopted GitHub Action exists for signing deb/rpm — the
dedicated actions (`signalwire/sign-rpm-packages-action`,
`dante-signal31/rpmsign`, `fragoi/debsign-action`, `arkane-systems/apt-repo-update`)
are all niche and sparsely maintained. The standard pattern used by real
projects is:

1. `crazy-max/ghaction-import-gpg` (~380★, de-facto standard) — import the
   release key from a GitHub secret into the runner keyring.
2. Plain `run:` steps with the native tools:
   - deb: `debsigs --sign=origin fido-daemon_*.deb`
   - rpm: `rpmsign --addsign fido-daemon-*.rpm`
3. A verify gate (`debsigs --verify`, `rpm -K`) before upload.

Skipped options: **cosign/sigstore** is well-known but its signatures are not
verifiable by `apt`/`dnf` (only good for release-asset checksums); hosted
build platforms (**COPR**, **PPA**, **OBS**) sign for you but tie the build to
a third-party service.

### Repo publishing (decision: in scope — GitHub Pages)

The apt and dnf repos are served as **static trees on this repo's GitHub
Pages** site (`https://andreparames.github.io/fido2-android-bridge/`), built
by the `publish` job of `packaging.yml` on tags / `workflow_dispatch`
(`packaging/scripts/build-repo-tree.sh`):

```
site/
  debian/<suite>/            flat apt repo per suite (bookworm/trixie/noble):
                             Packages, Packages.gz, Release, InRelease (signed),
                             fido-daemon_*.deb
  repo/<distro>/<arch>/      dnf repo per distro (fedora/x86_64, rockylinux/x86_64):
                             fido-daemon-*.rpm + repodata/ (createrepo_c)
  fido-daemon.gpg            release public key (binary; apt signed-by=)
  RPM-GPG-KEY                release public key (armored; rpm gpgkey=)
  index.html, .nojekyll      Pages: no autoindex, no Jekyll
```

Client setup — **Debian/Ubuntu**:
```
sudo install -d -m 0755 /etc/apt/keyrings
sudo curl -fsSL https://andreparames.github.io/fido2-android-bridge/fido-daemon.gpg \
  -o /etc/apt/keyrings/fido-daemon.gpg
echo "deb [signed-by=/etc/apt/keyrings/fido-daemon.gpg] https://andreparames.github.io/fido2-android-bridge/debian/<suite>/ ./" \
  | sudo tee /etc/apt/sources.list.d/fido-daemon.list   # suite: bookworm|trixie|noble
sudo apt update && sudo apt install fido-daemon
```

**RHEL-family**:
```
sudo rpm --import https://andreparames.github.io/fido2-android-bridge/RPM-GPG-KEY
cat >/etc/yum.repos.d/fido-daemon.repo <<EOF
[fido-daemon]
name=fido-daemon
baseurl=https://andreparames.github.io/fido2-android-bridge/repo/fedora/x86_64/
enabled=1
gpgcheck=1
gpgkey=https://andreparames.github.io/fido2-android-bridge/RPM-GPG-KEY
EOF
sudo dnf install fido-daemon
```

Notes: Pages is one site per repo (the marketing site moved to its own repo,
so this repo's Pages hosts the package repos). `.deb`/`.rpm` carry the same
version across suites (per-python distro builds), so each distro gets its own
suite/dir rather than a shared pool. GitHub Pages has a 1 GB site limit; prune
old versions if it grows. The first Pages deploy needs the `github-pages`
environment approved once.

---

## 10. Verification (maps to agents.md §6 Definition of Done)

Matrix: `debian:12`/`debian:13`, `ubuntu:24.04`, `fedora:41`, `rockylinux:10`,
amd64 + arm64. For each:

> Python floor is **3.11** (`pyproject.toml`; `centrifuge-python` needs ≥3.10),
> which excludes Rocky 8/9 (3.9), Ubuntu 20.04 (3.8) and 22.04 (3.10). The
> per-distro container matrix below reflects that.

1. `apt install ./fido-daemon_*.deb` / `dnf install ./fido-daemon-*.rpm` — clean,
   dependencies satisfied from distro repos only.
2. `systemd-analyze verify /usr/lib/systemd/system/fido-daemon.service` — passes.
3. Postinst effects: `fido-daemon` system user exists, is in the `uhid` group;
   `/var/lib/fido-daemon` exists and is owned by the user; `/etc/fido-daemon`
   conffile present.
4. `/usr/bin/fido-daemon --help` and `sudo -u fido-daemon fido-daemon pair
   --no-qr` — run, config written to `/etc/fido-daemon/config.toml`.
5. `ldd` the venv python + `import cryptography` — no missing shared libs.
6. Start unit (`systemctl start fido-daemon`): socket created at
   `/run/fido-daemon/fido2-bridge.sock` with `0600` (owned by `fido-daemon`);
   clean unlink on stop.
7. `lintian` / `rpmlint`: zero errors on all artifacts.
8. Signature check: `debsigs --verify` passes on each `.deb`; `rpm -K` reports
   a good signature on each `.rpm`.

---

## 11. Decisions (resolved)

| # | Question | Decision |
|---|----------|----------|
| 1 | Dependency strategy | **Bundled venv** under `/usr/lib/fido-daemon/venv` (§2) |
| 2 | Arch set | **Both**: `amd64` + `arm64` |
| 3 | Auto-enable on install | **No** — pairing must happen first; `postinst`/`%pre` print next steps |
| 4 | `uhid` group | **Create via package postinst** (`groupadd --system uhid`, guarded) |
| 5 | Tooling | **Native**: debhelper + rpmbuild (§3) |
| 6 | SELinux | **No policy module** — document issue + optional local module in README (§8) |
| 7 | Signing | **Include** in the pipeline; key isolated from build hosts (§9); repo publishing via **GitHub Pages** (§9.2) |
| 8 | Run model | **System service under a dedicated `fido-daemon` user** (§6), not a per-user unit — server-target deployment, survives logout |
| 9 | Service user provisioning | **postinst/`%pre` create the user + group**, add to `uhid`, create `/var/lib/fido-daemon` (§7/§8) |
| 10 | Socket reachability | **0600 owned by `fido-daemon`** — only same-user local clients; human-user clients use the uhid path (world-rw hidraw). Revisit if a direct-socket client under another user is needed |
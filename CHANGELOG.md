# Changelog

All notable changes to this project.

<!-- Entries are prepended by scripts/release/bump.py on each release. -->

## v0.4.0

### Added
- Daemon: managed relay mode with mode detection, token-less QR, and subscribe polling
- Daemon: control-plane pairing and open-subscribe rework (relay-publisher-auth)
- Android: Gatebridge Play Billing integration with managed-relay subscription gate
- Android: local diagnostics persistence with zip export
- Relay hardening: pass-default relay token and fidobridge channel namespace

### Changed
- Docs: reorganized (AGENTS.md rename, component plans, playstore grouping)

### Fixed
- CI: local Centrifugo now uses a fidobridge namespace
- CI: Play debug APK build and upload on master

## v0.3.1

### Changed

- Point repository URLs at the gatebridgeapp org and packages.gatebridge.app
- Relay topic namespace: channels are now `fidobridge:<channel_id>` (colon,
  PROTOCOL.md §3.3); Centrifugo config restricts channels to this namespace and
  denies the unnamed namespace
- Daemon defaults the relay connection JWT to an embedded token in code (no
  runtime `pass` dependency); `FIDO2_RELAY_TOKEN` overrides it

## v0.3.0

### Added
- App logo and updated launcher icons
- deb/rpm packaging for linux-fido-daemon
- Marketing website with GitHub Pages deploy workflow
- llms.txt, sitemap, AI crawler robots, and JSON-LD
- BRAND.md with naming rationale
- Business plan for remote WebAuthn product
- Manually-triggered Jammy image build with Python 3.11 + cffi

### Fixed
- Render-blocking: self-host fonts, inline CSS
- Center waitlist dialog on screen

### Changed
- CI: use prebuilt ghcr.io jammy image (Python 3.11) for Ubuntu 22.04 job
- CI: trigger packaging only on workflow_dispatch/tags; drop redundant smoke-test gate
- CI: run install smoke test only on workflow_dispatch/tags
- CI: bootstrap pip via ensurepip in Jammy image
- Run on distro python3 (>= 3.11) instead of a bundled interpreter
- Publish apt/rpm repos on GitHub Pages
- Move marketing website to gatebridge-site repo

## v0.2.1

### Fixed
- Default model when workflow inputs are empty

### Changed
- Gate release-llm on Environment release (manual approval)
- LLM release as master workflow_dispatch (same-run, tag-first)

## v0.2.0

### Added
- v3 Noise IK transport with TOFU pinning for bridge protocol
- Virtual FIDO2 HID key exposure for browser integration (--uhid)
- TOML config file support with request/response diagnostics
- Terminal QR code rendering for pairing URI via segno
- Relay token carried in pairing URI (protocol v2)
- Top-level README and Fly.io relay deployment
- GitHub Actions CI pipeline with ruff lint gate
- Automated emulator E2E harness using uiautomator2
- LLM-assisted release pipeline (OpenCode Go LongCat)
- Stable debug keystore pinned for CI and local APK installs

### Fixed
- CTAPHID framing and getInfo for browser compatibility (Chrome)
- CTAP2_ERR_NO_CREDENTIALS (0x2E) returned from getAssertion when no credential matches
- Relay self-echo loop and Chrome .dummy touch probe
- Centrifugo v6 CLI flags in integration job
- CI triggers corrected for master default branch
- Harness wait fixed to target mock daemon child of same shell
- CAMERA runtime permission requested for QR pairing preview on devices


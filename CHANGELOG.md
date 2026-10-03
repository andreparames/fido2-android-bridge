# Changelog

All notable changes to this project.

<!-- Entries are prepended by scripts/release/bump.py on each release. -->

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


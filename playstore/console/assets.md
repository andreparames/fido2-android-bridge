# Gatebridge — Play graphical assets checklist

## Purpose

Upload checklist for Play Console graphical assets for **Gatebridge**
(application ID `com.fidobridge.client`; package rename is out of scope —
keep as-is per `playstore/plan.md`). All product naming in device UI and marketing art
is **Gatebridge**, never "FIDO Bridge". Specs below follow the frozen
decisions in `playstore/plan.md`; where anything conflicts, `playstore/plan.md` wins.

Constraints for every asset and capture session:

- Gatebridge branding only; no emoji; no invented product names.
- Production managed relay host is `relay.gatebridge.app`
  (`wss://relay.gatebridge.app/connection/websocket`). Do not show or
  hardcode any personal/dev relay host in marketing assets. If an in-app
  field shows a relay URL, use the production host or a neutral placeholder —
  never a personal host.
- Category: Business (fallback Tools). minSdk 26 (Android 8.0+), target 34.
- Biometric hardware is required; shots that show a prompt must look like a
  real system biometric flow, not a software-only PIN.

---

## Asset table

| Asset | Spec | Source / notes |
|---|---|---|
| App icon | 512×512 PNG, 32-bit (RGBA) | Export from `/home/andre/dev/fido2android/.github/logo-original.png` (1080×1080, 8-bit RGBA). Play applies the adaptive-icon mask; keep the mark centered with safe padding and **avoid baking heavy rounded corners** into the export. |
| Feature graphic | 1024×500 PNG | Tunnel mark + wordmark "Gatebridge"; dark background; accent teal `#0d9488` (site theme-color). Placeholder design notes below. |
| Phone screenshots | 4–8 portrait images; minimum long edge ≥1080 px | Shot list below. Device UI must show **Gatebridge**, not FIDO Bridge. |
| Promo video (optional) | 10–30 s, 720p or higher | Narrative: QR pairing → biometric prompt → remote browser success. Prefer screen recording; no fabricated UI. |

Secondary sources on disk (do not upload these as the store icon; useful for
in-device consistency checks):

- `.github/logo-256.png` (256×256 RGBA), `.github/logo.png` (128×128 RGBA)
- Launcher mipmaps under `android-fido-client/app/src/main/res/`:
  `mipmap-mdpi/ic_launcher.png` 48×48 through
  `mipmap-xxxhdpi/ic_launcher.png` 192×192, plus `ic_launcher_round.png` /
  `ic_launcher_foreground.png` in each density and
  `mipmap-anydpi-v26/ic_launcher.xml`

---

## Feature graphic — placeholder design notes

For the designer or export agent ([OWNER: feature graphic export]):

- Canvas: 1024×500, PNG, RGB (no transparency on the store asset).
- Background: dark, matching Gatebridge dark UI / site theme (near-black
  charcoal). Do not introduce a light theme for this asset.
- Foreground: abstract tunnel/vortex mark (mint/teal) from
  `logo-original.png` + wordmark **Gatebridge** set in a clean sans in white
  or near-white.
- Accent: `#0d9488` for a thin rule, small label, or mark detail only.
- Optional short concrete line (no hype, no emoji), e.g. "Remote WebAuthn,
  backed by your phone's secure hardware." Do not claim FIDO Alliance
  endorsement or certification.
- No fake pricing, no social proof, no screenshots-of-screenshots. Optional
  subtle device silhouette is allowed; any visible UI text must say
  Gatebridge.
- Layout must read at thumbnail size; keep the mark large enough to survive
  Play listing crops.

---

## Screenshot shot list

Specs: portrait only, 4–8 images, minimum long edge ≥1080 px. Capture after
the Gatebridge rename (and after subscribe UI when available) so every shot
shows **Gatebridge**. Prefer a single consistent device theme per listing
section; light or dark is fine if intentional. Demo RP domain comes from the
review kit (e.g. `webauthn.io`); never a personal relay host. Do not capture
real user credentials or a real account.

1. **Pairing** — "Connect your phone" screen with QR scanner active and/or the
   paste-field fallback visible. Avoid catching the camera-permission system
   dialog mid-flow.
2. **Subscribe** — trial + monthly/yearly product cards once Play Billing UI
   lands (`gatebridge_individual_monthly`, `gatebridge_individual_yearly`;
   copy: 1 month free, then the price shown in Play). **Placeholder layout
   note:** until billing UI exists, do not invent fake price numbers; use a
   greyed placeholder card layout or skip this shot and re-capture later.
3. **Home connected** — connection/paired banner + request log with at least
   one row showing RP domain and outcome **Accepted**.
4. **Home empty/waiting** — waiting-for-request empty state; copy must be
   store-ready in **light and dark**.
5. **Optional: biometric system prompt** — system `BiometricPrompt` overlay
   showing the RP id. Prefer a short **screen recording** (promotes better
   than a still).
6. **Optional: Reset confirmation dialog** — in-app "Reset app" dialog
   (erases pairing keys + stored credentials); confirm dialog title/body use
   Gatebridge wording.

---

## Pre-screenshot app polish (implement later — not this docs session)

- `android-fido-client/app/src/main/res/values/strings.xml` — `app_name`
  from `FIDO Bridge` → `Gatebridge`.
- `FidoBridgeApp` error dialog title `FIDO Bridge error` → `Gatebridge`.
- Review remaining hardcoded "FIDO Bridge" UI strings (e.g. notification /
  foreground-service copy in `FidoBridgeService.kt`, `RequestNotifier.kt`)
  so no shot or status-bar notification shows the old name.
- Home and Pairing empty states must be store-ready in **light + dark**.
- This session changes no Gradle/app code and renames nothing else.

---

## Brand notes

- Logo is an abstract **tunnel/vortex** mark: mint/teal on dark. Marketing
  assets derive from `.github/logo-original.png`.
- In-device consistency: prefer existing launcher mipmaps where the device
  UI shows an icon; store icon is a separate 512×512 export from the 1080
  source with adaptive-safe padding.
- Wordmark on all marketing art: **Gatebridge**. Device UI must also read
  Gatebridge after polish lands.
- Accent teal `#0d9488` comes from the site theme-color; keep the rest of the
  palette within the existing mint/teal-on-dark brand.

---

## Play Console upload order

1. App icon (512×512)
2. Feature graphic (1024×500)
3. Phone screenshots (4–8, ordered by shot list quality: Pairing →
   Subscribe → Home connected → Home empty → optional biometric → optional
   Reset)

Listing-quality order: put the strongest story first (pairing and connected
home), keep optional shots after the required ones.

---

## Owner placeholders

- `[OWNER: feature graphic export]` — 1024×500 PNG per the design notes
  above, accent `#0d9488`, dark ground, tunnel mark + Gatebridge wordmark.
- `[OWNER: final screenshot capture after Gatebridge rename + subscribe UI]` —
  re-capture shots 1–6 once `app_name` / error-dialog polish and billing UI
  land; until then shots 2 and any un-renamed captures are placeholders.

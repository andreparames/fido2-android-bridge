# PLAYSTORE_BASE_PLAN — Gatebridge Play Store entry (docs only)

## Purpose
Generate Play Console–ready documentation for the Gatebridge Android app.
This file is the single source of truth for all writers. Do not invent
product facts that contradict this file. When in doubt, follow this file
over memory.

## Decision log (FROZEN)
| Decision | Value |
|---|---|
| Product name | Gatebridge (NOT "FIDO Bridge" in any user-facing doc) |
| Domain | https://gatebridge.app |
| Support email | hello@gatebridge.app |
| Security email | security@gatebridge.app |
| Application ID | com.fidobridge.client (KEEP — do not propose rename) |
| Play products | gatebridge_individual_monthly, gatebridge_individual_yearly |
| Trial | 1 month free on both products; NO permanent free managed tier |
| Monetization | Play Billing subscriptions only for the managed/hosted tier |
| OSS path | Free APK from GitHub Releases + self-hosted Centrifugo relay |
| Repo | https://github.com/gatebridgeapp/fido2-android-bridge |
| Marketing site | https://gatebridge.app (Astro; privacy/review pages not live yet) |
| Launch posture | Prepare docs now; publish to Play only when managed relay is ready |
| Managed relay host | `relay.gatebridge.app` (WebSocket: `wss://relay.gatebridge.app/connection/websocket`) |
| Category | Business (fallback Tools) |
| minSdk / target | Android 8.0+ (minSdk 26), target API 34 |
| Hardware | Biometric hardware REQUIRED (fingerprint or face) |

## Product split (must be crystal clear in all copy)
- Play Store = PAID managed product: pre-built app + hosted encrypted relay
  + auto-updates + email support. Subscription via Google Play.
- GitHub Releases = FREE open-source APK for users who run their OWN relay.
  This is open-core (Ghost/Mattermost pattern).
- NEVER offer a cheaper external payment for the hosted tier.
- NEVER imply a free managed relay or a permanent free tier.
- GitHub mention = self-hosting only.

## Positioning (from BUSINESS_PLAN.md / gatebridge-site)
- One job: remote WebAuthn / passkeys on headless or remote Linux, backed by
  the phone's secure hardware.
- Not a password manager. Not SSH sk-* replacement. Not SSO. Not a general
  FIDO2 server.
- Relay is UNTRUSTED. E2EE via Noise (Noise_IK_25519_AESGCM_SHA256).
- Keys never leave Android KeyStore (TEE/StrongBox). Every signature requires
  biometric auth — no software-only fallback.
- Phone must be online to respond.
- Target: individuals with VPS/remote box, dev shops, MSPs, SMB DevOps.
- Price anchor: YubiKey $25–55/seat vs Individual ~$1–3/mo (site also says
  "about $2 per month"; use Play Console live prices at launch — in docs use
  "monthly or yearly subscription; 1 month free, then the price shown in Play").

## Technical facts (accurate to current code)
- Android client: Kotlin, Compose, Hilt, BiometricPrompt, AndroidKeyStore.
- Pairing URI: fidobridge://pair?channel=<32 lc hex>&pubkey=<base64url 32>
  [&token=<jwt>][&v=3] — PROTOCOL.md v3.
- Relay: Centrifugo WebSocket; payloads sealed (Noise); relay sees ciphertext only.
- Permissions in manifest: CAMERA (QR), INTERNET, USE_BIOMETRIC,
  FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC, POST_NOTIFICATIONS.
- allowBackup=false. No Firebase/Analytics/Crashlytics/Sentry SDKs in app.
- Request log: in-memory only (rpId, type, outcome, timestamp); not uploaded.
- DiagnosticLogSink: **Play/release builds store diagnostics only on-device**
  and expose **Export diagnostics** to user storage. Relay publish
  (`fidobridge.log.<channel_id>`) is **debug/development only** — not used in
  the Play app. Must be disclosed accurately in the privacy policy.
- In-app "Reset app" erases pairing keys + stored credentials (not KeyStore
  hardware keys; not already-exported diagnostic files).
- App label today is still "FIDO Bridge" in strings.xml — docs must use
  Gatebridge; note rename as pre-screenshot polish (not this session's code work).
- Production managed relay host is `relay.gatebridge.app`
  (`wss://relay.gatebridge.app/connection/websocket`). Do not use the old
  personal/dev host in any product doc.
- Entitlement/backend for Play purchase → relay pairing is M1 (launch blocker);
  billing client gate is client-side only until backend exists.

## Voice & style
- Developer-tool SaaS: direct, concrete, no hype, no fake social proof.
- Prefer "security key" / "passkey" / "WebAuthn" as technical terms.
- Do not claim FIDO Alliance endorsement or certification.
- Short sentences. No emoji. No "revolutionary" / "military-grade".
- UK/US English: use plain international English (neutral).

## File map (writers produce exactly these paths)
| File | Owner task |
|---|---|
| playstore/README.md | index, decision log copy, launch checklist, Console sequence |
| playstore/console/listing.md | paste-ready Play store listing |
| playstore/console/compliance.md | Data Safety, rating, declarations, App Access text |
| playstore/console/review-kit.md | reviewer path, demo spec, script, fallback |
| playstore/console/assets.md | icon/feature graphic/screenshots specs + shot list |
| playstore/legal/privacy-policy.md | full draft policy for gatebridge.app/privacy |
| playstore/plans/billing.md | agent-ready Play Billing implementation spec |

## Cross-file conventions
- Privacy URL to use in listing/compliance: https://gatebridge.app/privacy
  (draft only until site PR; mark as "must be live before production track").
- Review kit URL: https://gatebridge.app/review (draft; same caveat).
- Product IDs always: gatebridge_individual_monthly, gatebridge_individual_yearly.
- Trial always: 1 month free, then paid; no permanent free tier.
- Package always: com.fidobridge.client.
- Managed relay always: `relay.gatebridge.app` / `wss://relay.gatebridge.app/connection/websocket`.
- Placeholders that the human fills: Play developer contact phone,
  legal entity name if required by Play.

## Sources writers should read (repo paths)
- /home/andre/dev/fido2android/BRAND.md
- /home/andre/dev/fido2android/BUSINESS_PLAN.md
- /home/andre/dev/fido2android/PROTOCOL.md (pairing URI §2, wire envelope §3)
- /home/andre/dev/fido2android/README.md
- /home/andre/dev/fido2android/android-fido-client/README.md
- /home/andre/dev/fido2android/android-fido-client/app/src/main/AndroidManifest.xml
- /home/andre/dev/fido2android/android-fido-client/app/src/main/java/com/fidobridge/client/networking/DiagnosticLogSink.kt
- /home/andre/dev/gatebridge-site/src/pages/open-source.astro
- /home/andre/dev/gatebridge-site/src/pages/security.astro
- /home/andre/dev/gatebridge-site/src/pages/company.astro
- /home/andre/dev/gatebridge-site/WEBSITE_PLAN.md (pricing/funnel)

## What this session must NOT do
- No Gradle/app code changes.
- No Play Console actions.
- No live site PRs (drafts only in playstore/).
- No renaming applicationId.

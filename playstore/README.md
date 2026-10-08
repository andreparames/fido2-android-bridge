# playstore/ — Gatebridge Play Store entry (docs only)

## What this is
Documentation package to prepare the Play Console listing for Gatebridge. No app code, no Play Console actions, no live site PRs in this package. Launch happens only when the managed relay is ready.

## Decision log
Copy the frozen decision table from plan.md:
- Product name Gatebridge
- Application ID com.fidobridge.client (kept)
- Play products gatebridge_individual_monthly, gatebridge_individual_yearly
- Trial 1 month free; no permanent free managed tier
- Monetization: Play Billing subscriptions for managed/hosted tier
- OSS path: GitHub Releases APK + self-hosted relay
- Category Business (fallback Tools)
- Biometric hardware required; Android 8.0+
- Managed relay host: relay.gatebridge.app
- Play diagnostics: local-only + Export diagnostics (relay diagnostic publish is debug-only)
- Launch posture: prepare now; publish when relay ready
- Support: hello@gatebridge.app; security@gatebridge.app

## Documents
| File | Purpose |
|---|---|
| [plan.md](plan.md) | Single source of truth for all writers and future agents |
| [console/listing.md](console/listing.md) | Paste-ready Play store listing (name, short/full description, tags) |
| [console/compliance.md](console/compliance.md) | Data Safety answers, content rating, declarations, App Access text |
| [console/review-kit.md](console/review-kit.md) | Reviewer path, demo relay spec, license testers, happy-path script |
| [console/assets.md](console/assets.md) | Icon 512, feature graphic 1024x500, screenshot shot list, polish notes |
| [legal/privacy-policy.md](legal/privacy-policy.md) | Draft policy for https://gatebridge.app/privacy |
| [plans/billing.md](plans/billing.md) | Agent-ready Play Billing + flavor + entitlement implementation spec |
| [plans/managed-relay.md](plans/managed-relay.md) | Managed special case: relay.gatebridge.app activate + Centrifugo subscribe proxy; classic JWT path elsewhere |
| [plans/backend.md](plans/backend.md) | Draft Gatebridge API service design (session/activate/webhook/RTDN) |
| [console/PLAY_CONSOLE.md](console/PLAY_CONSOLE.md) | Live Play products/prices/trial offer IDs (API-verified) |
| [console/PLAY_CONSOLE_CONFIG.md](console/PLAY_CONSOLE_CONFIG.md) | Play app config status: API-done vs Console UI remaining |
| [plans/invite-code.md](plans/invite-code.md) | Subscribe invite-code entitlement (review/partner path; Play session + activate) |

## Launch gate (publish only when ALL true)
- [ ] Managed relay + Centrifugo production endpoint live
- [ ] Entitlement backend issues pairing URIs for active Play subscriptions/trials
- [ ] play flavor APK: subscribe → pair → webauthn.io E2E green on device
- [ ] oss flavor APK: self-host path still green (regression)
- [ ] Release keystore + Play App Signing enrolled
- [ ] Privacy policy live at https://gatebridge.app/privacy
- [ ] Store listing + assets uploaded on Internal track
- [ ] License testers complete console/review-kit.md script
- [ ] Data Safety / rating / App Access submitted
- [x] Products created with P1M trial on monthly + yearly (see [console/PLAY_CONSOLE.md](console/PLAY_CONSOLE.md))
- [ ] Site "Get started" CTAs switch from waitlist → Play URL (site PR)
- [ ] GitHub Releases publish OSS APK + docs link to self-host

## Play Console sequence
1. Developer account; org/brand Gatebridge
2. Create app: name Gatebridge, package com.fidobridge.client, category Business
3. Privacy policy URL (after site publish)
4. Create subscription products (both IDs, P1M trial)
5. Upload assets (icon, feature graphic, screenshots)
6. Data Safety, content rating, target audience, encryption/ads/UGC declarations
7. App Access (from console/compliance.md + console/review-kit.md)
8. Internal testing track → license testers
9. Closed → Production (only after launch gate checklist)

## Out of scope here
- Live Play Console account actions
- Hosted relay operations
- Backend purchase verification implementation (see plans/billing.md §7; M1)
- Live site privacy/review pages (drafts in this folder; PR to gatebridge-site at launch)
- App code changes (flavors, billing UI, string rename)

## How to use
1. Humans fill remaining [OWNER: …] placeholders (Play contact phone, legal entity, Console prices, relay **infrastructure** log retention).
2. Managed relay host is live: `relay.gatebridge.app`.
3. Play/release app: diagnostics stay local; implement **Export diagnostics**; do not publish diagnostics to the relay in Play builds.
4. Implement plans/billing.md with a coding agent (products already live — see console/PLAY_CONSOLE.md).
5. Implement backend per plans/backend.md / plans/managed-relay.md §4.
6. Publish legal/privacy-policy.md and review-kit content to gatebridge.app.
6. Follow the Play Console sequence above.

# PLAY_CONSOLE_CONFIG — Play Store app configuration

**Package:** `com.fidobridge.client`  
**App name (store / device label):** **Gatebridge**  
**Key:** `~/.config/gatebridge/play-service-account.json`  
**Helper:** `python3 scripts/play_api.py list`

---

## Done via Android Publisher API

| Item | Status |
|---|---|
| Play app exists for `com.fidobridge.client` | Yes (API reachable) |
| Subscription `gatebridge_individual_monthly` | ACTIVE — base plan `monthly` — US **$1.99** / P1M |
| Subscription `gatebridge_individual_yearly` | ACTIVE — base plan `yearly` — US **$19.99** / P1Y |
| Free trial offer monthly | `monthly-free-trial` ACTIVE — 1× P1M free (new users) |
| Free trial offer yearly | `yearly-free-trial` ACTIVE — 1× P1M free (new users) |
| Product listing title/description/benefits (en-US) | Updated via API (`updateMask=listings`) |

Subscription listing copy (Play product pages — **not** the main store listing):

- **Monthly:** Gatebridge Individual (monthly) — phone as hardware security key for remote Linux; managed relay; auto-updates; email support; 1 month free then Play price.
- **Yearly:** Gatebridge Individual (yearly) — same; yearly Play price after trial.

---

## Console UI — still required (not exposed on this API)

Copy from `playstore/console/listing.md`, `playstore/console/compliance.md`, `playstore/console/assets.md`, `playstore/legal/privacy-policy.md`.

### Store listing (main app)
| Field | Value / source |
|---|---|
| App name | Gatebridge |
| Short description (≤80) | From `playstore/console/listing.md` (77-char draft) |
| Full description | `playstore/console/listing.md` full description |
| Graphic assets | Icon 512, feature graphic 1024×500, 4–8 screenshots — `playstore/console/assets.md` |
| Privacy policy URL | `https://gatebridge.app/privacy` (**must be live first**) |
| Website | `https://gatebridge.app` |
| Email | hello@gatebridge.app |
| Category | Business (fallback Tools) |
| Tags | security, passkeys, fido2, webauthn, mfa, linux, server, two-factor |

### Declarations
| Item | Source |
|---|---|
| Data Safety | `playstore/console/compliance.md` §2 |
| Content rating (IARC) | Everyone / PEGI 3 |
| Target audience | Not children; no Families |
| Encryption / ads / UGC | `playstore/console/compliance.md` §3 |
| App Access | `playstore/console/compliance.md` §4 + `playstore/console/review-kit.md` |
| License testers | Console → Settings → License testing |

### Tracks
| Track | Notes |
|---|---|
| Internal | Upload `play` flavor AAB/APK after Play App Signing |
| Closed | Waitlist cohort later |
| Production | Only after launch gate in `playstore/README.md` |

### Monetization Console extras
| Item | Notes |
|---|---|
| Merchant / payments profile | Required before paid production |
| Base plan states | Already ACTIVE via API |
| Regional prices | Only **US** set via API; add more regions in Console if needed |
| Offer targeting | New users, this subscription (set via API) |

---

## App code alignment (kept from Play-prep work)

| Piece | Location |
|---|---|
| Flavors `oss` / `play` | `android-fido-client/app/build.gradle.kts` |
| Play Billing (play only) | `src/play/.../PlayBillingSubscriptionRepository.kt` |
| OSS always-entitled | `src/oss/...` + `AlwaysEntitledSubscriptionRepository` |
| Product IDs | `gatebridge_individual_monthly`, `gatebridge_individual_yearly` |
| Device label | `strings.xml` → **Gatebridge** |
| Managed relay default (play) | `wss://relay.gatebridge.app/connection/websocket` |

CI now builds **oss** variants (`assembleOssDebug` / `testOssDebugUnitTest`).

---

## Play Console checklist (human)

- [ ] Create/confirm app title **Gatebridge** in store listing  
- [ ] Paste short + full description from `playstore/console/listing.md`  
- [ ] Upload icon + feature graphic + screenshots  
- [ ] Set privacy policy URL (after site `/privacy` is live)  
- [ ] Data Safety + content rating + audience + declarations  
- [ ] App Access + review kit path  
- [ ] License testers  
- [ ] Payment merchant profile  
- [ ] Play App Signing + upload key  
- [ ] Upload **play** flavor build to Internal track  
- [ ] Complete review-kit script on a device  

Products + trial offers: **already configured** (see API section above).

# Live Play state (via service account)

**Updated:** after creating products with `scripts/play_api.py`  
**Package:** `com.fidobridge.client`  
**Key:** `~/.config/gatebridge/play-service-account.json` (not in git)  
**API:** Android Publisher `androidpublisher.v3`  
**Regions version used on create:** `2025/03` (API reported latest `2026/01` at the time)

## Subscriptions (ACTIVE)

| productId | basePlanId | Period | US price | Grace (Play default) |
|---|---|---|---|---|
| `gatebridge_individual_monthly` | `monthly` | P1M | **$1.99** (`units=1`, `nanos=990000000`) | P7D |
| `gatebridge_individual_yearly` | `yearly` | P1Y | **$19.99** (`units=19`, `nanos=990000000`) | P14D |

Listing language: `en-US` — Gatebridge Individual; benefits = managed relay, auto-updates, email support; copy states 1 month free trial then Play price.

## Free-trial offers (ACTIVE)

| productId | basePlanId | offerId | Phase | Targeting |
|---|---|---|---|---|
| `gatebridge_individual_monthly` | `monthly` | `monthly-free-trial` | 1 × P1M free (US) | new users, this subscription |
| `gatebridge_individual_yearly` | `yearly` | `yearly-free-trial` | 1 × P1M free (US) | new users, this subscription |

Billing client should prefer offer tokens whose `freeTrialPeriod == "P1M"` (`playstore/plans/billing.md`).

## Helper

```bash
python3 scripts/play_api.py list
python3 scripts/play_api.py get gatebridge_individual_monthly
python3 scripts/play_api.py offers gatebridge_individual_monthly monthly
```

Env: `GB_PLAY_KEY` (optional), `GB_PLAY_PACKAGE`, `GB_PLAY_REGIONS_VERSION`.

## Still Console-UI / not this API

- App Access, Data Safety, content rating, store listing assets  
- License testers / Internal track  
- Payment merchant profile  
- Non-US regional prices (only US configured via API)  
- Release keystore / Play App Signing enrollment  

## Launch-gate checkbox

`playstore/README.md`: **Products created with P1M trial on monthly + yearly** — **done** (see this file).

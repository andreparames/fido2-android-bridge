# BILLING_PLAN — Gatebridge Play subscription (implementation spec)

Audience: an independent coding agent who will implement Play Billing later.
Grounding: `playstore/plan.md` is frozen truth. Wire/pairing formats: `PROTOCOL.md` §2.
Pricing copy: "1 month free, then the price shown in Google Play" (live Console prices at launch; do not hardcode price numbers in app copy). Package name stays `com.fidobridge.client`.

---

## 0. Frozen decisions

| Decision | Value |
|---|---|
| Product IDs | `gatebridge_individual_monthly`, `gatebridge_individual_yearly` |
| Trial | 1 month free (`P1M`) on both products; **no** permanent free managed tier |
| Package | `com.fidobridge.client` on both flavors |
| Billing | Play Billing only on the **play** flavor |
| Managed tier sales | Google Play only — **no** external checkout for the hosted relay |
| OSS path | GitHub APK + user self-hosted relay (open-core) |
| Product name in UI/docs | Gatebridge (not "FIDO Bridge") |
| Managed relay host | `relay.gatebridge.app` — WebSocket `wss://relay.gatebridge.app/connection/websocket` (same host as app `BuildConfig.RELAY_URL` default) |
| Client gate vs server entitlement | Client billing check is necessary but **not sufficient**; server-side entitlement is a launch blocker |

Constraints the implementer must not violate:

- No Play Billing code on the oss flavor; no sideload unlock path for the hosted relay.
- No permanent free managed tier anywhere in UI or product IDs.
- applicationId remains `com.fidobridge.client` — do not propose a rename.
- No emoji in UI copy or this plan's product-facing strings.
- Fail closed on billing uncertainty after grace.

---

## 1. Architecture overview

Two distribution flavors, same applicationId, mutually exclusive installs:

| Flavor | Distribution | Relay | Monetization |
|---|---|---|---|
| **play** | Google Play | Managed hosted relay at `wss://relay.gatebridge.app/connection/websocket` (env override `GATEBRIDGE_MANAGED_RELAY_URL` for non-prod) | Play Billing subscriptions gate access |
| **oss** | GitHub Releases APK | User self-hosted Centrifugo relay (`FIDO2_RELAY_URL` env override as today) | Free; `AlwaysEntitled` client-side |

Behavior differences:

- **play:** managed relay + subscription gate. Entitlement is required before pairing and before any foreground service / `BridgePipeline.start()`.
- **oss:** `BuildConfig.RELAY_URL` env override (current behavior). Entitlement layer binds to `AlwaysEntitledSubscriptionRepository` — no billing mocks required anywhere downstream.
- **Same applicationId:** `com.fidobridge.client` for both. Installing one flavor updates/replaces the other; users must not be able to mix "Play app + external unlock" or "OSS app + managed relay" in a way that bypasses billing.
- **Client gate is necessary, not sufficient.** The play client checks BillingClient state to decide UI/pipeline start. Long-term relay auth depends on **server entitlement** (§7). Client-only entitlement must never be treated as a production auth story for the managed Centrifugo channel.

---

## 2. Gradle changes

### 2.1 `android-fido-client/gradle/libs.versions.toml`

Add a version alias for Play Billing KTX (pin the current **stable 8.x** at implementation time — do not guess a patch that does not exist when you build):

```toml
[versions]
# ... existing ...
billingKtx = "8.0.0"  # confirm latest stable 8.x on implementation day

[libraries]
billing-ktx = { group = "com.android.billingclient", name = "billing-ktx", version.ref = "billingKtx" }
```

Do **not** add billing as a plain `implementation` — it must be flavor-scoped (below).

### 2.2 `android-fido-client/app/build.gradle.kts`

Add flavor dimension and product flavors. Keep `applicationId = "com.fidobridge.client"` in `defaultConfig`. Move/keep `RELAY_URL` per flavor.

```kotlin
android {
    // ... existing namespace, compileSdk, minSdk 26, targetSdk 34 ...

    flavorDimensions += "distribution"

    productFlavors {
        create("oss") {
            dimension = "distribution"
            buildConfigField("String", "MANAGED_RELAY", "false")
            buildConfigField("boolean", "PLAY_BILLING_REQUIRED", "false")
            // OSS: keep current env-driven relay behavior (see relayUrl() below)
            buildConfigField(
                "String",
                "RELAY_URL",
                "\"${escapeForBuildConfig(relayUrlOss())}\""
            )
        }
        create("play") {
            dimension = "distribution"
            buildConfigField("String", "MANAGED_RELAY", "true")
            buildConfigField("boolean", "PLAY_BILLING_REQUIRED", "true")
            // Production managed relay: wss://relay.gatebridge.app/connection/websocket
            buildConfigField(
                "String",
                "RELAY_URL",
                "\"${escapeForBuildConfig(relayUrlPlay())}\""
            )
        }
    }

    // ... existing buildTypes, compose, buildConfig = true ...
}

dependencies {
    // ... existing common deps ...

    // Play Billing only on play flavor
    "playImplementation"(libs.billing.ktx)

    // testImplementation mockk/turbine/junit unchanged; tests for OSS
    // entitlement must not require billing mocks on the classpath for oss
    // unit tests that bind AlwaysEntitled.
}

fun relayUrlOss(): String =
    System.getenv("FIDO2_RELAY_URL")
        ?: "wss://localhost:9000/connection/websocket" // env-first; local Centrifugo default for self-host/dev

fun relayUrlPlay(): String =
    System.getenv("GATEBRIDGE_MANAGED_RELAY_URL")
        ?: "wss://relay.gatebridge.app/connection/websocket" // production managed relay

fun escapeForBuildConfig(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")
```

Implementation notes for the coding agent:

- Build flavors: `ossDebug`, `ossRelease`, `playDebug`, `playRelease`. CI and local runs must name the variant explicitly.
- `play` flavor is the only variant that compiles `com.android.billingclient` types. If a shared source file references BillingClient directly, move it under `src/play/java/...`.
- oss keeps current `FIDO2_RELAY_URL` env behavior. play reads `GATEBRIDGE_MANAGED_RELAY_URL` with default `wss://relay.gatebridge.app/connection/websocket`; release builds use that production host unless a staging override is injected.
- `MANAGED_RELAY` is a string build-config field for Hilt/source-set branching consistency with existing string `RELAY_URL` style; `PLAY_BILLING_REQUIRED` is boolean and is the flag Hilt uses to choose `SubscriptionRepository` bindings.
- Do not change `applicationId`, `namespace`, `versionCode`, or biometric permissions as part of billing work except where §5 copy rename requires string edits.

---

## 3. Domain model (package `billing/`)

All new types live in `com.fidobridge.client.billing`. Prefer JVM-friendly pure Kotlin where possible so unit tests run on the desktop JVM without Robolectric unless a type inherently needs Android APIs.

### 3.1 Entitlement status

```kotlin
package com.fidobridge.client.billing

enum class EntitlementStatus {
    LOADING,
    ENTITLED,
    NOT_ENTITLED,
    BILLING_UNAVAILABLE,
    ERROR
}
```

Semantics:

- `LOADING` — first query in flight; UI shows spinner (§5).
- `ENTITLED` — active subscription **or** active free trial (`P1M`) from Play (or OSS always-entitled).
- `NOT_ENTITLED` — known inactive: no purchase, expired, or cancelled and past grace.
- `BILLING_UNAVAILABLE` — Play Billing unavailable (no Google Play, library init failure, unsupported device context). Show help link; do not silently treat as entitled.
- `ERROR` — unexpected client failure. Fail closed for pipeline start after refresh.

### 3.2 Product model

```kotlin
data class SubscriptionProduct(
    val productId: String,
    val title: String,
    val formattedPrice: String,
    val billingPeriod: String,        // e.g. "P1M", "P1Y" from Play
    val freeTrialPeriod: String?,     // "P1M" on both products when configured
    val offerToken: String            // from subscriptionOfferDetails
)
```

Only these productIds are legal: `gatebridge_individual_monthly`, `gatebridge_individual_yearly`.

### 3.3 Entitlement model

```kotlin
data class Entitlement(
    val status: EntitlementStatus,
    val productId: String? = null,
    val isTrial: Boolean = false,
    val expiryEpochMs: Long? = null,
    val purchaseToken: String? = null   // kept in memory / Play; never logged
)
```

### 3.4 Repository interfaces

```kotlin
interface SubscriptionRepository {
    val entitlement: StateFlow<Entitlement>
    suspend fun refresh()
    suspend fun queryProducts(): Result<List<SubscriptionProduct>>
    suspend fun launchPurchase(activity: Activity, productId: String)
    suspend fun restorePurchases()
    suspend fun acknowledgeIfRequired()
}
```

- `entitlement` is the single client-side source of truth for UI and the pipeline gate.
- `refresh()` re-queries purchases/products; called on app start, after listener events, and when entering settings/subscribe screens.
- `acknowledgeIfRequired()` acknowledges unacknowledged purchases (Play policy: purchases must be acknowledged within the grace window or Play refunds them).
- `launchPurchase` / `restorePurchases` may no-op or return errors on OSS impl (AlwaysEntitled) — UI must not show purchase CTA on oss.

### 3.5 Implementations

**`PlayBillingSubscriptionRepository`** (play source set or flavor-guarded):

- Wraps `BillingClient` with `PurchasesUpdatedListener` and `BillingFlowParams` / `ProductDetails` / `Purchase` / `AcknowledgePurchaseParams`.
- `startConnection` on init or first use; reconnect on `onBillingServiceDisconnected()`.
- `queryProductDetailsAsync` for both product IDs; parse `subscriptionOfferDetails`.
- Prefer the offer with `freeTrialPeriod == "P1M"`; store that offer's `offerToken` on `SubscriptionProduct`.
- Map `Purchase.PurchaseState` → `Entitlement`: `PURCHASED` → entitled (check trial window if applicable); `PENDING` → not yet entitled for pipeline start (conservative); `UNSPECIFIED_STATE` → refresh again.
- After successful purchase listener path: if `purchase.purchaseState == PURCHASED` and `!purchase.isAcknowledged`, call acknowledge; then update `entitlement`.
- Do not log `purchaseToken` or full JWT relay tokens (§10).

**`AlwaysEntitledSubscriptionRepository`** (oss):

```kotlin
class AlwaysEntitledSubscriptionRepository : SubscriptionRepository {
    // entitlement fixed to ENTITLED; queryProducts empty list;
    // launchPurchase / restorePurchases / acknowledgeIfRequired no-ops
}
```

**Hilt binding — `BillingModule`:**

```kotlin
@Module
@InstallIn(SingletonComponent::class)
abstract class BillingModule {
    @Binds
    @Singleton
    abstract fun bindSubscriptionRepository(
        impl: PlayBillingSubscriptionRepository
    ): SubscriptionRepository
}
```

Actual binding must branch on `BuildConfig.PLAY_BILLING_REQUIRED`. Preferred pattern: two modules, one per source set:

- `src/main/java/.../di/SubscriptionModule.kt` — interface only / common.
- `src/oss/java/.../di/SubscriptionModule.kt` — binds `AlwaysEntitledSubscriptionRepository`.
- `src/play/java/.../di/SubscriptionModule.kt` — binds `PlayBillingSubscriptionRepository`.

If you keep a single module in `main`, branch on `BuildConfig.PLAY_BILLING_REQUIRED` and keep all `BillingClient` imports inside the play branch (may require source-set split anyway to avoid oss compile refs).

### 3.6 Optional gate type

A thin collaborator for pipeline/service start checks:

```kotlin
class EntitlementGate(private val repository: SubscriptionRepository) {
    val isEntitled: Boolean
        get() = repository.entitlement.value.status == EntitlementStatus.ENTITLED
}
```

Pipeline and service may depend on `SubscriptionRepository` directly or on `EntitlementGate`; pick one style and stay consistent (tests in §8 assume one injectable gate).

---

## 4. Purchase flow (play)

Sequence for the **play** flavor only:

1. **Connect BillingClient** and register `PurchasesUpdatedListener`. On `BillingResponseCode.OK` / errors, refresh entitlement state.
2. **Query products:** `queryProductDetailsAsync` for `gatebridge_individual_monthly` and `gatebridge_individual_yearly`. Parse `subscriptionOfferDetails`. Prefer the offer whose `freeTrialPeriod` is `P1M`; fall back to base plan details if trial offer missing (still show price; do not invent trial copy).
3. **UI listing:** SubscribeScreen shows both plans with Play `formattedPrice` and trial copy "1 month free, then {formattedPrice}" (exact price numbers come from Play Console at launch; never hardcode `$2` in strings).
4. **Launch billing:** `launchBillingFlow` with the selected product's `offerToken` (from the trial-preferring offer).
5. **Listener handling:**
   - `OK` + purchase present → verify `productId` is one of the two legal IDs → if `UNACKNOWLEDGED`, acknowledge → update `Entitlement` → navigate to next intended screen (PAIRING if pairing pending, else HOME).
   - `USER_CANCELED` → stay on SubscribeScreen; no error dialog required (optional neutral status).
   - `ITEM_ALREADY_OWNED` → `queryPurchasesAsync` / restore path; if active purchase found, proceed as owned.
   - `BILLING_UNAVAILABLE` → show help link (Play Store / Play services guidance); stay on Subscribe.
   - Other codes → map to `EntitlementStatus.ERROR` / user-visible short message; fail closed for pipeline.
6. **Never trust client-only entitlement long-term for relay auth.** Client state gates local UI and local pipeline start. Managed relay authorization requires server entitlement (§7). Do not implement "local purchaseToken forever equals relay access" as the production story.

---

## 5. Navigation / UI

### 5.1 Current baseline

`FidoBridgeApp` today:

```kotlin
object Routes {
    const val HOME = "home"
    const val PAIRING = "pairing"
}
// startDestination = if (appViewModel.isPaired) Routes.HOME else Routes.PAIRING
```

Pairing success currently does `context.startForegroundService(Intent(context, FidoBridgeService::class.java))` then navigates HOME. Error dialog title today is `"FIDO Bridge error"` — must become `"Gatebridge"` for playstore screenshots and brand (playstore/plan.md frozen brand).

### 5.2 New start-destination matrix

Compute from `SubscriptionRepository.entitlement` + pairing state:

| Entitlement status | Paired? | Start destination | Notes |
|---|---|---|---|
| `LOADING` | any | spinner / neutral gate screen | Do not start service |
| `NOT_ENTITLED` | any | `Routes.SUBSCRIBE` | play only path shown |
| `BILLING_UNAVAILABLE` | any | `Routes.SUBSCRIBE` | Include help link |
| `ERROR` | any | `Routes.SUBSCRIBE` or error dialog + retry | Fail closed |
| `ENTITLED` | no | `Routes.PAIRING` | Safe to pair; service start only after paired + entitled |
| `ENTITLED` | yes | `Routes.HOME` | Normal operation |

Illustrative routing (agent implements in `FidoBridgeApp` / `AppViewModel`):

```kotlin
val startDestination = when (entitlement.status) {
    EntitlementStatus.LOADING -> Routes.LOADING
    EntitlementStatus.ENTITLED -> if (isPaired) Routes.HOME else Routes.PAIRING
    else -> Routes.SUBSCRIBE
}
```

### 5.3 Screens

- **`SubscribeScreen` + `SubscribeViewModel`**
  - Lists monthly + yearly via `SubscriptionRepository.queryProducts()`.
  - Price + "1 month free, then {price}" per plan.
  - Restore purchases button.
  - Link to Play subscription management (intent `https://play.google.com/store/account/subscriptions` or the Play Billing `SubscriptionManager` equivalent available at implementation time; document the exact intent in code comments only if needed — no extra docs).
  - Error/help state for `BILLING_UNAVAILABLE`.
- **`Routes.LOADING`** (or reuse a simple full-screen progress composable) for `LOADING`.
- **Pairing gate:** play does **not** start `FidoBridgeService` / does **not** call `BridgePipeline.start()` unless status is `ENTITLED`. oss `AlwaysEntitled` keeps current start behavior.
- **Copy rename (pre-screenshot polish, part of this billing work order):**
  - `"FIDO Bridge error"` → `"Gatebridge"`.
  - Add subscription-expired message, e.g. "Your Gatebridge subscription is not active. Renew in Google Play to use the managed relay."
  - App label/strings: Gatebridge in user-facing copy; `applicationId` unchanged.
- **Home banner:** when entitlement drops mid-session (`ENTITLED` → `NOT_ENTITLED`), show a non-blocking banner on HOME linking to Subscribe; pipeline stops per §6.

### 5.4 oss vs play UI

- oss: no Subscribe CTA, no billing errors. Navigation can stay `PAIRING`/`HOME` based on pairing only (entitlement always `ENTITLED`).
- play: full matrix above.

---

## 6. Pipeline / service integration

### 6.1 BridgePipeline

Inject `SubscriptionRepository` or `EntitlementGate` into `BridgePipeline` (constructor + `DataModule.provideBridgePipeline`).

- **play:** if not `ENTITLED` at `start()` / `startInternal()`, do **not** create transport. `fail("subscription required")` (or equivalent `BridgeState.Error`) before any relay connect. Diagnostic lines on play/release are local-only (no relay publish); do not log tokens.
- **On expiry while connected:** collect `repository.entitlement` in pipeline scope. Transition to `NOT_ENTITLED` (or `BILLING_UNAVAILABLE` past grace) → `stop()` + surface error to UI. Do not keep a live relay session for a known-not-entitled user.
- **oss:** `AlwaysEntitled` → pipeline behaves as today (no billing branch taken at runtime beyond the gate always true).

### 6.2 FidoBridgeService

`FidoBridgeService` checks entitlement **before** `pipeline.start()` (line ~41 today). If not entitled: do not start foreground; log a safe diagnostic line; return/stop service start path. Pairing screen success path in UI must not `startForegroundService` on play when not entitled (already enforced by navigation matrix; service is defense in depth).

### 6.3 DataModule / relay URL

- play: `relayUrl = BuildConfig.RELAY_URL` from managed env placeholder (§2). Production release job injects real host.
- oss: unchanged env override pattern `FIDO2_RELAY_URL`.
- **DiagnosticLogSink:** Play/release builds must keep diagnostics **on-device only** and support **Export diagnostics** to user storage. Do not publish diagnostics to the relay in release/Play variants (relay `fidobridge.log.<channel_id>` is debug/dev only). Do not log tokens or `purchaseToken` in any sink. If a sink is no-op on play release, that is correct — billing work must not re-enable relay diagnostics.

### 6.4 Identity / pairing secrets

Pairing URI secrets (channel, static pubkey, relay token) continue to live only in `EncryptedSharedPreferences` via `IdentityStore` / `EncryptedIdentityStore` (`fidobridge_identity`). Server-issued pairing URIs for managed tier must follow `PROTOCOL.md` §2 and be stored through the same store — not in plain prefs, not in billing repository state.

---

## 7. Server-side entitlement (launch blocker)

Client billing check is insufficient for managed relay security. Required **before production track**.

**Primary managed UX (authoritative):** [`managed-relay.md`](managed-relay.md).

- Relay host `relay.gatebridge.app` is a **special case** only; any other relay host uses the classic JWT path and **must not** call Gatebridge APIs.
- App (entitled) `POST /v1/channels/activate { channel }` after Play verify; Centrifugo **subscribe proxy** asks `POST /centrifugo/subscribe` on every subscribe; daemon only **polls subscribe** until allowed.
- Pairing QR stays PROTOCOL `fidobridge://pair?channel&pubkey&v=3` (token optional). **No** channel-status API; **no** backend-delivered phone pubkey; Noise TOFU unchanged.
- Backend **storage/schema is implementer’s choice** from that plan’s API contract (no fixed SQL model in this doc).

Also required before production track:

1. **Play Real-time Developer Notifications (RTDN)** → backend purchase state (created, renewed, cancelled, expired, refunded, chargeback).
2. **Backend mapping:** Play entitlement ↔ allowed relay channel(s) (see MANAGED_RELAY_PLAN §4).
3. **Play Billing client** (this document): subscribe UI, trial, restore, local entitlement gate; `EntitlementBackend.activate(channel)` behind a fake in JVM tests until the API is live.

### 7.1 Play Billing ↔ activate (client contract)

```http
POST /v1/play/session
{ "productId": "gatebridge_individual_monthly",
  "purchaseToken": "...",
  "packageName": "com.fidobridge.client" }
→ { "sessionToken": "...", "entitled": true, "isTrial": true }

POST /v1/channels/activate
Authorization: Bearer <sessionToken>
{ "channel": "<32 lc hex>" }
→ { "channel": "...", "status": "active" }
```

Full webhook/`fidobridge:` prefix rules: MANAGED_RELAY_PLAN §4.

Pairing URI parsing (classic **and** managed) still follows `PROTOCOL.md` §2:

- `channel`: 32 **lowercase** hex chars (`[0-9a-f]{32}`).
- `pubkey`: base64url, unpadded, decodes to exactly 32 bytes (X25519 static public key).
- `token`: optional Centrifugo connection JWT (compact serialization); **omit on managed bootstrap**.
- `v`: present `3` or absent (defaults to 3). Wrong version → reject.

### 7.2 Centrifugo token policy (classic / self-host)

- Self-host Centrifugo: user-managed JWT/token as today; no Gatebridge subscribe proxy required.
- Managed Centrifugo: do **not** issue per-channel subscription JWTs for `fidobridge:*` (proxy is the gate). Connection HMAC stays server-side only.

### 7.3 Client isolation

- Isolate backend calls behind an `EntitlementBackend` interface (play source set). Ship a fake implementation for unit tests.
- Classic/OSS path: no call to Gatebridge activate/session APIs; pairing remains user-local daemon pairing.
- Do not put backend host secrets or long-lived Centrifugo credentials in the app.

---

## 8. Test plan (TDD)

Write tests before or with each implementation step. Unit tests are JVM (`testImplementation` junit, mockk, turbine, kotlinx-coroutines-test already present).

### 8.1 Unit JVM

| Test | Asserts |
|---|---|
| `FakeSubscriptionRepository` variants | entitled / not entitled / billing unavailable / loading / error StateFlows |
| `SubscribeViewModelTest` | product list rendering state; restore triggers repository; launchPurchase delegates; BILLING_UNAVAILABLE shows help; USER_CANCELED stays |
| `EntitlementGateTest` | pipeline blocked when not entitled; allowed when entitled; oss AlwaysEntitled allows pipeline without billing mocks |
| `NavigationRoutingTest` | full matrix §5.2 (status × paired → route) |
| `PurchaseListenerMappingTest` | mock/fake BillingClient listener mapping: OK→entitled, CANCELED→stay, ALREADY_OWNED→restore, BILLING_UNAVAILABLE→help; productId allow-list; acknowledge-if-required called for UNACKNOWLEDGED |
| `AlwaysEntitledSubscriptionRepositoryTest` | always `ENTITLED`; no-op purchase APIs |

OSS flavor tests must run without `com.android.billingclient` on the test classpath for modules that bind AlwaysEntitled. Play repository tests may use mocks/fakes around listener callbacks; avoid requiring a real Play Store on CI.

### 8.2 Device / manual

- License tester manual script from `playstore/console/review-kit.md` (not CI).
- Steps covered on device: subscribe trial → pair → pipeline connects; cancel/resubscribe; expiry simulation via license tester purchase expiry if available; restore purchases on new install; oss build still pairs with self-hosted relay.
- Verify Play purchase states on a real device with license tester accounts only — never production test purchases on shared devices without reset.

---

## 9. Play Console ops checklist

Implementer / product owner (not the coding agent in a unit-test session) must complete before production:

1. **Create both subscription products** with exact IDs:
   - `gatebridge_individual_monthly`
   - `gatebridge_individual_yearly`
2. **Base plans:** monthly + yearly. Configure `freeTrialPeriod` = **1 month (`P1M`)** on both.
3. **Grace:** conservative billing grace, e.g. **3 days** (align with fail-closed client behavior §10; confirm final value in Console).
4. **Listing prices:** Play app listing / in-app copy must match Console live prices. Docs and UI may only say "1 month free, then the price shown in Play".
5. **License testing:** add license tester emails (team + QA). Test all flows under license testers before production.
6. **Promo codes:** optional — useful for waitlist/manual support; not required for v1 client.
7. **Payments profile / merchant account:** must be active before production track.
8. **App Access / review notes:** provide test instructions that do not require external payment paths; managed relay is Play-only. Privacy URL must be live (`https://gatebridge.app/privacy` per playstore/plan.md cross-file convention) before production.
9. **Internal testing track first:** install play flavor, buy/trial, pair, complete a WebAuthn flow against managed relay once server entitlement (§7) exists.

---

## 10. Security / policy constraints

- **No Play Billing on oss.** No code path on oss grants hosted-relay entitlement via sideload, fake purchase, or client flag.
- **No external checkout for managed tier** in the Play app. Do not deep-link to non-Play payments for hosted relay access.
- **Never log** `purchaseToken` or full JWT relay tokens. Logging of `productId`, coarse status, and product price strings is fine.
- **Fail closed:** if billing query fails after grace (unknown purchase state), pipeline **stops**. Do not default to entitled on error.
- **Pairing URI / relay token secrecy:** server-issued pairing material goes only into `EncryptedSharedPreferences` via `IdentityStore` / `EncryptedIdentityStore`. Not into log files, crash breadcrumbs, or plain `SharedPreferences`.
- **applicationId** stays `com.fidobridge.client`.
- **Client entitlement ≠ production relay auth.** Server must enforce subscription/trial on channel/token issuance (§7).
- **Diagnostics:** Play/release = local only + **Export diagnostics**; no relay diagnostic publish in Play builds. Debug may use `fidobridge.log.<channel_id>`. Billing work must not expand what diagnostics include (no tokens, no purchaseToken).
- **Brand:** user-facing strings Gatebridge; no emoji; no FIDO Alliance endorsement claims.

---

## 11. Implementation order (for the coding agent)

1. **Flavor skeleton** — `flavorDimensions "distribution"`, `productFlavors oss/play`, `MANAGED_RELAY` / `PLAY_BILLING_REQUIRED` BuildConfig fields, `playImplementation(libs.billing.ktx)`, per-flavor `RELAY_URL` (play env placeholder; oss keeps `FIDO2_RELAY_URL`). Confirm both variants compile.
2. **Domain + OSS binding** — `EntitlementStatus`, `SubscriptionProduct`, `Entitlement`, `SubscriptionRepository`, `AlwaysEntitledSubscriptionRepository`, Hilt module(s) with `PLAY_BILLING_REQUIRED` branch. Unit-test AlwaysEntitled + routing matrix.
3. **Play Billing impl** — `PlayBillingSubscriptionRepository`: connection, product query, `P1M` offer preference, purchase launch, acknowledge, restore, listener mapping tests.
4. **Subscribe UI** — `SubscribeScreen`, `SubscribeViewModel`, `Routes.SUBSCRIBE` / `Routes.LOADING`, navigation matrix, restore + Play subscriptions intent, help link for `BILLING_UNAVAILABLE`.
5. **Entitlement gate in pipeline/service** — inject gate; block `BridgePipeline.start()` / `FidoBridgeService` when not entitled on play; collect expiry while connected → `stop()` + error; Home banner when entitlement lost mid-session; brand copy rename "FIDO Bridge error" → "Gatebridge" + subscription-expired message.
6. **Backend client interface + pairing fetch** — `EntitlementBackend` play-only; mock in tests; POST entitlement + GET pairing after subscribe; parse URI per `PROTOCOL.md` §2 into `IdentityStore`.
7. **Brand rename strings** — Gatebridge user-facing strings for Play screenshots (label/strings.xml polish per playstore/plan.md; applicationId unchanged).
8. **Device pass** — license tester + `playstore/console/review-kit.md` script; oss build regression with self-hosted relay.
9. **Production release prep** — confirm play flavor `RELAY_URL` is `wss://relay.gatebridge.app/connection/websocket` (or explicit staging env); Play App Signing; complete §9 Console checklist; only then production track.

### Done criteria (client side)

- Both flavors build; oss has zero billing dependencies.
- play: subscribe trial → entitled → pair → pipeline connects to managed URL.
- play: not entitled → Subscribe only; no foreground service; pipeline Error("subscription required").
- Unit tests in §8.1 green on JVM.
- No `purchaseToken` / JWT in logs.
- Docs/copy: exact product IDs, P1M trial, no permanent free tier, Gatebridge branding, no emoji; managed relay host is `relay.gatebridge.app` only.

### Explicitly out of scope for this client doc

- Backend implementation details beyond the illustrative contract in §7.1.
- Team/Business Play products (not in frozen product IDs for this plan).
- Renaming applicationId.

# INVITE_CODE_PLAN — Subscribe “invite code” entitlement

**Status:** Design frozen for implementation  
**Audience:** coding agents (inspect code; implement from this contract)  
**Related:** `playstore/plans/billing.md`, `playstore/plans/managed-relay.md`, `playstore/plans/backend.md`, `playstore/console/review-kit.md`

---

## 1. Purpose

Add a small control on the **play** Subscribe screen so invited users (Play reviewers, support, partners, waitlist) can become entitled **without a real Play purchase**, using a pre-shared **8-digit invite code**.

The code exercises the **same** backend session + channel activate path as a paid subscription. It is **not** a public free managed tier and must not appear as one in the store listing.

**Why:** Play app reviewers are anonymous (no license-tester email). Invite codes + App Access give a review path that still uses the **production `play` APK** (billing code present), not a stripped “different app” Internal build.

---

## 2. Frozen naming

| Surface | Value |
|---|---|
| UI button | **Have an invite code?** |
| Dialog title | **Enter invite code** |
| Helper text | For invited, demo, or partner access. Not a public free tier. |
| JSON key | **`inviteCode`** |
| Value format | **8 decimal digits** (`^[0-9]{8}$`) |
| `entitlementKind` (response) | **`invite_code`** (Play path may use `"play"` or omit) |
| Backend env / config | **`GATEBRIDGE_INVITE_CODES`** (comma-separated list or file path) |
| Client field | `Entitlement.inviteCode` — **in-memory only**, never logged, never persisted |
| ViewModel method | `submitInviteCode(code: String)` |
| Error reason | `invalid_invite_code` |

Product IDs for real Play billing remain `gatebridge_individual_monthly` / `gatebridge_individual_yearly`. Invite path does **not** create a Play product.

---

## 3. User-facing flow (play flavor)

```
SubscribeScreen (not entitled)
  → products + Buy (Play Billing) + Restore + Manage
  → small button: "Have an invite code?"
  → dialog: enter 8 digits → Activate
  → POST /v1/play/session { "inviteCode": "…" }
  → 200 { sessionToken, entitled: true, entitlementKind: "invite_code" }
  → client Entitlement = ENTITLED (inviteCode held in memory)
  → navigate as after Buy (Pairing if unpaired, else Home)
  → user scans fidobridge://pair?channel=C&pubkey=P&v=3
  → ManagedPairingGate (managed host only):
        activate(C) → POST session { inviteCode } → POST /v1/channels/activate
  → pairing stored → pipeline may start
```

OSS / classic relay: **no** invite button; no Gatebridge API; AlwaysEntitled unchanged.

---

## 4. API contract — `POST /v1/play/session`

Accept **exactly one** of two body shapes. Reject both or neither (`400`).

### 4.1 Play purchase (unchanged)

```http
POST /v1/play/session
Content-Type: application/json

{
  "productId": "gatebridge_individual_monthly",
  "purchaseToken": "<Play Purchase>",
  "packageName": "com.fidobridge.client"
}
```

### 4.2 Invite code (new)

```http
POST /v1/play/session
Content-Type: application/json

{ "inviteCode": "12345678" }
```

- `inviteCode`: must match `^[0-9]{8}$`.
- `packageName` not required on invite path.
- Do not send `purchaseToken` together with `inviteCode`.

### 4.3 Responses (shared)

**Success:**

```json
{
  "sessionToken": "…",
  "expiresInSec": 900,
  "entitled": true,
  "isTrial": false,
  "entitlementKind": "invite_code"
}
```

Purchase path may set `"entitlementKind": "play"` or omit the field.

**Failure:**

```json
{ "entitled": false, "reason": "invalid_invite_code" }
```

(or `invalid_purchase`, `no_active_subscription`, etc.)

### 4.4 `POST /v1/channels/activate` (unchanged)

```http
POST /v1/channels/activate
Authorization: Bearer <sessionToken>
{ "channel": "<32 lc hex>" }
```

Any valid activate-scoped session (purchase **or** invite) may activate a channel per existing `playstore/plans/managed-relay.md` §4.2 rules (400 bad channel, 409 rebind policy, etc.).

---

## 5. Backend behavior (invite path)

1. Load allowlist from `GATEBRIDGE_INVITE_CODES` (comma-separated env or path to file with one code per line).  
2. Validate format (8 digits) before lookup.  
3. **Rate-limit** `/v1/play/session` by client IP (e.g. ≥10 failed attempts / 15 min → `429`). 8-digit space is small.  
4. **Constant-time** compare against allowlist.  
5. On match: issue the **same** short-lived activate JWT as the purchase path (`sub=invite:<stable-id>` or hash, `scope=activate`, TTL ≈ 900s).  
6. On miss: `{ "entitled": false, "reason": "invalid_invite_code" }` (or HTTP 403 with same body — pick one style and document it; client treats non-entitled / non-2xx as failure).  
7. **Never log raw invite codes** or session tokens. Log at most `entitlementKind=invite_code` + outcome.  
8. **Rotation:** ops regenerate the list per cohort (review cycle, partner batch). Optional per-code expiry / one-time use is **out of scope for v1**.  
9. Optional later: store SHA-256 of codes; tag grants (`review` / `partner` / `waitlist`). Not required now.

**Launch note:** invite codes are ops secrets. Generate randomly, e.g.:

```bash
python3 -c "import secrets; print(f'{secrets.randbelow(10**8):08d}')"
```

Rotate when a review or partner cycle ends.

---

## 6. Client — domain model

### 6.1 `Entitlement` (main source set)

```kotlin
data class Entitlement(
    val status: EntitlementStatus,
    val productId: String? = null,
    val isTrial: Boolean = false,
    val expiryEpochMs: Long? = null,
    val purchaseToken: String? = null,
    val inviteCode: String? = null  // in-memory only; never logged or persisted
) {
    val isEntitled: Boolean get() = status == EntitlementStatus.ENTITLED

    companion object {
        val Loading = Entitlement(EntitlementStatus.LOADING)
        val NotEntitled = Entitlement(EntitlementStatus.NOT_ENTITLED)
        val BillingUnavailable = Entitlement(EntitlementStatus.BILLING_UNAVAILABLE)
        val Error = Entitlement(EntitlementStatus.ERROR)
        val Entitled = Entitlement(EntitlementStatus.ENTITLED)
    }
}
```

Invite success example:

```kotlin
Entitlement(
    status = EntitlementStatus.ENTITLED,
    productId = "invite_code",
    isTrial = false,
    purchaseToken = null,
    inviteCode = "12345678"
)
```

### 6.2 Validator (main, JVM-testable)

```kotlin
object InviteCodeValidator {
    private val PATTERN = Regex("^[0-9]{8}$")
    fun isValid(code: String): Boolean = PATTERN.matches(code)
}
```

Accepts `00000000`–`99999999`. Rejects length ≠ 8, non-digits, empty, whitespace-padded unless you trim first (prefer **no trim**; user must enter exactly 8 digits).

### 6.3 `SubscriptionRepository`

Add a method to apply invite entitlement after a successful session call (play impl updates the flow; OSS no-ops or is unused because UI is hidden):

```kotlin
// Prefer explicit API over overloading purchase path
fun markEntitledForInvite(inviteCode: String)
```

- Play `PlayBillingSubscriptionRepository`: set `_entitlement` as above.  
- `AlwaysEntitledSubscriptionRepository`: can no-op or ignore (button not shown).  
- **Do not** write `inviteCode` to EncryptedSharedPreferences or any disk store.

### 6.4 `EntitlementBackend` / invite client

Keep activate on the existing interface:

```kotlin
interface EntitlementBackend {
    suspend fun activate(channel: String): Result<ActivateResult>
}
```

Add a play-side client for submit (or fold into backend impl — pick one style and stay consistent):

```kotlin
interface InviteCodeClient {
    suspend fun submitInviteCode(code: String): Result<Unit>
}
```

**`PlayInviteCodeClient`** (play source set):

1. `InviteCodeValidator.isValid(code)` else `Result.failure`.  
2. `POST {GATEBRIDGE_API_URL}/v1/play/session` body `{ "inviteCode": code }`.  
3. Parse `entitled == true` and non-empty `sessionToken`.  
4. `subscriptionRepository.markEntitledForInvite(code)`.  
5. `Result.success`.  
6. Failures: network, 4xx/5xx, `entitled=false` → `Result.failure` (no token in logs).

**OSS:** do not bind `InviteCodeClient`, or bind a stub that always fails (button not in UI).

### 6.5 `PlayEntitlementBackend.activate` (must change)

Current code requires `purchaseToken != null`. Branch:

```text
entitlement = subscriptionRepository.entitlement.value
if (!entitlement.isEntitled) fail

if (entitlement.purchaseToken != null && entitlement.productId != null) {
    session body = { productId, purchaseToken, packageName }
} else if (entitlement.inviteCode != null) {
    session body = { inviteCode }
} else {
    fail  // fail closed
}

sessionToken = POST /v1/play/session
POST /v1/channels/activate { channel } with Bearer sessionToken
```

Never log `inviteCode` or `sessionToken`.

---

## 7. Client — UI

### 7.1 Strings (`strings.xml`)

```xml
<string name="invite_code_button">Have an invite code?</string>
<string name="invite_code_title">Enter invite code</string>
<string name="invite_code_helper">For invited, demo, or partner access. Not a public free tier.</string>
<string name="invite_code_hint">8 digits</string>
<string name="invite_code_invalid">Enter exactly 8 digits.</string>
<string name="invite_code_failed">That invite code was not accepted.</string>
<string name="invite_code_activate">Activate</string>
```

No emoji.

### 7.2 `SubscribeScreen`

- After Restore / Manage buttons, add a **small** `TextButton`: `invite_code_button`.  
- Visible **only** when billing is in play mode (play flavor / products present / `PLAY_BILLING_REQUIRED` — hide if `queryProducts` is empty **and** flavor is oss; simplest: only compile/show on play via `BuildConfig.PLAY_BILLING_REQUIRED`).  
- `AlertDialog`:
  - Title / helper as above  
  - `OutlinedTextField` value + `onValueChange` (filter to digits, max 8)  
  - `KeyboardOptions(keyboardType = KeyboardType.NumberPassword)`  
  - Confirm: `invite_code_activate`; dismiss: Cancel  
  - Error text from ViewModel state  
- On dialog confirm → `viewModel.submitInviteCode(code)`  
- While submitting: disable confirm; show progress if needed  
- On success: existing `LaunchedEffect(entitlement.isEntitled)` → `onEntitled()`  

### 7.3 `SubscribeViewModel`

```kotlin
// state
val inviteDialogVisible: StateFlow<Boolean>
val inviteCodeInput: StateFlow<String>
val inviteError: StateFlow<String?>
val inviteSubmitting: StateFlow<Boolean>

fun showInviteDialog()
fun hideInviteDialog()
fun onInviteCodeChange(raw: String)  // digits only, max 8
fun submitInviteCode()
```

`submitInviteCode()`:

1. Validate via `InviteCodeValidator` → else `inviteError = invite_code_invalid`  
2. `inviteSubmitting = true`  
3. `inviteCodeClient.submitInviteCode(code)`  
4. Success → hide dialog, clear input, `refreshEntitlement` / rely on repository flow update  
5. Failure → `inviteError = invite_code_failed`  
6. Clear `inviteSubmitting`

**Never** put the code in logs, analytics, or `userMessage` dialog text.

---

## 8. Flavor / DI

| Flavor | `SubscriptionRepository` | `EntitlementBackend` | `InviteCodeClient` |
|---|---|---|---|
| **play** | `PlayBillingSubscriptionRepository` | `PlayEntitlementBackend` | `PlayInviteCodeClient` |
| **oss** | `AlwaysEntitledSubscriptionRepository` | `FakeEntitlementBackend` | not bound / failing stub; **no UI button** |

`BuildConfig.PLAY_BILLING_REQUIRED` remains the Hilt/UI switch for play-only surfaces.

BuildConfig `GATEBRIDGE_API_URL` already exists for play session/activate.

---

## 9. Navigation / pipeline (unchanged gates)

| Gate | Behavior with invite |
|---|---|
| `FidoBridgeApp` | `ENTITLED` (including invite) → MainNavHost |
| `MainActivity` service start | entitled → may start service when paired |
| `BridgePipeline` | `entitlement.isEntitled` required before connect |
| `ManagedPairingGate` | managed host: entitled + `activate(channel)` via invite session |
| Classic relay | no activate; invite path irrelevant |

Invite entitlement is **client + backend session**, not a Play `Purchase`. Client gate opens UI; **relay** still requires successful activate + subscribe proxy.

---

## 10. Security constraints

1. 8-digit codes are **weak** → backend rate limit + rotation are mandatory in production.  
2. Never log `inviteCode`, `sessionToken`, or full pairing secrets.  
3. Fail closed if session or activate fails.  
4. Store listing / marketing: **do not** advertise invite codes as free access.  
5. Same **play** APK as production billing path (no billing-stripped review app).  
6. OSS flavor never exposes the button.  
7. Codes are not a substitute for Play entitlement on production users; they are grants you issue.  
8. Do not persist invite codes on disk.

---

## 11. Testing (minimum)

### JVM (Android)

| Test | Asserts |
|---|---|
| `InviteCodeValidatorTest` | accept 8 digits; reject 7/9/letters/empty |
| `SubscribeViewModelTest` | show dialog; digit filter max 8; invalid → error; submit success → entitled; submit fail → error; no code in logged state |
| `PlayEntitlementBackend` body mapping | purchaseToken path posts purchase JSON; inviteCode path posts `{inviteCode}`; neither → failure |
| Entitlement / UI | invite success → same nav gate as purchase (`isEntitled`) |
| OSS | no `InviteCodeClient` required; button not shown when `PLAY_BILLING_REQUIRED == false` |

### Backend (when implemented)

| Test | Asserts |
|---|---|
| Valid invite code | 200, `entitled=true`, `sessionToken`, `entitlementKind=invite_code` |
| Invalid code | denied, no token |
| Bad length / non-numeric | 400 |
| Both `purchaseToken` and `inviteCode` | 400 |
| Rate limit | repeated failures → 429 |
| Activate with invite session | channel allow-list / activate 200 |
| Logs | no raw code |

### Device (manual)

1. `playDebug`/`playRelease` APK with `GATEBRIDGE_API_URL` → real API.  
2. Insert a test code in `GATEBRIDGE_INVITE_CODES`.  
3. Subscribe → Have an invite code? → enter code → Pairing.  
4. Scan demo/managed QR → biometric → Accepted.  
5. OSS APK: no invite button; pairing unchanged.

---

## 12. Docs updates (same PR or immediately after)

| File | Update |
|---|---|
| `playstore/plans/backend.md` §4.1 | Dual body: purchase **or** `inviteCode`; response `entitlementKind` |
| `playstore/plans/managed-relay.md` §4.1 | Same contract note |
| `playstore/console/review-kit.md` | Reviewer path: enter invite code from App Access / email, then demo QR |
| `playstore/console/compliance.md` App Access | Invite code provided by Gatebridge — not a public free tier |
| `playstore/console/PLAY_CONSOLE_CONFIG.md` | Optional note under Console UI |
| `playstore/README.md` | Link `playstore/plans/invite-code.md` |

**App Access copy (suggested):**

> Gatebridge is a remote WebAuthn authenticator. For review, Gatebridge can provide an **invite code** (8 digits) that unlocks the same managed-relay entitlement path as a subscription. Pair with the demo QR at https://gatebridge.app/review. Contact hello@gatebridge.app. Invite codes are not a public free tier.

---

## 13. Implementation order

| Step | Work |
|---|---|
| 1 | Backend or mock: `POST /v1/play/session` accepts `inviteCode`; `GATEBRIDGE_INVITE_CODES`; rate limit |
| 2 | Main: `Entitlement.inviteCode`, `InviteCodeValidator`, repository `markEntitledForInvite` |
| 3 | Play: `PlayInviteCodeClient`, `PlayEntitlementBackend` activate branch, Hilt bind |
| 4 | UI: strings, SubscribeScreen button + dialog, SubscribeViewModel |
| 5 | JVM tests (§11) |
| 6 | Docs (§12) |
| 7 | Device pass with real API + demo QR |

**Definition of done (client):**  
On `play` build, valid invite code → ENTITLED → Pairing → managed activate with `{inviteCode}` → pipeline may start. Invalid code fails closed with UI error. OSS unchanged. No code in logs or prefs.

---

## 14. Out of scope

- Play product / trial changes  
- Permanent free managed tier or store-listing promo of codes  
- OSS / classic JWT pairing changes  
- One-time-use or hashed code storage (v1 optional)  
- Play Console UI store listing fields  

---

## 15. Open points (defaults)

| Point | Default |
|---|---|
| JSON key | `inviteCode` |
| Charset | 8 decimal digits |
| Persist code | No (memory only) |
| Button label | Have an invite code? |
| Hidden on OSS | Yes |
| `entitlementKind` | `invite_code` |
| Config | `GATEBRIDGE_INVITE_CODES` |
| Rate limit | ~10 fails / 15 min / IP |

---

## Agent checklist

- [ ] Session accepts `inviteCode` only (not mixed with purchaseToken)  
- [ ] Client validator + UI digit filter max 8  
- [ ] Play activate branches on `inviteCode` vs `purchaseToken`  
- [ ] Entitlement invite path sets ENTITLED without Play Purchase  
- [ ] OSS: no button, no invite client required  
- [ ] No invite codes in logs or EncryptedSharedPreferences  
- [ ] Backend rate limit + code rotation documented  
- [ ] Docs contract updated  
- [ ] JVM tests green; classic/OSS regressions green  

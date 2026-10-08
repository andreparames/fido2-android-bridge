# MANAGED_RELAY_PLAN — Special case: `relay.gatebridge.app`

**Audience:** coding agents (read this; inspect code yourselves; change what this plan requires).  
**Rule:** Implement from this contract + existing code. Do not invent extra product behavior.  
**Related:** `playstore/plans/billing.md` (Play Billing client), `PROTOCOL.md` (classic pairing URI), `playstore/plan.md` (frozen product decisions).

---

## 0. Summary

Two connection modes.

| Mode | When | Auth / gate |
|---|---|---|
| **Classic** (default) | Relay host ≠ `relay.gatebridge.app` | Centrifugo JWT from pairing URI (`token`); no Gatebridge API |
| **Managed** (special case) | Relay host = `relay.gatebridge.app` | Entitled Play app **activates** channel `C` on Gatebridge API; Centrifugo **subscribe proxy** asks that API on every subscribe; daemon only **polls subscribe** until allowed |

Pairing QR stays PROTOCOL (`channel` + `pubkey`, `token` optional). Pairing material is not published on Centrifugo during setup. Noise TOFU is unchanged (handshake learns phone key). Backend **storage/schema is the implementer’s choice** — only the API behavior below is binding.

---

## 1. Frozen decisions

| Decision | Value |
|---|---|
| Managed host | `relay.gatebridge.app` (hostname only, case-insensitive, full URL parse) |
| Managed WS URL | `wss://relay.gatebridge.app/connection/websocket` |
| Classic | Any other host → existing JWT path; **zero** Gatebridge HTTP |
| Package | `com.fidobridge.client` |
| Play products | `gatebridge_individual_monthly`, `gatebridge_individual_yearly` |
| Trial | `P1M` both; no permanent free managed tier |
| Managed gate | Backend allow decision + Centrifugo subscribe proxy — not client-only paywall |
| Managed QR | `fidobridge://pair?channel=…&pubkey=…[&v=3]`; omit Centrifugo JWT |
| Classic QR | Unchanged PROTOCOL (token when present) |
| `channel_admin` | Backend session scope for activate after Play verify — not an APK/Centrifugo admin secret |
| TOFU | Existing Noise behavior only; **no** backend-delivered phone key; **no** channel status API |
| Backend storage | **Implementer’s choice** from the API contract (§4) |
| Diagnostics | Play/release local + export only (do not regress) |
| PROTOCOL v1 | No new URI params; detect via configured relay URL only |

---

## 2. Mode detection

```
managed IFF hostname(relayUrl) == "relay.gatebridge.app"
```

- Parse scheme/host/port properly; ignore scheme/path/port when host matches.  
- No substring matches (`evil-relay.gatebridge.app` → classic).  
- Invalid URL → classic.

| Peer | Relay URL source |
|---|---|
| Daemon | `FIDO2_RELAY_URL` / daemon config |
| App | `BuildConfig.RELAY_URL` (play default = managed host) |

| Mode | Activate API | Subscribe proxy | URI `token` | Connection |
|---|---|---|---|---|
| Managed | App calls activate | Required | Optional/absent | Connect OK; **subscribe** gated by proxy |
| Classic | Never | No | As today | JWT from URI / user Centrifugo |

Classic must not depend on `api.gatebridge.app`.

---

## 3. Managed happy path

```
DAEMON (managed)
  1. Generate C + X25519 keypair P (existing pairing generation)
  2. QR: fidobridge://pair?channel=C&pubkey=P&v=3
  3. Connect to Centrifugo (anonymous or low-priv connect only)
  4. Loop: subscribe("fidobridge:" + C) + backoff until allowed
  5. Subscribe OK → Noise handshake → existing TOFU pin

APP (managed)
  6. Parse pairing URI → C, P
  7. Play entitlement (Billing; trial or active)
  8. POST /v1/channels/activate { channel: C }  // session after Play verify
  9. Store pairing material (IdentityStore / PairingRepository)
 10. Connect + subscribe fidobridge:C → proxy allows
 11. Noise with daemon

CENTRIFUGO (managed only)
  - Namespace fidobridge:* : subscribe_proxy_enabled
  - POST http://127.0.0.1:PORT/centrifugo/subscribe on every SUBSCRIBE (loopback)
  - Allow iff API says channel active; else 403
```

### Classic (unchanged)

```
Daemon: pair → QR with channel, pubkey, token
App: parse → store token → connect with JWT → subscribe
No activate; no Gatebridge webhook path required
```

---

## 4. Backend API contract (binding)

Implementers choose DB/schema/process layout to satisfy this behavior. Do not ship a fixed schema as product law; satisfy the contract.

### 4.1 `POST /v1/play/session`

Turn Play purchase proof into a short-lived session with activate scope.

```http
POST /v1/play/session
Content-Type: application/json

{ "productId": "gatebridge_individual_monthly",
  "purchaseToken": "<Play Purchase>",
  "packageName": "com.fidobridge.client" }
```

```json
{ "sessionToken": "…", "expiresInSec": 900, "entitled": true, "isTrial": true }
```
```json
{ "entitled": false, "reason": "no_active_subscription" }
```

**Must:** verify with Google Play Developer API; persist whatever state you need for revoke/RTDN; never trust client-only entitlement.

### 4.2 `POST /v1/channels/activate`

```http
POST /v1/channels/activate
Authorization: Bearer <sessionToken>
Content-Type: application/json

{ "channel": "<32 lc hex>" }
```

```json
{ "channel": "…", "status": "active" }
```

Errors: `401` bad session; `403` not entitled; `400` bad channel; `409` channel bound to another subscription (default: deny rebind).

**Must:** channel = `[0-9a-f]{32}`; activate implies **subscribe allowed** for that channel on managed Centrifugo until deactivated; tie to entitlement for revoke.

### 4.3 `POST /centrifugo/subscribe` (webhook)

Centrifugo subscribe proxy target (managed instance).

**Request:**
```json
{ "client": "…", "transport": "websocket", "protocol": "json",
  "encoding": "json", "user": "…", "channel": "fidobridge:a1b2…" }
```

**Allow:** `{ "result": {} }`  
**Deny:** `{ "error": { "code": 403, "message": "permission denied" } }`

**Must:** strip the `fidobridge:` namespace prefix (accept `fidobridge.` defensively); allow iff channel currently entitled/active; fail closed on errors; p99 &lt; ~500ms.

### 4.4 Not in the API

| Endpoint | Status |
|---|---|
| Channel status / phone pubkey | **Do not add** for this plan |
| Daemon authenticate / enroll | **Not required** |
| Master Centrifugo key from clients | **Forbidden** |

### 4.5 RTDN (production)

Update entitlement state on renew/cancel/expire/refund so activate/webhook stop allowing. Stub/mock until Play Console + service account exist.

---

## 5. Centrifugo (managed instance)

| Setting | Value |
|---|---|
| Connection JWT | Server-side HMAC only; never in APK/public daemon |
| Anonymous or low-priv connect | Allowed; connect ≠ subscribe on arbitrary `fidobridge:*` |
| Namespace | `fidobridge` → channels `fidobridge:<32hex>` (separator `:`) |
| Subscribe proxy | Enabled; endpoint `http://127.0.0.1:PORT/centrifugo/subscribe` (loopback) |
| Sub JWT for `fidobridge:*` | Do not issue (proxy skipped if present) |
| User-limited channels | Do not use for this namespace |
| HTTP API key | Ops/backend only |

Self-host Centrifugo: unchanged; no proxy required.

**Mechanism reference:** Centrifugo subscribe proxy POSTs `{ client, user, channel, … }` to your endpoint; reply `{ "result": {} }` or `{ "error": { "code": 403, … } }`. Your backend **is** the allowlist; Centrifugo does not keep a dynamic channel ACL of its own.

---

## 6. Android agent tasks

Inspect code yourself.

1. **`RelayModeDetector`** — hostname rule (§2); unit-test matrix including evil suffixes.  
2. **Managed post-scan:** Play entitlement → `EntitlementBackend.activate(channel)` → existing IdentityStore → pipeline only if entitled **and** activate ok.  
3. **Classic:** token from URI; **never** call activate/backend.  
4. **Interfaces:**

```kotlin
interface EntitlementBackend {
  suspend fun activate(channel: String): Result<ActivateResult>
}
```

Fake for JVM tests; real HTTP impl only when managed/play.  
5. **Do not regress:** classic pairing, Noise, CTAP2, biometrics, local diagnostics/export.  
6. Subscribe UI / Billing can follow `playstore/plans/billing.md` (fakes OK until Console).

---

## 7. Daemon agent tasks

Inspect code yourself.

1. **Mode detect** from configured relay URL (same rule as app).  
2. **Managed:** generate C/P; QR without token; connect low-priv; poll `fidobridge:C` until allowed; Noise + existing TOFU.  
3. **No** activate, **no** Play APIs, **no** channel status API.  
4. **Classic:** current JWT/token behavior; no Gatebridge HTTP.  
5. Config: existing `FIDO2_RELAY_URL` / config key only (no new required key for v1).

---

## 8. PROTOCOL.md

- v1: **no** new URI parameters; detection = configured URL only.  
- Managed channel form is **`fidobridge:<32hex>`** (colon separator), pinned in `playstore/plans/backend.md` §5.3; matches `PROTOCOL.md` §3.3.  
- If `relay=` is needed later: update ABNF + allowed params + versioning rules deliberately (old peers reject unknown params).  
- Mirror wire changes into `PROTOCOL.md` + fixtures per repo rules.

---

## 9. Security (non-negotiable)

1. Centrifugo HMAC / HTTP API keys never in client or public daemon builds.  
2. Pairing material not published on Centrifugo during managed setup (QR OOB; activate on API).  
3. Webhook deny-by-default; fail closed.  
4. Classic path offline from Gatebridge API.  
5. Activate requires server-side Play verification.  
6. TOFU = existing Noise handshake only.  
7. No purchaseToken/sessionToken/pairing secrets in logs.  
8. Entitlement end → channel no longer allowed to subscribe.

---

## 10. Testing (minimum)

**Backend:** activate 401/403/400/409; webhook allow/deny/prefix; mocked Play verify.  
**Daemon:** mode matrix; managed QR without token; poll deny→allow; classic regression; **no** Gatebridge HTTP on classic.  
**Android:** mode matrix; managed activate once; pipeline blocked if not entitled; classic never calls activate.  
**E2E (when API+Centrifugo up):** `relay.gatebridge.app` — unpaid cannot join; entitled pair + one webauthn.io assertion via `--uhid`; localhost classic Centrifugo still works.

---

## 11. Implementation order

| Step | Deliverable |
|---|---|
| 1 | Mode detector + tests (both peers) |
| 2 | Backend API + entitlement persistence (your schema) + webhook + fake Play verify |
| 3 | Centrifugo managed config (subscribe proxy) |
| 4 | Daemon managed poll path |
| 5 | Android activate + fake EntitlementBackend |
| 6 | Play Billing UI (JVM tests; real Play later) |
| 7 | E2E on managed relay |
| 8 | Real Play verify + RTDN + license testers |

**DoD (managed):** on `relay.gatebridge.app`, unpaid app cannot subscribe to `fidobridge:C`; entitled app + daemon complete Noise + one assertion; classic self-host unchanged and never calls Gatebridge API.

---

## 12. Non-goals

- applicationId/brand renames beyond existing plans  
- Team/Business products  
- SSH `sk-*`  
- Pairing over Centrifugo plaintext  
- Daemon calling Play/activate  
- “Daemon vs app” identity in Centrifugo (only subscribe proxy + API allow decision)  
- **Fixed SQL/data model** — API contract only  

---

## 13. Defaults for open points

| Point | v1 default |
|---|---|
| Connect auth managed | Anonymous or low-priv JWT; subscribe still proxied |
| `relay=` in QR | Defer |
| Channel rebind | `409` unless same entitlement |
| Daemon poll timeout | Backoff until stopped; flag later |
| Session TTL | ~15 min activate scope |
| Storage | Implementer’s choice from §4 |

---

## Agent checklist

- [ ] Managed iff host is `relay.gatebridge.app`  
- [ ] Classic: no Gatebridge HTTP  
- [ ] Managed QR: PROTOCOL pair URI, token optional  
- [ ] App: activate after Play entitlement; fake backend in tests  
- [ ] Daemon: subscribe poll only; Noise TOFU as today; no status/enroll API  
- [ ] Centrifugo managed: subscribe proxy → `POST /centrifugo/subscribe`  
- [ ] Backend satisfies §4; schema is yours  
- [ ] Tests: §10; classic regressions green  
- [ ] No secrets in APK/public daemon builds  

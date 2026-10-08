# BACKEND_PLAN — Gatebridge managed-relay backend (draft)

**Status:** DRAFT — design review. Not frozen. §12 decisions resolved; storage/schema remains illustrative (§4). Only the API behavior in `playstore/plans/managed-relay.md` §4 is binding.
**Audience:** coding agent implementing the service.
**Grounding (read first):**
- `playstore/plans/managed-relay.md` — binding API contract (§4), managed happy path (§3), security (§9).
- `playstore/plans/billing.md` §7 — server-side entitlement is a launch blocker; client gate is necessary but not sufficient.
- `PROTOCOL.md` §2 — channel (`[0-9a-f]{32}`), pairing URI. Relay never sees plaintext.
- `AGENTS.md` — security-first, no speculative crypto, fail closed.

---

## 0. Purpose and scope

One HTTP service ("Gatebridge API", public host `api.gatebridge.app`) that turns a Google Play subscription into permission to subscribe to a managed Centrifugo channel.

**Responsibilities**

1. `POST /v1/play/session` — verify a Play purchase/trial with the Google Play Developer API; issue a short-lived session token with `activate` scope.
2. `POST /v1/channels/activate` — bind a channel `C` to the calling entitlement; make `C` subscribe-able.
3. `POST /centrifugo/subscribe` — Centrifugo subscribe-proxy webhook: allow iff `C` is currently entitled/active; deny by default.
4. RTDN handling — update entitlement state on renew/cancel/expire/refund so (2) and (3) stop allowing.

**Explicit non-goals** (from `playstore/plans/managed-relay.md` §4.4) — do **not** add:

- Channel-status or phone-pubkey API.
- Daemon authenticate/enroll API.
- Any endpoint that hands a client the Centrifugo master/HMAC key.
- A fixed SQL/data model as product law.

The relay only ever carries sealed AES-256-GCM envelopes (`PROTOCOL.md`); this service never sees, stores, or logs pairing/Noise secrets.

---

## 1. Deployment target

Runs on the same Hetzner host as the managed Centrifugo instance.

```
                 internet                     loopback / systemd
  Android app ──────────────► api.gatebridge.app  ──┐
  (play flavor)   HTTPS          (nginx TLS)        │
                                                     ├─ gatebridge-api.service
  Centrifugo ── subscribe proxy ────────────────────┘   (uvicorn, 127.0.0.1:PORT)
               POST http://127.0.0.1:PORT/centrifugo/subscribe
  Google Play Developer API ◄── outbound HTTPS ── gatebridge-api
  Google Pub/Sub push ──────► https://api.gatebridge.app/rtdn (public, nginx)
```

- **Process:** `systemd` service (`gatebridge-api.service`), non-root user, restart on failure, `EnvironmentFile`/`LoadCredential` for secrets.
- **TLS/DNS:** `api.gatebridge.app` → Hetzner IP; cert via the existing certbot + nginx pattern (`relay/README.md:157-207`). Only `/rtdn` and the `/v1/*` API need to be publicly exposed; the Centrifugo proxy callback stays on loopback.
- **DB:** local PostgreSQL (or managed). The entitlement/channel tables are the only stateful thing. **No backups are taken (§12.6, resolved):** this state is deliberately disposable — losing it simply forces clients to re-activate/re-pair, which is accepted. Do not build a backup/restore pipeline.
- **Why same host:** the subscribe webhook is on the hot path of every subscribe; loopback keeps p99 well under the 500 ms target, and Centrifugo never needs to reach the public API to authorize.

---

## 2. Technology choices

| Layer | Choice | Why |
|---|---|---|
| Runtime | Python 3.12 | Matches the daemon toolchain (CI, ruff, pytest); server-side peers share one language |
| HTTP | FastAPI + uvicorn | Async, typed, trivial to test; pydantic gives request validation the contract needs |
| Validation/models | Pydantic v2 | Enforces `[0-9a-f]{32}` channel, required fields, canonical errors |
| DB | PostgreSQL | Rebind/revoke + RTDN need real constraints and transactions; no migration pain later |
| ORM/migrations | SQLAlchemy 2.0 + Alembic | Schema is ours to evolve; Alembic versions the migrations |
| Play verify | `google-api-python-client` (`androidpublisher` v3) + `google-auth` | Official client; never hand-roll purchase verification |
| RTDN | Google Cloud Pub/Sub push | Play publishes RTDN to a Pub/Sub topic; push is the least-moving-parts receiver |
| Sessions | Signed JWT (short TTL, `scope=activate`) | Stateless verify on the hot path; can add a revocation/id table if needed |
| Tests | pytest + `httpx.MockTransport`/`respx` | Fake Play verify; deterministic webhook tests |

Alternative considered: Go (`androidpublisher/v3`, single static binary). Justified only if the subscribe proxy becomes a high-QPS hot path; at current scale it adds a toolchain for no measurable gain.

---

## 3. Data model (illustrative — implementer's choice)

> `playstore/plans/managed-relay.md` §4 defines behavior, not a schema. Suggested minimum:

**`subscriptions`** — one row per Play entitlement.

| Column | Notes |
|---|---|
| `id` | PK |
| `purchase_token` | Play purchase token; **never logged**, unique |
| `product_id` | allowlist: `gatebridge_individual_monthly`, `gatebridge_individual_yearly` |
| `package_name` | must equal `com.fidobridge.client` |
| `status` | `active` / `in_trial` / `cancelled` / `expired` / `refunded` |
| `is_trial` | bool |
| `expires_at` | from Play; drives revoke |
| `created_at`, `updated_at` | |

**`channels`** — channel bound to a subscription.

| Column | Notes |
|---|---|
| `channel` | PK, `[0-9a-f]{32}` |
| `subscription_id` | FK; unique per channel → rebind denies with 409 |
| `state` | `active` / `revoked` |
| `activated_at`, `revoked_at` | |

**`sessions`** — **not used** (§12.2, resolved): session tokens are signed JWTs, so there is no server-side session table in v1. Shown only as the fallback if a revocation list is ever needed.

| Column | Notes |
|---|---|
| `token_hash` | never store raw token |
| `subscription_id` | FK |
| `scope` | `activate` |
| `expires_at` | now + 900 s |

**`rtdn_events`** — idempotency for Pub/Sub redelivery.

| Column | Notes |
|---|---|
| `message_id` | unique; dedupe key |
| `received_at` | |
| `processed` | bool |

Indexes: `channels(channel)` (hot path), `subscriptions(purchase_token)`, `rtdn_events(message_id)`.

Invariant (§4.2): a channel is subscribe-able iff it has a row in `channels` with `state=active` whose subscription is currently entitled. Revoke flips state; no delete needed.

---

## 4. API contract

These mirror `playstore/plans/managed-relay.md` §4 exactly. Request/response bodies are binding.

### 4.1 `POST /v1/play/session`

Accepts **exactly one** of two body shapes (Play purchase **or** invite code; both/neither → `400`). Full invite contract: [`invite-code.md`](invite-code.md) §4.

Play purchase:

```http
POST /v1/play/session
Content-Type: application/json

{ "productId": "gatebridge_individual_monthly",
  "purchaseToken": "<Play Purchase>",
  "packageName": "com.fidobridge.client" }
```

Invite code:

```http
POST /v1/play/session
Content-Type: application/json

{ "inviteCode": "12345678" }
```

```json
{ "sessionToken": "…", "expiresInSec": 900, "entitled": true, "isTrial": true, "entitlementKind": "invite_code" }
```

```json
{ "entitled": false, "reason": "no_active_subscription" }
```

Must: verify against the Play Developer API (server-side) for the purchase path; for the invite path match `GATEBRIDGE_INVITE_CODES` with rate limiting and never log raw codes. Persist state for revoke/RTDN, never trust client-only entitlement. Reject a `packageName`/`productId` outside the allowlist.

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

Errors: `401` bad/expired session; `403` not entitled; `400` bad channel; `409` channel bound to another subscription. **Rebind policy (§12.3, resolved): v1 has no transfer flow — a channel bound to a different subscription is always `409`; re-activating the same subscription on its own channel is idempotent-allow.**

Must: validate `channel = [0-9a-f]{32}`; activation implies subscribe allowed until deactivated; tie to entitlement for revoke.

### 4.3 `POST /centrifugo/subscribe` (webhook — see §5)

Allow: `{ "result": {} }` — Deny: `{ "error": { "code": 403, "message": "permission denied" } }`.

### 4.4 Session token

- Format: signed JWT (HS256 or EdDSA) with `sub=subscription_id`, `scope=activate`, `exp`, `iat`, `jti`.
- TTL ≈ 900 s (`playstore/plans/managed-relay.md` §13).
- Verify signature + `exp` + `scope` on every `activate` call. Bad/absent/expired → `401`.
- Never log the raw token or the `purchaseToken`.

---

## 5. Centrifugo interaction

### 5.1 Configuration (Centrifugo v6, verified against centrifugal.dev/docs/server/proxy)

Subscribe proxy is enabled **per namespace**; the endpoint lives under `channel.proxy.subscribe`:

```json
{
  "channel": {
    "proxy": {
      "subscribe": {
        "endpoint": "http://127.0.0.1:8080/centrifugo/subscribe",
        "timeout": "1s"
      }
    },
    "without_namespace": {
      "allow_subscribe_for_client": false,
      "allow_publish_for_client": false,
      "allow_publish_for_subscriber": false
    },
    "namespaces": [
      {
        "name": "fidobridge",
        "subscribe_proxy_enabled": true,
        "allow_subscribe_for_client": true,
        "allow_publish_for_client": true,
        "allow_publish_for_subscriber": true
      }
    ]
  }
}
```

- `timeout` default is `1s`; the backend must comfortably answer within it (loopback + indexed lookup + short TTL cache).
- The `endpoint` is intentionally loopback. Public exposure of `/centrifugo/subscribe` is not required while Centrifugo and the API share the host.

### 5.2 Subscribe-proxy request/response

Centrifugo POSTs JSON on every SUBSCRIBE:

```json
{ "client": "…", "transport": "websocket", "protocol": "json",
  "encoding": "json", "user": "…", "channel": "fidobridge:a1b2…" }
```

Full field set (`SubscribeRequest`): `client`, `transport`, `protocol`, `encoding`, `user`, `channel` (all required), plus optional `token`, `meta`, `data`, `b64data`, `labels`.

Backend logic:

1. Read `channel`, strip the namespace prefix (§5.3).
2. Validate `[0-9a-f]{32}`; invalid → deny.
3. Look up the channel → subscription; allow iff `channels.state=active` **and** subscription currently entitled (`active`/`in_trial`, not expired/refunded). Optionally serve from a short-TTL in-process cache invalidated by activate/revoke/RTDN.
4. Allow → `{"result": {}}`; any other case or any internal error → 403 (fail closed).

Centrifugo expects exactly `{"result": {}}` for allow and `{"error": {"code": 403, "message": "permission denied"}}` for deny. Error codes should stay in `[400,1999]`.

### 5.3 Channel naming — resolved: `fidobridge:<C>`

**Decision (§12.1, resolved):** the managed namespace separator is **colon** and the channel form is **`fidobridge:<channel_id>`**. This matches Centrifugo's default namespace separator, the already-deployed `relay/config.json`, `PROTOCOL.md` §3.3, and the CHANGELOG. `CHANNEL_PREFIX=fidobridge:`.

The earlier `fidobridge.<C>` / "strip optional `fidobridge.` prefix" wording in `playstore/plans/managed-relay.md` is **superseded** and has been updated to the colon form.

Implementation: the webhook strips the `fidobridge:` prefix **and** accepts a `fidobridge.` prefix defensively (cheap insurance against a future separator change), then validates `[0-9a-f]{32}`. The daemon/app channel strings MUST emit the colon form. Canonical references: `PROTOCOL.md` §3.3 and `relay/README.md`.

### 5.4 Connection auth and why the proxy is authoritative

- Connection JWT/HMAC is server-side only; never shipped in the APK or public daemon (`playstore/plans/managed-relay.md` §5, §9.1).
- Anonymous or low-priv connect is allowed. Connect ≠ subscribe; the proxy is the gate.
- Do **not** issue subscription JWTs for `fidobridge:*` and do **not** make these user-limited channels. Per Centrifugo's channel-permission model, **the subscribe proxy is skipped when a subscription token is present or the channel is user-limited** — either would bypass the backend allowlist. This is a hard requirement, not a preference.
- The backend is the allowlist; Centrifugo keeps no dynamic channel ACL of its own.

### 5.5 Managed happy-path recap

```
Daemon: generate C + X25519 P; QR fidobridge://pair?channel=C&pubkey=P&v=3 (no token)
        connect low-priv; loop subscribe("fidobridge:C") + backoff until allowed
App:    Play entitlement → POST /v1/channels/activate {channel:C}
        connect + subscribe → proxy asks this service → allowed
        Noise handshake → TOFU pin (unchanged)
```

Classic (any host ≠ `relay.gatebridge.app`) never touches this service (`playstore/plans/managed-relay.md` §2, §9.4).

---

## 6. Play verification flow

1. Receive `{productId, purchaseToken, packageName}`.
2. Reject if `packageName != com.fidobridge.client` or `productId` not in the allowlist.
3. Call the Play Developer API `purchases.subscriptionsv2.get` with a **service-account** credential (from systemd credentials), `packageName` + `purchaseToken`.
4. Accept iff the subscription state is active or in a valid trial; capture `expiryTime`, trial flag, and the linked purchase state.
5. Acknowledge the purchase if required by Play policy (do not silently leave it unacknowledged).
6. Upsert the `subscriptions` row keyed by `purchase_token`; return session token + `entitled`/`isTrial`.

Never derive entitlement from client-supplied fields. A Play API failure is a hard `entitled:false` / error — no optimistic allow.

---

## 7. RTDN (production; stub until Console + service account exist)

> Transport **(§12.4, resolved): Google Cloud Pub/Sub push** to `POST /rtdn`. No pull worker in v1.

- Play publishes Real-time Developer Notifications to a Cloud Pub/Sub topic.
- Receiver: `POST /rtdn` (nginx-terminated HTTPS), verify the Pub/Sub push OIDC bearer (`google-auth`) before processing.
- Payload is a base64 `Message.data` containing a `DeveloperNotification`; decode and switch on `subscriptionNotification.notificationType` (renewed, canceled, expired, on-hold, refunded, revoked, etc.).
- Apply to the `subscriptions` row; revoking/expiring must immediately stop `activate`/subscribe from allowing (`playstore/plans/managed-relay.md` §4.5, §9.8).
- **Idempotent:** Pub/Sub delivers at-least-once. Dedupe on `messageId` in `rtdn_events` before applying.
- Until the Console/service account exist: a stub endpoint that returns 200 and logs nothing sensitive, plus `FakePlayVerifier` for tests.

---

## 8. Configuration and secrets

| Setting | Source | Notes |
|---|---|---|
| `PLAY_SERVICE_ACCOUNT_JSON` | systemd credential / file `0600` | never in git, never logged |
| `PLAY_PACKAGE_NAME` | env | `com.fidobridge.client` |
| `PLAY_PRODUCT_IDS` | env | allowlist |
| `SESSION_SIGNING_KEY` | systemd credential | JWT signing |
| `DATABASE_URL` | env / systemd credential | Postgres DSN |
| `CHANNEL_PREFIX` | env | `fidobridge:` (canonical, see §5.3) |
| `PUBSUB_AUDIENCE` | env | expected OIDC audience for `/rtdn` |
| `SUBSCRIBE_CACHE_TTL_SEC` | env | default small (e.g. 5–30) |

Secrets follow the repo's existing `pass`/`0600` conventions (`relay/README.md:118-126`).

---

## 9. Security requirements (non-negotiable)

1. Centrifugo HMAC / HTTP API keys never in client or public daemon builds; this service holds them server-side only.
2. Webhook deny-by-default; any exception, timeout, or malformed request → 403. Fail closed.
3. Activate requires server-side Play verification; client assertions are ignored.
4. No `purchaseToken`, session token, or pairing secret in logs.
5. Classic path has zero coupling to this service.
6. RTDN verification failure → reject, do not mutate state.
7. Input validation on every request (`channel` regex, allowlists); never echo user input into errors beyond safe text.
8. Entitlement end ⇒ channel no longer allows subscribe. Guaranteed by tying the webhook decision to live subscription state, not to activation time alone.
9. Rate-limit `/v1/play/session` and `/v1/channels/activate` per source; the webhook is loopback-only.

---

## 10. Observability

- Structured JSON logs; redact tokens. Log `channel`, `status`, `subscription_id` (not the raw Play token) for audit.
- Metrics: activate counts by result, webhook allow/deny/error, verify latency, RTDN processed/dup.
- Health: `/healthz` (process) and `/readyz` (DB reachable). No secrets in output.

---

## 11. Testing (minimum, per `playstore/plans/managed-relay.md` §10)

| Test | Asserts |
|---|---|
| Activate auth matrix | `401` bad session, `403` not entitled, `400` bad channel, `409` rebind |
| Webhook | allow for active channel; deny unknown/revoked/expired; prefix strip (both separators); 403 on internal error / malformed body |
| Play verify (mocked) | active/trial → entitled; expired/refunded/unknown → not entitled; wrong package/product rejected |
| Session | signature/expiry/scope enforcement |
| RTDN | state transitions on renew/cancel/expire/refund; idempotent dedupe on same `messageId`; OIDC rejection |
| Latency | webhook p99 well under 500 ms with warm cache |

Use `FakePlayVerifier` and an in-memory/transactional Postgres for deterministic CI; no real Play calls in tests.

---

## 12. Decisions (resolved) and remaining unknowns

All previously-open questions are now decided; the entries below are binding.

| # | Decision |
|---|---|
| 12.1 | **Channel form:** colon — `fidobridge:<32hex>`, `CHANNEL_PREFIX=fidobridge:`. `playstore/plans/managed-relay.md` updated to match (§5.3). |
| 12.2 | **Session token:** signed JWT (HS256 or EdDSA), `sub=subscription_id`, `scope=activate`, TTL 900 s. No server-side session table (§4.4). |
| 12.3 | **Rebind:** no transfer flow in v1. Different subscription → `409`; same subscription re-activating its own channel → idempotent-allow (§4.2). |
| 12.4 | **RTDN transport:** Google Cloud Pub/Sub **push** to `POST /rtdn`; OIDC verified. No pull worker (§7). |
| 12.5 | **Hosting:** same Hetzner host as managed Centrifugo; subscribe webhook stays loopback (§1). |
| 12.6 | **DB backups:** none. State is disposable; loss forces re-activate/re-pair, which is accepted. No `pg_dump`/PITR pipeline (§1). |
| 12.7 | **DNS/TLS:** we control `gatebridge.app` DNS; add `api.gatebridge.app` and issue the cert via the existing certbot+nginx pattern (`relay/README.md:157-207`). |

**Remaining unknowns (operational, non-blocking for code):**

1. **GCP project/org for Pub/Sub** — which project hosts the RTDN topic and the Play service account. Needed before step 6 (§13); the stub + `FakePlayVerifier` unblock earlier steps.
2. **Initial per-source rate limits** for `/v1/play/session` and `/v1/channels/activate` (§9.9) — pick concrete values at deploy.

---

## 13. Implementation order

| Step | Deliverable |
|---|---|
| 1 | Skeleton: FastAPI app, config/secret loading, Postgres + Alembic, health |
| 2 | `FakePlayVerifier` + `POST /v1/play/session` + session tokens |
| 3 | `POST /v1/channels/activate` (401/403/400/409) + entitlement persistence |
| 4 | `POST /centrifugo/subscribe` + loopback cache + deny-by-default |
| 5 | Managed Centrifugo config (subscribe proxy), no sub JWT / user-limited channels |
| 6 | RTDN stub → real verify + idempotency |
| 7 | E2E on `relay.gatebridge.app` with daemon + app; classic regression |

**DoD:** on the managed relay, an unpaid app cannot subscribe to `fidobridge:C`; an entitled app + daemon complete Noise and one assertion; classic self-host is unchanged and never calls this service.

---

## 14. Future work (deferred)

### 14.1 Daemon readiness hint (`C-meta`) — deferred; keep polling for v1

**Status:** **not in v1.** v1 keeps the daemon's simple "attempt the gated subscribe with backoff until allowed" (`playstore/plans/managed-relay.md` §3, §5.5). The hint below only shortens *pairing latency*; it does not change who is authorized. The subscribe proxy on `fidobridge:C` remains the sole authority.

**Idea:** the daemon generates `C`, shows the QR, and also subscribes to a read-only `C-meta` signaling channel. On `POST /v1/channels/activate`, the backend (via the Centrifugo Server API) publishes `{"state":"ready","channel":"<C>","epoch":…}` to `C-meta`. The daemon wakes and performs the normal gated `subscribe("fidobridge:C")`. Spoofing "ready" is harmless — the subsequent subscribe is still authoritatively checked — so the hint carries no authority.

**Required pieces (if adopted):**
- Dedicated `meta` namespace (NOT `fidobridge`, which carries the subscribe proxy): `allow_subscribe_for_anonymous: true`; every `allow_publish_*: false` so only the Server API can publish; `history_size: 1` + short TTL + `force_recovery`/cache `auto_cache_recover` so a late or reconnecting daemon replays the latest state; `channel_regex` to keep names hex.
- Backend publishes on activate: **after** the DB commit, and **best-effort** — activation must succeed even if hint delivery fails.
- Derive the meta channel from `C` (e.g. `meta:<H(C)>`), reusing the `PROTOCOL.md` §3.2 derivation; no extra input in the activate body.
- Revocation symmetry: also publish `revoked`/`expired` on RTDN. Authoritative enforcement still needs `expire_at` + `sub_refresh`, or a Server API `disconnect` on revoke (§5.5), since the proxy is not re-run on an existing subscription.
- Update `PROTOCOL.md` / `playstore/plans/managed-relay.md` and bump `PROTOCOL_VERSION` per `AGENTS.md` §1.1.
- **Never make correctness depend on the hint** — keep the subscribe poll as the fallback; a missed hint must degrade to current behavior.

**Alternative considered:** backend Server API `subscribe`/`disconnect` targeting the daemon's connection directly (no meta channel; instant readiness and prompt revoke). Requires identifying the daemon's connection (connect profile `data` → assigned user), which brushes the "no daemon enroll/auth API" non-goal (§4.4).

**Why deferred:** the benefit is latency-only (pairing UX). It adds a namespace, an activate→Centrifugo publish side effect, recovery configuration, and a protocol change. Revisit if sub-second readiness or prompt revocation is actually required.

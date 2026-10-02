# Business Plan — FIDO2 Android Bridge (Remote WebAuthn)

**Positioning:** a niche but useful product. *Remote WebAuthn only* — a way to
use an Android phone as a hardware-backed FIDO2/WebAuthn authenticator for a
remote Linux server. Nothing more.

Not a password manager. Not an SSH-key replacement. Not a general FIDO server.
Not an SSO platform. One job, done well: **phishing-resistant WebAuthn
authentication on headless/remote Linux, backed by the phone's secure
hardware.**

---

## 1. Product scope

### Core value (the one thing we do)

When a WebAuthn-capable app or browser on a remote Linux server (SSH bastion,
VPS, dev box, admin panel, control-plane dashboard) requests a security key or
passkey, the FIDO2 Android Bridge lets the user's phone perform the biometric
signature in hardware — equivalent to a physical security key, without the
hardware purchase or USB forwarding.

### What is deliberately NOT in scope

- SSH `sk-*` key replacement (requires a virtual FIDO2 device layer plus CTAP
  `hmac-secret` / `credMan` on Android — a separate, large roadmap item).
- Password managers, OTP/TOTP, SSO/IdP services, PKI, or a general-purpose
  FIDO2 *server*.
- Anything that requires the phone to hold anything except hardware-backed
  WebAuthn credentials.

Keeping the scope narrow is a feature: it makes the security story
understandable, the audit surface small, and the product differentiated.

---

## 2. Target market: SMBs

### Ideal Customer Profile (SMB edition)

SMBs (2–500 employees) whose staff authenticate to Linux servers as part of
daily work, and who either:

- can't justify per-seat hardware key purchases (YubiKey ~$25–55/seat), or
- have remote/headless infrastructure where plugging in a USB key is
  impractical, or
- want phishing-resistant MFA on their admin/control-plane surfaces without a
  heavyweight IAM stack.

### Segments

| Segment | Why it fits | Primary use case |
|---|---|---|
| **Individuals** | Single users with a VPS / remote box wanting hardware-backed WebAuthn | Their own remote server, no infra to run |
| **Software development houses / agencies** | Devs live on SSH bastions and client infra; already care about phishing-resistant MFA | WebAuthn on SSH gateways, admin panels, CI/CD dashboards |
| **MSPs & IT service providers** | Manage many clients' servers; same pain replicated | WebAuthn across client servers; possible resell channel |
| **Small SaaS / DevOps teams** | Cloud VPS, root access, remote workers | Control-plane auth, vendor dashboards |
| **VPS / hosting companies** | Customer-facing auth on server control panels | Password/OTP replacement on admin panels |
| **Security / pentest firms** | Early adopters, credibility | Dogfooding + customer confidence |
| **Web3 / crypto teams** | High-value targets, hardware-security mindset | Remote signing / admin auth |

### Primary wedge

**Software development houses and MSPs.** Same underlying pain (remote-server
auth without hardware keys), and MSPs double as a distribution channel into the
broader SMB market.

---

## 3. SMB feature set (paid tier)

Lean. These are the features SMBs will actually pay for; anything heavier is an
enterprise up-sell later.

1. **Admin console** — add/remove users, list enrolled devices, revoke a lost
   phone's device in one click.
2. **Lost-phone recovery** — re-pair a new device, revoke the old one. The #1
   daily operational pain.
3. **Audit log** — who authenticated, when, from where. Cheap for us, valuable
   for dev-shop client audits.
4. **Per-team RP allow-lists** — which relying parties (apps/servers) each
   user's credentials are valid for.
5. **Bulk onboarding** — invite teammates; QR pairing at scale.
6. **Managed relay** — we host the (untrusted) relay so teams don't run their
   own Centrifugo. This is the *entire* Individual plan: it's what the app
   connects to. Without a paid account the app has nothing to talk to.
7. **Support** — email + shared Slack channel.

The **Individual plan is thin by design**: the pre-built Play Store app + a
hosted relay + updates. It deliberately withholds the admin console, team
features, and priority support — those are what SMBs pay for.

### Explicitly deferred (enterprise tier, later)

Self-hosted relay, SSO/SAML-OIDC integration, compliance certs (SOC 2), SIEM
export, SLA-backed support, per-tenant isolation for the relay.

---

## 4. Pricing model

Two separate things that must not be confused:

- **The open-source project is free.** Source, daemon, Android app, wire
  protocol — MIT/whatever license we choose. Anyone can self-host the relay
  and sideload the app forever, at $0.
- **The product is paid.** A *pre-built* app (Play Store, auto-updates) wired
  to *hosted* Centrifugo relays. Free open source does **not** imply a free
  pre-built product — just like you can self-host Ghost or Mattermost for free
  while their hosted plans cost money.

The value sold is **managed convenience**: working binaries, a hosted relay we
keep running and secure, automatic updates, and (for teams) the admin console.

### Tiers

Per-seat/month SaaS, self-serve, no contracts — matches how individuals and
SMBs actually buy.

| Tier | Target | Price (indicative) | Includes |
|---|---|---|---|
| **Open Source** | Everyone | $0 | Full source; build & self-host the relay yourself; sideload the app |
| **Individual** | Solopreneurs, tinkerers, one person | ~$1–3 / month (or ~$20–30 / yr) | Play Store app, auto-updates, hosted relay, email support |
| **Team** | SMBs (5–200 seats) | ~$5–10 / user / month | Admin console, recovery, audit log, RP allow-lists, managed relay, priority email support |
| **Business** | SMBs (200+ seats) | ~$10–15 / user / month | Everything in Team + support SLA, SSO, advanced policies, onboarding |

### Pricing logic

- **Individuals pay a small amount** — the open-source "free" route is the
  self-host path; the Play Store + hosted-relay path is a managed service and
  is priced as one. This converts the open-source community from a cost center
  into revenue from day one.
- **Anchor:** a YubiKey is $25–55 one-time per hardware key, plus per-user
  subscription at enterprises. Our individual plan undercuts one hardware key
  per year, and our team plans undercut per-seat hardware procurement.
- **No free managed tier.** The free tier is the open-source self-host path,
  not a hosted-but-gimped product. That keeps marginal relay cost near zero
  and avoids freeloaders on our infrastructure. (Revisit only if we need
  user-acquisition growth over revenue.)
- **Team plan is where the money is**; Individual is the low-friction upsell
  path — a one-person open-source user whose company grows becomes a Team
  account.

---

## 5. Go-to-market

1. **Open-source community is the funnel.** Individual open-source users
   (already the audience) hit the friction of self-hosting a relay and
   sideloading the app → subscribe to the $2/mo Individual plan. As their
   companies adopt, Individual graduates to Team.
2. **Dev-house beachhead.** Pitch: *"Your admin dashboards and SSH-gateway
   WebAuthn, backed by the phone already in your devs' pockets, with a per-
   client audit trail."* No SSH-key claim.
3. **MSP channel.** Offer reseller/white-label later; MSPs bundle it into
   managed-security packages for their SMB clients.
4. **Content/marketing.** Guides for "WebAuthn on headless servers,"
   "phishing-resistant MFA for SMBs," comparison vs. hardware keys.
5. **Direct sales for Business tier.** Sales-assisted, small team.
6. **Play Store presence.** The paid app listed on Play is itself an
   acquisition channel — anyone searching "security key app" or "passkey" on
   Android finds a ready-to-use product instead of a GitHub repo.

---

## 6. Risks & mitigations

- **FIDO2 adoption is still spreading.** Mitigation: ride the push for
  phishing-resistant auth (executive orders, insurance requirements, platform
  passkey momentum).
- **Browser/platform support for virtual authenticators is evolving.**
  Mitigation: stay close to the WebAuthn spec; keep the daemon surface minimal
  and spec-conformant.
- **Phone availability as a factor.** The phone must be online. Mitigation:
  market to remote/headless use cases (SSH bastions, VPS admin) where a phone
  is already the natural second factor; be explicit about the online
  requirement.
- **"Just buy a YubiKey" objection.** Mitigation: no hardware procurement,
  no USB forwarding, per-seat cost scales to zero marginal unit, works where
  plugging in a key isn't possible.
- **"Why pay when it's open source?"** Mitigation: the open-source route is
  self-hosting + sideloading — real friction (run Centrifugo, TLS cert, app
  updates, pairing). The paid product sells removal of that friction. This is
  the standard open-core trade, and it only holds if the hosted path is
  genuinely more convenient.
- **Scope creep.** Mitigation: this plan explicitly rejects SSH keys, SSO, and
  password management. New features must serve *remote WebAuthn*.

### Licensing consideration

Open-source license must be permissive enough (e.g. MIT/Apache-2.0) for trust
and community contribution, while the *product* — Play Store listing, hosted
relay service, admin console backend — is proprietary/closed. This is the
standard open-core split: the wire protocol and both peers stay open; the
management plane we run for customers doesn't have to be.

---

## 7. Roadmap (SMB-focused)

1. **M0 (current):** polish open-source — stable daemon + Android app, robust
   UHID virtual authenticator, TDD suite, docs.
2. **M1:** managed relay hosting + device registry backend (foundation of the
   admin console) + paid Play Store listing (Individual plan).
3. **M2:** Team tier — admin console, bulk onboarding, recovery, audit log, RP
   allow-lists.
4. **M3:** Business tier — SSO, priority support, advanced policies.
5. **Later (not SMB):** self-hosted relay, SOC 2, SSH `sk-*` support.

---

## 8. Definition of "done" for the paid tier

- An individual can install the app from Play Store, pay, and pair in < 10
  minutes without touching the relay.
- Admin can revoke any device in < 1 minute.
- Lost-phone recovery requires no support ticket.
- Audit log answers "who authenticated, when, from where" per RP.
- Managed relay runs with no plaintext visible to us (existing E2EE
  guarantees preserved).
- Onboarding a 20-person team takes < 1 hour.
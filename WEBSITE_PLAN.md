# WEBSITE_PLAN.md — Gatebridge Marketing Site

**Brand:** Gatebridge · **Domain:** `gatebridge.app`
**Companions:** [`BUSINESS_PLAN.md`](BUSINESS_PLAN.md) · [`BRAND.md`](BRAND.md) · [`PROTOCOL.md`](PROTOCOL.md)

---

## 1. Purpose

The marketing site is the primary conversion surface for the open-core funnel
described in `BUSINESS_PLAN.md` §5. It must:

1. **Educate** a niche technical audience on an unfamiliar product category
   (Android phone as a remote WebAuthn authenticator).
2. **Differentiate** against both "just buy a YubiKey" and "install an IAM
   stack" objections.
3. **Convert** open-source interest into Individual paid subscriptions, with
   Team as the expansion path.
4. **Signal** technical credibility to security-conscious buyers (dev shops,
   pentest firms, MSPs) without enterprise trust theater.

The site is **not** a hardware catalog, an IAM platform brochure, or an
enterprise sales portal. It is a docs-forward developer-tool marketing site.

---

## 2. Competitive context (summary)

Analysis of competitor sites (Yubico, Nitrokey, Kaspersky, Feitian, RSA,
Token2, Swissbit, OneSpan, Google Titan) yielded these constraints:

| Pattern | Seen at | Gatebridge stance |
|---|---|---|
| Transparent pricing | Token2, Nitrokey | **Adopt** — SMB self-serve depends on it |
| Docs-forward architecture | Yubico, Nitrokey | **Adopt** — this audience lives in docs |
| "How it works" as first-class page | Yubico | **Adopt** — category is unfamiliar; education is the sale |
| Clear open-source vs. paid boundary | Token2, Nitrokey | **Adopt** — monetization mechanism is that friction |
| Comparison content | Kaspersky tiers (honest version) | **Adopt** — answer "just buy a YubiKey" on-site |
| Logo walls / CISO quotes | Yubico, RSA | **Avoid** — no enterprise logos yet; faking them undermines trust |
| Award badges / lab seals | Kaspersky | **Avoid** — meaningless before independent validation |
| Hidden pricing / contact-sales-only | RSA, HID, Entrust, OneSpan | **Avoid** — wrong motion for SMB; reserve sales-assisted for Business tier only |
| Lifestyle photography | Yubico | **Avoid** — mismatched with developer-tool product |
| Mega-menus (40+ items) | Yubico, RSA | **Avoid** — flat, predictable nav for a niche product |
| Dual-carousel corporate newsrooms | Swissbit, OneSpan | **Avoid** — signals "large company"; a simple blog index is more honest |

**Closest structural analogs:** Vercel, Linear, Tailscale, Fly.io, Smallstep —
technical audience, transparent pricing, docs-forward, open-source-friendly,
self-serve funnel.

---

## 3. Site map

```
gatebridge.app
├── /                          Homepage
├── /how-it-works              Architecture, security model, scope boundaries
├── /use-cases                 Segment-mapped scenarios
│   ├── /use-cases/individuals
│   ├── /use-cases/dev-houses
│   ├── /use-cases/msps
│   └── /use-cases/saas-devops
├── /pricing                   Four tiers, transparent, with YubiKey anchor
├── /compare                   Gatebridge vs. hardware keys vs. platform passkeys vs. legacy OTP
├── /security                  Threat model, E2EE design, key storage, biometric gating
├── /open-source               GitHub, license, contribution guide, open-core split
├── /blog                      Guides, comparison posts, protocol notes, release notes
├── /docs                      Quickstart, admin guide, daemon install, protocol reference
├── /company                   About, contact, status
└── /business                  Business-tier CTA ("Talk to us") — sales-assisted
```

### Page specifications

#### 3.1 Homepage (`/`)

- **Hero:** one-sentence value prop —
  *"Use your Android phone as a hardware-backed FIDO2 security key for your
  remote Linux servers."*
- **Sub-line:** the differentiator vs. buying YubiKeys — no hardware
  procurement, no USB forwarding, works where you can't plug in a key.
- **Three-card "How it works":**
  1. Pair your phone (QR / `fidobridge://` URI)
  2. Linux daemon intercepts WebAuthn on the server
  3. Biometric signature in phone hardware via E2EE relay
- **Two CTAs:** **Get started** (paid path — Play Store / hosted relay) and
  **Self-host free** (open-source GitHub).
- **Trust strip:** "Open source · End-to-end encrypted · Keys never leave
  your phone" — **no** customer logo walls.

#### 3.2 How it works (`/how-it-works`)

- System diagram: Linux daemon ↔ encrypted relay ↔ Android app.
- Security model summary (link to `/security` for depth).
- Explicit scope boundaries: **not** an SSH key replacement, **not** SSO,
  **not** a password manager, **not** a general FIDO2 server.
- This is the most important differentiator page — it educates the audience
  on why this is different from both "just buy a YubiKey" and "install an
  IAM stack."

#### 3.3 Use cases (`/use-cases/*`)

Segment-mapped, matching `BUSINESS_PLAN.md` §2:

| Segment | Scenario narrative |
|---|---|
| Individuals / solo devs | Single VPS / remote box; hardware-backed WebAuthn without buying a key |
| Dev houses & agencies | SSH bastions, admin panels, CI dashboards; per-client audit trail |
| MSPs & IT service providers | Same pain replicated across many clients' servers |
| Small SaaS / DevOps teams | Control-plane auth, vendor dashboards, remote workers |

Each page: short scenario narrative + relevant features + CTA. Not feature
checklists.

#### 3.4 Pricing (`/pricing`)

- **Transparent, visible pricing** — deliberate differentiator against
  RSA/HID/Entrust/OneSpan.
- Four tiers side-by-side:

  | Tier | Target | Price (indicative) | Includes |
  |---|---|---|---|
  | Open Source | Everyone | $0 | Full source; build & self-host relay; sideload app |
  | Individual | Solopreneurs, tinkerers | ~$1–3/mo (or ~$20–30/yr) | Play Store app, auto-updates, hosted relay, email support |
  | Team | SMBs (5–200 seats) | ~$5–10/user/mo | Admin console, recovery, audit log, RP allow-lists, managed relay, priority support |
  | Business | SMBs (200+ seats) | ~$10–15/user/mo | Everything in Team + SSO, SLA, advanced policies, onboarding |

- **Anchor callout:** "One YubiKey costs $25–55. Gatebridge Individual costs
  less than one key per year."
- Open-source vs. paid distinction must be crystal clear (Ghost/Mattermost
  pattern): self-hosting is free; the paid product is convenience (managed
  relay, auto-updates, admin console).

#### 3.5 Comparison (`/compare`)

- Direct comparison table:
  - Gatebridge
  - Hardware security keys (YubiKey class)
  - Platform passkeys (Apple/Google/Microsoft)
  - Legacy OTP (TOTP apps, SMS)
- Columns: cost per seat, works on headless Linux, no hardware procurement,
  phishing-resistant, admin console, audit log.
- This is the content-marketing wedge called for in `BUSINESS_PLAN.md` §5.4 —
  make it a first-class page, not a blog post.

#### 3.6 Security model (`/security`)

- Threat model (what the relay can and cannot see).
- E2EE design: Noise Protocol (`Noise_IK_25519_AESGCM_SHA256`), per-session
  keys, perfect forward secrecy.
- Key storage: Android Keystore / TEE / StrongBox; biometric gating
  (no software-only fallback).
- Pairing: trust-on-first-use, public-key pinning, rejection of unknown
  phones.
- Link to [`PROTOCOL.md`](PROTOCOL.md) / technical docs for the skeptical
  audience (pentest firms, security-conscious dev shops).
- **Replaces** the customer testimonial wall: for an early-stage security
  product, technical transparency *is* the trust signal.

#### 3.7 Open source (`/open-source`)

- GitHub organization link, license (MIT/Apache-2.0 — see `BUSINESS_PLAN.md`
  §6 licensing consideration).
- Architecture overview, contribution guide.
- Honest explanation of the open-core split:
  - **Open:** source, daemon, Android app, wire protocol.
  - **Paid:** Play Store listing, hosted relay service, admin console backend.
- Community hooks: Discord/Matrix, GitHub Discussions, blog changelog.

#### 3.8 Blog (`/blog`)

Content-led acquisition per `BUSINESS_PLAN.md` §5.4:

- "WebAuthn on headless servers: a practical guide"
- "Phishing-resistant MFA for SMBs without an IAM budget"
- "Gatebridge vs. YubiKeys: when hardware keys make sense, when they don't"
- "How Android Keystore hardware backing works"
- Protocol specs, security notes, release notes.

#### 3.9 Docs (`/docs`)

- Quickstart (Individual tier).
- Team admin guide.
- Daemon installation.
- Protocol reference (link to `PROTOCOL.md`).
- Docs should be a separate section or subdomain (Yubico pattern:
  `docs.gatebridge.app`) — this audience lives in docs.

#### 3.10 Company / Business

- `/company`: minimal — about, contact, support email, status page.
- `/business`: Business-tier CTA ("Talk to us") for the sales-assisted
  segment (SSO, SLA, advanced policies).

---

## 4. Visual style

| Dimension | Recommendation | Rationale |
|---|---|---|
| **Overall aesthetic** | Clean, modern developer-tool SaaS — dark mode optional, generous whitespace, monospace accents for technical terms | Matches audience expectations (Vercel, Linear, Tailscale); distance from hardware-catalog (Feitian) and enterprise-brochure (RSA) styles |
| **Color** | One primary accent (e.g., teal/green "bridge" or electric blue), neutral greys, dark mode support | Yubico uses teal; avoid copying directly. Developer tools skew dark; offer both. |
| **Typography** | Inter or similar geometric sans for UI; JetBrains Mono / Fira Code for code, terminal commands, protocol snippets | Signals "built for developers" without gimmicks |
| **Imagery** | Architecture diagrams, terminal screenshots, phone-meets-server illustrations — **no** stock photos of people in offices | The product is abstract (a bridge between phone and server); diagrams sell it better than lifestyle photography |
| **Icons** | Simple line icons or none; avoid badge/award clutter | Kaspersky-style award walls would look unearned at this stage |
| **Density** | Medium — more text than Yubico's sparse heroes, less than Nitrokey's dense grids | The audience reads; they also scan. Docs-forward sites balance both. |
| **Motion** | Minimal — subtle hover states, maybe a looping "how it works" animation in the hero | Avoid heavy carousels of RSA/OneSpan; those signal "enterprise marketing," not "developer tool" |
| **Mobile** | Fully responsive; the product's other half *is* a phone — show the Android app UI clearly | Practically, many visitors will be on mobile; thematically, the phone is the product |

---

## 5. Navigation

Flat and predictable — **no** mega-menus:

```
Product · How it works · Pricing · Docs · Blog · GitHub
```

- **Product** → `/use-cases` (dropdown or direct)
- **Docs** → `/docs` (or `docs.gatebridge.app`)
- **GitHub** → external link to the org
- Footer: Security, Open source, Company, Contact, Status, Legal

---

## 6. Funnel design

```
Awareness     → Blog / guides / comparison pages / Play Store listing
Interest      → Homepage → How it works → Security model
Evaluation    → Pricing (transparent) → Comparison vs. hardware keys
Activation    → "Get started" → Play Store install + hosted relay pairing
  (Individual)              → OR "Self-host free" → GitHub
Expansion    → Individual user at a growing company → Team tier
  (Team)                    → Admin console, recovery, audit log pitch
Enterprise   → Business tier → "Talk to us" (sales-assisted, SSO, SLA)
```

The site's primary job at launch is **conversion of open-source interest
into Individual paid subscriptions**, with Team as the revenue expansion
path. Every page must make the self-host vs. hosted tradeoff legible,
because that friction *is* the monetization mechanism
(`BUSINESS_PLAN.md` §4, §6).

---

## 7. Implementation notes

### Directory

This plan lives in `WEBSITE_PLAN.md` (repo root, alongside `ANDROID_PLAN.md`
and `DAEMON_PLAN.md`). The site scaffold lives in [`website/`](website/).

### Framework (TBD)

No framework has been chosen yet. Requirements that constrain the choice:

- Static-site generation or SSG-adjacent (marketing + docs + blog).
- MDX or similar for content pages (blog, docs, use-cases).
- Dark mode support.
- Fast build times (docs-heavy site).
- Easy deployment to a static host (Cloudflare Pages, Netlify, Vercel).

**Leading candidates:** Astro (content-collection-first, zero-JS by default),
Hugo (fast, mature), Eleventy (flexible). Decision deferred until content
volume justifies the tradeoffs.

### Content sources

| Content | Source of truth | Site location |
|---|---|---|
| Security model details | `PROTOCOL.md`, `agents.md` | `/security`, `/docs` |
| Product scope / non-goals | `BUSINESS_PLAN.md` §1 | `/how-it-works`, `/compare` |
| Pricing tiers | `BUSINESS_PLAN.md` §4 | `/pricing` |
| Use-case segments | `BUSINESS_PLAN.md` §2 | `/use-cases/*` |
| Brand name, domain, color | `BRAND.md` | Global chrome |
| Open-core split | `BUSINESS_PLAN.md` §4, §6 | `/open-source` |

### Launch blockers (from `BRAND.md` §5)

Before public launch:

- [ ] Formal trademark clearance opinion (attorney) in Class 9 / 42.
- [ ] Register the trademark.
- [ ] Secure social handles (@gatebridge on GitHub, X, etc.).
- [ ] Consider `getgatebridge.com` / `trygatebridge.com` redirects.

---

## 8. Definition of done (marketing site)

- [ ] Homepage communicates the one-sentence value prop in < 5 seconds.
- [ ] Pricing is visible without a sales call (Individual + Team).
- [ ] `/compare` answers "just buy a YubiKey" on-site.
- [ ] `/security` links to `PROTOCOL.md` and explains the threat model.
- [ ] `/open-source` states the open-core split honestly.
- [ ] Docs quickstart gets an Individual-tier user to first signature in
      < 30 minutes (mirrors `BUSINESS_PLAN.md` §8 pairing goal).
- [ ] No fabricated logos, awards, or testimonials anywhere.
- [ ] Dark mode works; mobile is fully responsive.
- [ ] Site builds and deploys from CI on every merge to main.

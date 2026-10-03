# BRAND.md — Gatebridge

**Product name:** Gatebridge
**Domain:** `gatebridge.app`
**Codename during development:** "FIDO2 Android Bridge" (also `fidobridge://`
pairing URI scheme in `PROTOCOL.md`).

---

## 1. What the product is

A niche product: **remote WebAuthn**. Use an Android phone as a hardware-backed
FIDO2/WebAuthn authenticator for a remote Linux server. The phone holds the
keys in its secure element; the daemon on the server bridges WebAuthn requests
to the phone over an E2EE relay.

The name encodes the product's action: **the app bridges a gate** — it is the
bridge at the authentication gate, carrying WebAuthn requests across the gap
between a remote server and the user's phone.

---

## 2. Naming rules applied (source: top naming experts)

Distilled from Lexicon Branding (David Placek), SurveyMonkey, Brandwatch,
Segment8, Atlassian's naming team, LaunchWeek.ai, GeneratorBrain, and
NamingCube:

1. **Short** — 1–3 syllables; fits an app icon.
2. **Easy to pronounce and spell** — must survive the "phone test" (say it;
   the listener must be able to spell it back).
3. **Memorable above all** — "if you are forgettable, you will lose."
4. **Distinctive, not descriptive** — avoid generic words (Smart, Quick, Key,
   Guard); descriptive names in a crowded category are invisible and
   unprotectable.
5. **Communicate an idea, don't describe a feature** — leaves room to grow.
6. **Ownable** — trademark-clear in Class 9 (software) / 42 (SaaS).
7. **Available** — domain + social handles; a good name on a good TLD beats a
   worse name on `.com`.
8. **No negative meaning in major languages.**
9. **Scalable** — survives the five-year test (expansion beyond today's scope).
10. **Attitude/color, not comfort** — signals something new rather than being
    merely safe.
11. **Test with buyers** — 5-second clarity test, recall after 24h,
    competitor-shelf test.

**Strategy chosen:** the category (FIDO2/passkeys) is established but brand
awareness is low → experts recommend **suggestive/coined**, not descriptive.
"Gatebridge" is a literal-but-suggestive compound: it evokes the product's
action (bridging a gate) without naming any feature.

---

## 3. Naming journey & screening results

### Direction: "bridge"

The product *is* a bridge (browser ↔ phone, server ↔ key). Candidates and
outcomes:

| Candidate | Result | Reason |
|---|---|---|
| FIDO2Android | codename only | Terrible marketing name; "FIDO" is the FIDO Alliance's registered mark; descriptive; doesn't scale |
| Fidobridge | rejected | "FIDO" trademark exposure; descriptive/invisible |
| Keybridge | rejected | `keybridge.com` taken (1999); **Utimaco "KeyBRIDGE" HSM product** is an active trademark in crypto/key-management (Class 9/42) — direct conflict; also an open-source WebAuthn tool named `keybridge` exists |
| Viaduct | rejected | User preference (metaphor too abstract) |
| Crossing | viable but weak | No direct trademark conflict found, but common English word = weak distinctiveness; `crossing.io` available |
| Drawbridge | rejected | **Two prior users in identity/security**: an active cybersecurity company (alt-invest niche) and a cross-device identity/ad-tech company (acquired by LinkedIn) |
| Gatepass | rejected | Great meaning (a real compound: "authorization to pass a checkpoint") but **crowded in access-control**: GatePass.ai, GatePass Play Store app, gatepass.io, MyGatePass — confusingly-similar risk in an adjacent class |
| **Gatebridge** | **chosen** | The literal "gate + bridge" combination; clean trademark clearance (existing users are all trade/real-estate/consulting, none in software/security); `gatebridge.app` registered by us |
| Portbridge | runner-up | No auth/security conflict, but Teradyne's "PortBridge" (chip-test software) and a Portuguese bridge-machinery company exist |

### Note on the "-gate" suffix

The *"-gate" suffix* is Watergate-scarred ("Bridgegate" is a real political
scandal). **Gatebridge puts "gate" first**, avoiding that association
entirely.

---

## 4. Domain

| Domain | Status |
|---|---|
| `gatebridge.com` | taken (1999, parked) — route/reconsider if ever affordable |
| `gatebridge.app` | **owned by us** — the product's home |
| `gatebridge.io` | taken (2021, unused; confirmed against the .io registry) |
| `gatebridge.dev` | available |
| `getgatebridge.com` / `trygatebridge.com` | check at purchase time |

`.app` is HTTPS-mandatory at the registry level — a security signal that pairs
naturally with the WebAuthn positioning.

---

## 5. What's decided / what still needs doing

**Decided:**
- Product name: **Gatebridge**
- Primary domain: `gatebridge.app`
- Code/repo names, `fidobridge://` URI scheme, and package identifiers may
  keep the development codename — they are technical identifiers, not the
  brand. (Renaming them is optional and not required by the brand decision.)

**Still to do before any public launch:**
- Formal trademark clearance opinion (attorney) in Class 9 / 42.
- Register the trademark.
- Secure social handles (@gatebridge on GitHub, X, etc.).
- Consider `getgatebridge.com` / `trygatebridge.com` as `.com`-adjacent
  redirects.
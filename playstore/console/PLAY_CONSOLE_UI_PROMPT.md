# PLAY_CONSOLE_UI_PROMPT — copy-paste for a browser agent

Use this prompt with an agent that can drive a browser (Play Console UI).
Do **not** re-create products or base plans — those are already live via API.

---

## Prompt (copy everything below)

```
You are updating Google Play Console UI fields for an existing Android app.

## Account / access
- Google account email: icebraining@gmail.com
- Sign in to https://play.google.com/console with that account.
- Play Console may ask for 2FA on the owner's phone — stop and report if you cannot complete login; do not invent codes.
- App package name: com.fidobridge.client
- Product / app name on Play: Gatebridge
- If multiple apps appear, choose the one whose package name is com.fidobridge.client.

## Already done — DO NOT redo
These exist in Play Console already (created/updated via Android Publisher API):
- Subscription products (ACTIVE):
  - gatebridge_individual_monthly — base plan "monthly" — US $1.99 / P1M
  - gatebridge_individual_yearly — base plan "yearly" — US $19.99 / P1Y
- Free-trial offers (ACTIVE, 1 month free, new users, this subscription):
  - monthly-free-trial
  - yearly-free-trial
- Subscription (product) listing titles/descriptions/benefits (en-US) already set.
Do NOT delete, recreate, or change product IDs, prices, base plan IDs, or trial offers unless a field is missing and you report it first.

## Your job — Console UI store listing + identity (text fields only)
Work through Play Console menus for this app. Prefer "Main store listing" / "Store presence" / "App content" as labeled in the current UI.

### 1) Main store listing (en-US unless Console requires another default)
Paste EXACTLY:

App title:
Gatebridge

Short description (77 chars, max 80):
Hardware-backed passkey sign-in for remote Linux. Managed relay subscription.

Full description (copy the entire block between the fences, including line breaks):

Gatebridge turns your Android phone into a hardware-backed security key for remote Linux systems. Sign in to web apps and admin panels on servers you access remotely, without buying a physical key and without USB forwarding. The Play Store product is a paid managed service: the pre-built app plus a hosted encrypted relay.

Problem

WebAuthn and passkeys are the phishing-resistant standard for browser sign-in. On a remote Linux box — an SSH bastion, a VPS, a control plane, an admin panel — that standard often falls apart. There is no USB port you can reach. There is no hardware key to plug in through a forwarded connection. TOTP is phishable. Passwords are phishable. You are left with weaker auth exactly where root access lives.

How it works

1. Subscribe. Choose Individual monthly or yearly on Google Play. The first month is free; after that, the price shown in Play applies.

2. Run the Gatebridge daemon on your Linux server. Install the daemon, point it at the managed relay, and leave it running under systemd.

3. Scan the pairing QR code. Open the Gatebridge app on your phone and scan the QR the daemon prints. Pairing establishes an encrypted channel in under a minute.

4. Sign in. When a relying party on the server requests a passkey, the app prompts for fingerprint or face. The phone signs inside its secure hardware. The server receives the signed response and completes the WebAuthn ceremony.

What your subscription includes

- The pre-built Gatebridge app from Google Play, with automatic updates.
- A hosted, encrypted relay so you do not run your own broker.
- Email support.
- Billing handled by Google Play. Manage or cancel the subscription in your Play Store account.

Security

Private keys never leave Android KeyStore. Generation and signing happen inside TEE or StrongBox on devices that support it. Every signature requires a biometric prompt — there is no software-only fallback. The relay is treated as untrusted. Payloads are end-to-end encrypted with Noise (Noise_IK_25519_AESGCM_SHA256); the relay sees ciphertext only. Replay or tamper attempts drop the connection and abort the request.

Who it is for

- Individuals with a VPS or remote box who want hardware-backed passkeys without buying hardware.
- Software teams that need phishing-resistant sign-in on bastions, admin panels, and control planes.
- MSPs managing client infrastructure who want passkey auth without per-seat hardware procurement.

Requirements

- Android 8.0 or later.
- Biometric hardware on the phone (fingerprint or face).
- The phone must be online while a sign-in is in progress.
- A Linux server running the Gatebridge daemon.

What this is not

- Not a password manager. Gatebridge does not store or fill passwords.
- Not an SSH key replacement. It does not implement SSH security keys.
- Not an SSO product. It does not replace an identity provider.
- Not a general FIDO2 server. It does one job: remote WebAuthn backed by your phone.

Pricing

Individual plans are available monthly or yearly through Google Play. The first month is free. After the trial, the price shown in Play Console at launch applies. There is no permanent free managed tier. Cancel anytime in the Play Store; access continues until the end of the paid period.

Open source and self-hosting

The full source code is on GitHub at https://github.com/gatebridgeapp/fido2-android-bridge. Free APKs are published on GitHub Releases for anyone who runs their own Centrifugo relay. The paid product on Google Play is the hosted managed service — pre-built app, managed relay, auto-updates, and email support — not a software license. Self-hosting is supported; it is not a free tier of the managed product.

Install Gatebridge, start the one-month free trial, and pair your phone to your Linux server in minutes.

### 2) Contact / links (if the form asks)
- Website: https://gatebridge.app
- Privacy policy URL: https://gatebridge.app/privacy
  NOTE: If Play blocks an empty/unreachable privacy URL, still enter the URL. If the page 404s, report that — do not invent a different privacy host. (Site page may not be live yet.)
- Email: hello@gatebridge.app
- Phone: leave blank / skip unless Console requires it — if required, STOP and report "phone field needs owner input".

### 3) Category / tags (if present in UI)
- Category: Business (fallback Tools if Business unavailable)
- Tags / keywords (if free-text): security, passkeys, fido2, webauthn, mfa, linux, server, two-factor
- If Console only offers predefined categories, pick the closest Business/Tools security category and report what you chose.

### 4) Graphic assets
If Console asks for graphics NOW:
- Do NOT invent images.
- Report: "Graphics not uploaded in this pass — owner will supply icon 512x512, feature graphic 1024x500, screenshots per playstore/console/assets.md."
- Skip upload if optional; do not leave required broken fields half-filled without saying so.

### 5) Optional: declarations if the UI forces a linear setup wizard
Only if you cannot save store listing without them — otherwise leave for a later pass and report:
- Data Safety: follow playstore/console/compliance.md (no analytics SDK; diagnostics local on release; in-app purchase = subscription; E2EE in transit; reset app deletes pairing/credentials on device).
- Content rating: not child-directed; no UGC; no ads → aim Everyone / PEGI 3.
- Target audience: not children; do not enable Families.
- Encryption: app-layer Noise E2EE + TLS.
- App Access: if required, enter: "Gatebridge is a remote WebAuthn authenticator. Review path: Internal/Closed track + license testers; demo pairing at https://gatebridge.app/review. Test contact hello@gatebridge.app."

### 6) Do NOT do in this pass
- Do not upload APK/AAB or create releases/tracks unless listing save requires a draft app first — if you must create a draft to save listing, do it and report.
- Do not change subscription prices, product IDs, or trial offers.
- Do not enable production.
- Do not invent legal entity, phone numbers, or addresses.
- Do not claim FIDO Alliance endorsement.
- Do not paste relay hostnames (relay.gatebridge.app) into public listing fields.

## Reporting format
When finished (or blocked), return a structured report:
1. Login: success / blocked (reason)
2. App selected: package name confirmed?
3. Fields updated: list each field + before/after or "set to provided value"
4. Fields skipped/blocked: reason
5. Screenshots/paths of forms if your tools support it
6. Next human actions

Read source material from these repo files if needed (do not modify them):
- playstore/console/listing.md
- playstore/console/compliance.md
- playstore/console/assets.md
- playstore/console/PLAY_CONSOLE_CONFIG.md
```

---

## Human checklist after the agent runs

- [ ] Confirm login used **icebraining@gmail.com** and package **com.fidobridge.client**
- [ ] Spot-check short description (77 chars) and full description in Console preview
- [ ] Privacy URL accepted (or note if site `/privacy` still 404s)
- [ ] Products still show $1.99 / $19.99 + 1-month trials (API state unchanged)
- [ ] Upload graphics later per `playstore/console/assets.md`
- [ ] License testers + App Access + Data Safety if agent deferred them

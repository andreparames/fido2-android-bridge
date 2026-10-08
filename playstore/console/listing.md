# Play Store Listing — Gatebridge

Paste-ready text for Google Play Console (short description, full description, and identity fields). Copy values into Console exactly as written below.

---

## Identity fields

| Field | Value |
|---|---|
| App name | Gatebridge |
| Short description | `Hardware-backed passkey sign-in for remote Linux. Managed relay subscription.` |
| Application ID | `com.fidobridge.client` |
| Category | Business (fallback Tools) |
| Tags | security, passkeys, fido2, webauthn, mfa, linux, server, two-factor |
| Website | https://gatebridge.app |
| Privacy policy URL | https://gatebridge.app/privacy — **must be live before production track** |
| Contact email | hello@gatebridge.app |
| Phone | [OWNER: fill in Play Console] |

**Short description character count: 77** (limit 80).

---

## Full description

Copy the block below into Play Console. Character count is ~3,000–4,000 and must stay inside that band.

```text
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
```

---

## Copy rules (for the human — do not paste into Console)

1. Do not claim FIDO Alliance endorsement, certification, or partnership anywhere in the listing, screenshots, or marketing site.
2. Always state biometric hardware and online-phone requirements wherever the product is described. Never imply a signature works offline or without fingerprint/face.
3. Never imply a free managed relay or a permanent free tier. The only free path is the self-host open-source route on GitHub.
4. GitHub mentions = self-hosting only. Do not present GitHub as a cheaper paid option for the managed product.
5. Never write "pay less on our website" or any external-payment pitch for the hosted tier. Subscriptions are Play Billing only.
6. Prices are not hardcoded in this listing. Enter the live price in Play Console at launch; copy may only say "the price shown in Play".
7. Branding is Gatebridge only in user-facing copy. Do not use the development codename "FIDO Bridge" in the listing.
8. Store copy may say "Gatebridge-managed encrypted relay" without raw hostnames. The production host for docs/engineering is `relay.gatebridge.app`; never paste personal/dev hosts into Console fields.
9. Application ID stays `com.fidobridge.client`. Do not propose a rename in Console fields.
10. No emoji in any Play Console field. Developer-tool voice: direct, concrete, short sentences.
11. Privacy policy at https://gatebridge.app/privacy must be live before the production track is submitted.
12. Phone field remains an owner-filled placeholder until Play Console contact details are ready.

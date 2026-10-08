# Gatebridge — Play Console compliance pack

Prepared for Play Console data-safety, rating, audience, and App Access
forms. Package: `com.fidobridge.client`. Branding: Gatebridge. Source of
truth: `playstore/plan.md`.

---

## 1. Purpose note (paste-ready)

> Gatebridge is a remote WebAuthn authenticator for Android. The app turns the
> phone into a hardware-backed security key for remote or headless Linux
> systems. Users pair the app with a companion Linux daemon via a QR code.
> When a WebAuthn (passkey/security-key) challenge is requested on the remote
> machine, the app shows the Android BiometricPrompt and signs the challenge
> with a private key that never leaves Android's hardware keystore
> (TEE/StrongBox). Communication with the relay that carries messages is
> end-to-end encrypted; the relay sees ciphertext only. There is no advertising
> SDK, no analytics SDK, and no third-party tracker in the app code. The paid
> Play product is a managed subscription that provides the pre-built app and
> hosted relay access; the 1-month trial applies to both subscription
> products. There is no permanent free managed tier.

---

## 2. Data Safety form — completed answers

| Play Console item | Answer | Detail to paste into free-text where allowed |
|---|---|---|
| Data collected — App activity | Yes | In-memory sign-in request log only (relying-party domain / `rpId`, request type, outcome, timestamp). Held in device memory for the session. Not uploaded to third parties; not used for ads. |
| Data collected — Network communications | Yes | Encrypted relay traffic between the app and the relay the user paired with (Centrifugo WebSocket). Payloads are sealed with app-layer E2EE before they leave the device; the relay forwards opaque ciphertext. |
| Data collected — Device or other IDs | Yes | Relay pairing uses a channel ID (32-char lowercase hex) and the device's public key shown in the pairing URI. **No advertising ID.** No device fingerprinting for ads. |
| Data collected — Personal info (name, email) | No | No in-app account. Users do not create a Gatebridge username or password in the app. Support contact is optional and external to the app. |
| Data collected — Location | No | Not collected. |
| Data collected — Contacts / photos / media / files | No | Camera permission is used only to scan the pairing QR code; images are not stored or uploaded. |
| Data collected — App info and performance | No third-party crash/analytics SDKs | App has no Firebase/Analytics/Crashlytics/Sentry SDKs. On Play/release builds, diagnostic lines are stored only on the device and can be exported locally (see diagnostic note below); they are not sent to Gatebridge or the relay. |
| Data shared | **None** | No analytics, no ads, no third-party trackers in code. Subscription purchase is handled by Google Play Billing (Google is the payment processor under Play's terms, not a data-sharing analytics partner of ours). |
| Data sold | **No** | |
| Data used for personalisation or ads | **No** | |
| Encrypted in transit | **Yes** | Authentication messages: app-layer E2EE using Noise `Noise_IK_25519_AESGCM_SHA256` (per-session keys; relay sees ciphertext only). Connection to the relay also uses TLS. |
| Encrypted at rest | **Yes** | Signing keys: Android Keystore (TEE/StrongBox, hardware-backed, user-authentication required per signature). Pairing material (channel/keys): EncryptedSharedPreferences. |
| User can request data deletion | **Yes** | In-app **Reset app** erases pairing keys and stored credentials on the device. Play/release diagnostics stay local only and can be cleared/exported by the user; exported files are under the user's control. |
| In-app purchases | **Yes** | Subscriptions via Google Play Billing: `gatebridge_individual_monthly` and `gatebridge_individual_yearly`. Each includes a 1-month free trial, then paid. Managed relay access is the subscription benefit. No permanent free managed tier. |
| Opt-in required for data collection | N/A beyond what is disclosed above | The sign-in request log is in-memory session state for the authentication feature; it is not a separate opt-in analytics program. |

### Diagnostic data note (paste into Data Safety free-text / privacy policy)

> The Play Store build stores diagnostic log lines only on the device. The user
> can export them to storage they control with an in-app **Export diagnostics**
> action. Diagnostics are not uploaded to Gatebridge or to the relay on Play
> builds. Lines cover app lifecycle, connection state, and biometric outcome;
> no private key material, credential contents, or plaintext of signing
> requests. Not sold, not used for advertising, not sent to third-party
> analytics. Debug/development builds may publish diagnostics to a relay log
> channel during local debugging only.

---

## 3. Additional declarations table

| Console declaration item | Answer |
|---|---|
| Ads | **None.** No ad SDKs, no ad networks, no ad-based data use. |
| User-generated content (UGC) | **None.** No profiles, posts, comments, or content sharing in the app. |
| Encryption | Standard TLS to the relay, plus app-layer end-to-end encryption (Noise `Noise_IK_25519_AESGCM_SHA256`) for authentication data. Signing keys stay in Android Keystore; the relay never receives plaintext authentication payloads or private keys. |
| Target audience | **Not directed at children.** Business / developer tool. Not designated for Families. No child-appealing themes, characters, or in-app reward mechanics. |
| Content rating (IARC) | **Everyone / PEGI 3.** No violence, no UGC, no gambling, no simulated gambling, no sexual content, no drugs/alcohol, no language, no social interaction features. Core activity is biometric authentication to the user's own remote systems. |
| Health / medical | **N/A.** Not a health app; does not collect health data. |
| Financial features | **None** beyond Play-managed subscriptions for the managed relay service. No budgeting, banking, lending, crypto-wallet, or payment-processing features inside the app. Purchases are handled by Google Play Billing. |
| Data safety / privacy | See section 2. Privacy policy URL: `https://gatebridge.app/privacy` (must be live HTTPS before production track). |
| Remote access / VPN / network admin | The app is a WebAuthn authenticator endpoint, not a VPN or remote-control tool. It does not tunnel device traffic; it signs WebAuthn challenges over an E2EE relay channel on user request. |

---

## 4. App Access form — paste-ready paragraph

> Gatebridge is a remote WebAuthn authenticator. The Android app receives a
> WebAuthn / CTAP2 challenge from a companion Linux daemon over an encrypted
> relay, shows the Android BiometricPrompt, and signs the challenge with a
> hardware-backed key that never leaves Android Keystore.
>
> Core flow for a reviewer:
> 1. Subscribe to a Gatebridge plan through Google Play Billing
>    (`gatebridge_individual_monthly` or `gatebridge_individual_yearly`;
>    1-month free trial), or use a Play license-testing Google account with
>    internal/closed testing.
> 2. Pair the app with the Linux daemon by scanning the daemon's pairing QR
>    code (pairing URI `fidobridge://pair?channel=...&pubkey=...`, PROTOCOL.md
>    v3).
> 3. On the remote Linux host, trigger a WebAuthn / passkey sign-in. The
>    daemon forwards the challenge over the relay; the app prompts for
>    biometric authentication and returns the signature over the same E2EE
>    channel.
>
> Review path:
> 1. Install the Gatebridge build from the Internal testing or Closed testing
>    track.
> 2. Use a license-testing Google account set up in Play Console (in-app
>    purchase testing).
> 3. Open https://gatebridge.app/review for the demo pairing URI and hosted
>    demo relay instructions.
>
> A companion Linux daemon is required for real WebAuthn transactions. The
> review kit at https://gatebridge.app/review provides a pre-provisioned
> pairing path and demo relay instructions so reviewers can complete the flow
> without deploying their own daemon. Hardware biometric authentication
> (fingerprint or face) is required; the device must support biometrics.
>
> Test contact: hello@gatebridge.app
>
> Note: https://gatebridge.app/review must be live (HTTPS) before the
> production track; until then use the Internal/Closed track build with the
> review kit draft instructions.

---

## 5. Privacy policy URL note

- Policy URL to enter in Play Console (listing and App Access):  
  **https://gatebridge.app/privacy**
- This URL must be live and served over HTTPS before the production track.
- Draft source lives at `playstore/legal/privacy-policy.md` (same repo folder);
  publish from that draft to the marketing site.
- The policy must disclose local-only diagnostics on Play builds and the
  **Export diagnostics** action; debug builds may use a relay log channel
  during development only. Also disclose the in-memory request log, Keystore /
  EncryptedSharedPreferences use, subscription handling via Google Play
  Billing, and the in-app Reset app deletion path.
- Support: hello@gatebridge.app. Security contact: security@gatebridge.app.

---

## 6. Console checklist this file supports

| Console step | Where addressed |
|---|---|
| Data Safety form (collected, shared, sold, encryption, deletion, purchases) | Section 2 table + diagnostic note |
| Ads declaration | Section 3 |
| UGC declaration | Section 3 |
| Encryption declaration (TLS + E2EE) | Section 3, section 2 transit row |
| Target audience / Families | Section 3 |
| IARC content rating questionnaire | Section 3 (Everyone / PEGI 3 basis) |
| Health / medical declaration | Section 3 (N/A) |
| Financial features declaration | Section 3 (Play subscriptions only) |
| App Access form | Section 4 paragraph |
| Privacy policy URL | Section 5 (`https://gatebridge.app/privacy`) |
| Review kit URL | Section 4 (`https://gatebridge.app/review`) |

### Still human-filled in Console (not defined by this pack)

- Play developer contact phone.
- Legal entity name / seller identity if Play requires it.
- Final live prices in Play Billing products (docs phrase as "1 month free,
  then the price shown in Play").
- IARC questionnaire click-through to obtain the final rating certificate.

Note: production managed relay host is **not** a Console field; it is
`relay.gatebridge.app` (see playstore/plan.md / playstore/plans/billing.md).

# Draft privacy policy for Gatebridge

**DRAFT — must be published at https://gatebridge.app/privacy before Play production track.**

**Product:** Gatebridge (Android app, package name `com.fidobridge.client`)
**Domain:** https://gatebridge.app
**Effective date:** [OWNER: date]
**Legal entity:** [OWNER: legal entity if Play requires]
**Contact:** hello@gatebridge.app

---

## 1. Who we are

Gatebridge makes a remote WebAuthn authenticator for Linux servers. The app on your phone does the signing; a daemon on the Linux box you pair with receives requests and sends them over an encrypted relay. We operate at https://gatebridge.app.

- General contact: [hello@gatebridge.app](mailto:hello@gatebridge.app)
- Security reports: [security@gatebridge.app](mailto:security@gatebridge.app)

## 2. What the app is

Gatebridge is a remote WebAuthn / passkeys client. It turns your Android phone into a hardware-backed security key for remote and headless Linux machines. It is not a password manager and not an SSH key replacement.

Gatebridge is an open-core product:

- The Android app, the Linux server program, and the message protocol are open source (see https://github.com/gatebridgeapp/fido2-android-bridge).
- The hosted managed service (the paid Play Store product: pre-built app, hosted encrypted relay, auto-updates, email support) is a paid subscription.
- You can run Gatebridge entirely on your own infrastructure by downloading the free open-source APK from GitHub Releases and running your own relay.

This privacy policy covers the Gatebridge Play Store app and the Gatebridge-hosted managed relay. Section 9 covers self-hosted deployments.

## 3. Information stored on your device

Gatebridge stores the following on your Android device:

- **Pairing material.** When you scan a pairing QR code, the app stores the daemon public key, the channel ID, and (if the relay requires one) an optional relay connection token. These live in EncryptedSharedPreferences on the device.
- **WebAuthn credentials.** Credential records created during registration stay in the app's storage on the device.
- **Hardware-backed signing keys.** Your private signing key is generated in and never leaves the Android KeyStore (TEE or StrongBox). The private key never leaves the secure hardware element, is never uploaded, and is never recoverable by Gatebridge.
- **Request log (in-memory only).** The app keeps a short in-memory log of recent signing requests for the current app session: relying-party domain, request type, outcome (pending, accepted, or rejected), and timestamp. This log is not persisted to disk and is not uploaded to us or to the relay. It is discarded when the app process ends, when you use Reset app, or when you clear the log in the app.
- **Diagnostic logs (local only on Play/release builds).** The Play Store build stores diagnostic log lines **only on your device**. Typical lines cover app lifecycle, connection state, and biometric authentication outcome (for example, whether biometric auth succeeded or was denied). Diagnostic lines do not include private key material, credential contents, or the plaintext of signing requests. They are **not** uploaded to Gatebridge or to the relay. You can export them to storage you control with the in-app **Export diagnostics** action; after export, the file is under your control and is no longer managed by the app. Debug/development builds may also publish diagnostics to a relay log channel for local debugging; that behavior is not used in the Play/release app.

**Reset app.** The in-app "Reset app" action erases pairing material and stored WebAuthn credentials from the device. It does not delete hardware-backed keys from the Android KeyStore; the device OS manages those. Local diagnostic logs and any files you already exported are not deleted by Reset app unless the OS or you remove them; use the app's clear/export controls and your device's file manager as needed.

## 4. Information sent over the network

Gatebridge communicates with the relay you paired with — on the Play/hosted path, the Gatebridge-managed relay at `relay.gatebridge.app`; on the free self-host path, your own relay.

- **Encrypted WebAuthn traffic.** Signing and registration challenges and responses are exchanged end-to-end encrypted (E2EE) with the relay using the Noise protocol (`Noise_IK_25519_AESGCM_SHA256`). Session keys are derived per connection. The relay only sees ciphertext; it cannot read the challenges, responses, or any plaintext WebAuthn data.
- **Relay identity.** The relay is an untrusted broker. It can see that a paired device connected (channel ID, connection timing, traffic volume) but not the content of the encrypted messages.
- **Diagnostics are not sent over the network on Play/release builds.** Diagnostic log lines stay on the device and can be exported locally (section 3). The Play app does not publish diagnostic logs to the relay. Debug builds may publish to a relay log channel during development only.

## 5. Billing and subscriptions

Subscriptions are sold and processed through Google Play Billing.

- **What Google processes.** Google Play handles payment collection and processing. We never see or store your card number, bank details, or other full payment credentials.
- **What we receive.** We receive purchase and entitlement state from Google Play (for example, the product ID and purchase token) so we can grant access to the managed relay and honor your subscription.
- **Products.** Gatebridge Individual — monthly and yearly (product IDs `gatebridge_individual_monthly` and `gatebridge_individual_yearly`).
- **Trial.** Both products include a 1-month free trial, then become paid at the price shown in Google Play at the time of purchase.
- **Canceling.** Cancel anytime in the Google Play Store under Subscriptions. Cancellation takes effect at the end of the current billing period.
- **Refunds.** Refunds are handled under Google Play refund policies. Google, not Gatebridge, processes refunds.
- **No permanent free managed tier.** There is no permanent free tier for the Gatebridge-hosted managed relay. Free use is only possible by running the open-source components yourself (see section 9).

## 6. What we do not collect

- No analytics SDK. The app contains no Firebase Analytics, Google Analytics, Crashlytics, Sentry, or similar analytics SDKs.
- No advertising identifier. Gatebridge does not use the Android advertising ID for tracking.
- No contacts, location, microphone, or other unrelated data.
- No cloud backup of keys. The app sets `android:allowBackup="false"`, so pairing material and credentials are not included in Android Auto Backup or device-to-device transfer.
- No sale of personal data. We do not sell your personal data to anyone.

## 7. Permissions and why

| Permission | Why |
|---|---|
| CAMERA | Scan the pairing QR code that links the app to your daemon. |
| INTERNET | Connect to the relay you paired with over WebSocket. |
| USE_BIOMETRIC | Show the on-device biometric prompt (fingerprint or face) before every signature. Biometric hardware is required. |
| FOREGROUND_SERVICE and FOREGROUND_SERVICE_DATA_SYNC | Keep the relay session alive while a signing sequence is in progress or the foreground session is active. |
| POST_NOTIFICATIONS | Notify you when the app receives a signing request, so you know a biometric prompt is coming. |

Biometric authentication is enforced at the hardware level (Android KeyStore). There is no software-only fallback: a signature cannot be produced without a successful biometric prompt.

## 8. Data retention and deletion

- **On-device data.** Pairing material, stored WebAuthn credentials, local diagnostic logs, and the in-memory request log remain on your device until you use Reset app (pairing/credentials), clear logs in the app, clear the app's data, re-pairing overwrites pairing material, or you delete an exported diagnostics file yourself. Hardware-backed keys are managed by the Android OS and are not deleted by Reset app.
- **App diagnostics.** On the Play/release build, diagnostics are local-only and are not retained on Gatebridge infrastructure. Exported files are stored where you save them and are under your control.
- **Managed-relay infrastructure.** The Gatebridge-hosted relay at `relay.gatebridge.app` may keep limited infrastructure logs (for example, connection or error records) only as needed to run the service. [OWNER: confirm infrastructure log retention before production] The Play app does not send diagnostic log lines to that relay.
- **Purchase records.** Google Play holds your purchase and subscription records under Google's own policies. You can view and manage them in the Play Store.

## 9. Open-source / self-hosted deployments

If you run your own daemon and your own relay (the free GitHub Releases APK plus self-hosted relay path), that operator — often you — processes data under your own deployment policies. You control your own relay, your own operational logs, and your own retention.

This policy covers the Gatebridge Play Store app and the Gatebridge-hosted managed relay. It does not change what happens inside infrastructure you operate yourself.

## 10. Children

Gatebridge is not directed at children. We do not knowingly collect personal information from children. If you believe a child has provided personal information to us, contact hello@gatebridge.app and we will delete it.

## 11. Changes

We may update this privacy policy. The current version is published at https://gatebridge.app/privacy. Continued use of the Gatebridge app after a change constitutes acceptance of the updated policy.

## 12. Contact

Questions about this policy:

- General: [hello@gatebridge.app](mailto:hello@gatebridge.app)
- Security: [security@gatebridge.app](mailto:security@gatebridge.app)

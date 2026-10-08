# Gatebridge — Play Store Review Kit / App Access

Package: `com.fidobridge.client`
Demo access URL: https://gatebridge.app/review
Support: hello@gatebridge.app | Security: security@gatebridge.app

## 1. Purpose

This kit lets Google reviewers exercise the full managed product flow
**subscribe → pair → biometric sign → status UI** without installing any
Linux packages on their machine. The deep self-host path is optional and
documented separately.

Reviewers should complete the happy-path script (Section 4) in under five
minutes on a physical Android device with biometric hardware (fingerprint or
face). The demo relay, demo fido-daemon, and review pairing URI are
pre-provisioned by Gatebridge ops.

## 2. Demo environment spec (ops when relay launches)

| Item | Spec |
|------|------|
| Relay | Hosted demo Centrifugo instance |
| Daemon | Demo `fido-daemon` started with `--uhid` (virtual HID, no hardware authenticator required) |
| Channel | One **stable demo channel** for the review cycle |
| Pairing URI | Regenerated **per review cycle**; posted at https://gatebridge.app/review. Never reuse a production subscriber channel. |
| Challenge sink | Demo daemon logs WebAuthn challenges to a **review-only sink** (review log, not production logs) |
| Demo relay host | `relay.gatebridge.app` (WebSocket `wss://relay.gatebridge.app/connection/websocket`) — **dedicated demo channel only**, never a production subscriber channel |
| Review page | https://gatebridge.app/review |

Constraints:
- Demo channel material (channel hex, pubkey) is review-only; rotate before each production-track review round.
- The demo daemon runs in `--uhid` mode so reviewers do not need a real security key on the server.
- Relay payloads remain E2EE; the review sink sees challenge metadata (rpId, type, timestamp) needed to confirm acceptance, not signing keys.

## 3. License tester path

1. Play Console → Settings → License testing: add the reviewer Google account(s) as license testers.
2. License testers receive the subscription entitlement **free** (no payment flow during review).
3. In the `play` flavor, open the **Subscribe** screen.
4. Select either product — `gatebridge_individual_monthly` or `gatebridge_individual_yearly`.
5. Start the **1-month trial**. No permanent free tier exists; the trial is one month on both products, then the price shown in Play.

## 4. Happy-path script (5 minutes)

| Step | Action |
|------|--------|
| 1 | Install the Internal/Closed track build (`com.fidobridge.client`). |
| 2 | Open the app → **Subscribe** → start the 1-month trial (license tester). |
| 3 | From Home (unpaired state) → open **Pairing**. |
| 4 | Scan or paste the demo pairing URI from https://gatebridge.app/review. |
| 5 | Status screen shows **Connected**. |
| 6 | Trigger WebAuthn from the demo server (webauthn.io via the demo daemon, or a scripted `getAssertion`). |
| 7 | The biometric prompt appears showing the RP id → approve → the in-app request log shows **Accepted**. |

Notes:
- Device must have biometric hardware enrolled. Keys never leave the Android KeyStore (TEE/StrongBox); every signature requires biometric auth.
- The phone must stay online while the challenge is in flight.
- In-app request log is in-memory (rpId, type, outcome, timestamp) and is not uploaded.

## 5. Deep path (optional)

Reviewers who want to self-host the Linux daemon against the demo relay:

- Follow the quickstart: https://gatebridge.app/docs/quickstart
- Pair the self-hosted daemon to the demo relay channel using the pairing URI issued for that session.
- The demo relay remains the endpoint; the daemon runs on the reviewer's own machine with `--uhid` or a local authenticator.

The deep path is **not required** for review. The happy-path script in Section 4 is sufficient.

## 6. Fallback if demo relay is down

If https://gatebridge.app/review is unreachable or the demo daemon cannot connect:

1. Provide a **30-second screen recording** of the full flow.
2. Provide **annotated screenshots** covering:
   - Pairing screen (URI scanned/pasted)
   - Status screen showing **Connected**
   - Biometric prompt with the RP id
   - Request log showing **Accepted**

Recordings and screenshots may be attached to the review notes or hosted at the review URL once ops restores the demo.

## 7. Error states to verify

| Condition | Expected behaviour |
|-----------|--------------------|
| Relay unreachable | Connection error on Status / relay transport; app does not silently succeed. Pairing may complete locally but handshake fails until relay is reachable. |
| Subscription expired / not entitled | Subscribe gate blocks the managed pairing path; no permanent free tier is offered. |
| Malformed pairing URI — missing `channel` or `pubkey` | Reject (PROTOCOL.md §2.3: missing `channel` or `pubkey` → reject). |
| Malformed pairing URI — `pubkey` not decoding to exactly 32 bytes | Reject (bad pubkey length). |
| Malformed pairing URI — uppercase `channel` | Reject; channel must be exactly 32 lowercase hex chars `[0-9a-f]{32}` (PROTOCOL.md §2.3). |
| Version mismatch — `v` present and not `3` | Reject with `VERSION_MISMATCH` (0x7F) per PROTOCOL.md §2.3; absent `v` defaults to `3`. |
| Biometric cancel / failure | Abort immediately with `CTAP2_ERR_OPERATION_DENIED` (0x27). No software-only fallback signing. |

## 8. Hosting notes

- **Draft:** this file is the in-repo draft of the reviewer-facing page.
- **Live target:** https://gatebridge.app/review must be published before the production track review.
- The live page will be an Astro page, following the same pattern as `security.astro` on the Gatebridge site (draft now; site PR later).
- The review page must show: current demo pairing URI (regenerated per review cycle), demo relay host, quickstart link, and the fallback assets from Section 6.

## 9. What reviewers should NOT need

Reviewers must **not** need:

- Production subscriber data or production relay channels
- Real personal pairing URIs (demo channel only; rotate per cycle)
- Play payment with a real card — license testers are entitled free
- Linux package installation on the reviewer's machine (demo path only; deep path optional)
- Any access to Gatebridge production infrastructure, support mailboxes, or the marketing site beyond the review page

## Branding and product constraints

- Product name: **Gatebridge** (not "FIDO Bridge" in user-facing copy).
- Package: `com.fidobridge.client`.
- Play products: `gatebridge_individual_monthly`, `gatebridge_individual_yearly`.
- Trial: 1 month free on both products; **no permanent free tier**.
- The `fidobridge://` URI scheme is a protocol constant (PROTOCOL.md §2) and stays unchanged; it is not the product name.
- Relay is untrusted; E2EE (Noise) end to end. Private keys never leave Android KeyStore.

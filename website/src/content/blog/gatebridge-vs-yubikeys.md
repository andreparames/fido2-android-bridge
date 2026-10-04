---
title: "Gatebridge vs. YubiKeys: when hardware keys make sense"
description: "An honest comparison: physical security keys vs. a phone-backed bridge for remote Linux sign-in."
pubDate: 2026-10-04
tags: ["comparison", "yubikey", "security keys"]
---

The most common objection to Gatebridge is: "Why not just buy a YubiKey?"

It's a fair question. Physical security keys are excellent. They work offline, they're trusted at enterprise scale, and they're phishing-resistant. But they're not always the right tool.

## Where YubiKeys win

- **Offline sign-in.** A YubiKey works with no network. Gatebridge needs your phone online and reachable through a relay.
- **Compliance.** Many regulated environments require certified hardware tokens.
- **Shared workstations.** A physical key can be handed to a contractor or used on a kiosk.
- **No phone dependency.** If the phone is lost, dead, or out of battery, the key still works.

## Where Gatebridge wins

- **Headless and remote Linux.** You can't plug a YubiKey into a server in a datacenter. USB forwarding is fragile. A phone-backed bridge works wherever the server has network access.
- **No per-seat hardware buying.** A YubiKey costs $25 to $55 per person. That adds up at 50 seats. Gatebridge costs less than one key per year.
- **No extra hardware to manage.** The phone is already in every employee's pocket. No lost keys to reissue, no inventory to track.
- **Instant revocation.** Lose a phone? Revoke it in the admin console. Lose a YubiKey? Order a replacement and re-enroll.

## The honest answer

If your team needs offline sign-in or is in a compliance regime that requires physical tokens, buy YubiKeys.

If your team signs in to remote Linux servers and can't justify per-seat hardware, or can't physically plug in a key, Gatebridge is the pragmatic stronger-than-password option.

They're not mutually exclusive. Many organizations use both: YubiKeys for laptops, Gatebridge for servers.

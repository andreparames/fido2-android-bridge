---
title: "Individual developers and solo founders"
description: "One VPS, one phone, hardware-backed sign-in. No security keys to buy or manage."
segment: "Individuals"
---

## Scenario

You run a single VPS or a few remote boxes. You administer them over SSH, and you have set up security-key sign-in on your admin panel or a self-hosted dashboard. You want stronger protection than passwords, but buying a YubiKey for one person feels like overkill, and USB forwarding to a remote session is unreliable.

## How Gatebridge helps

- Pair your phone once. Every security-key sign-in on your servers routes through it.
- No hardware to purchase, ship, or replace.
- The signing secret stays inside your phone's security chip.
- Works from anywhere your phone has network access: cafe, airport, home office.

## Typical setup

```
Your laptop (SSH) → VPS running the Gatebridge daemon → Admin panel → Your phone
```

## What it costs

The Individual plan (about $2 per month) includes the Play Store app, hosted relay, and email support. Prefer free? Self-host the relay and sideload the app. Zero cost, more setup.

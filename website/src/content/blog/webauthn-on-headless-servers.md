---
title: "Security-key sign-in on headless servers: a practical guide"
description: "How to get stronger-than-password sign-in working on SSH bastions, VPSes, and remote Linux boxes without buying a YubiKey for every seat."
pubDate: 2026-10-04
tags: ["security keys", "linux", "guide"]
---

Security-key sign-in was designed for browsers on laptops with a second device nearby. Headless Linux servers (SSH bastions, VPSes, control planes) break that assumption. You can't plug in a USB security key on a box in a datacenter, and USB forwarding over SSH is fragile at best.

## The problem

When a security-key-aware app on a remote server needs you to sign in with a hardware key:

1. The browser or CLI on the server starts the sign-in flow.
2. It needs a hardware security key to sign the challenge.
3. There's no key present. The phone is in your pocket, the server is elsewhere.

## Options

### USB forwarding

`ssh -Y` or `usbip` can forward a physical key to a remote session. It works, but:

- Requires a physical key on the machine in front of you.
- USB/IP is finicky across networks.
- Doesn't work when the "client" is itself a jump host.

### Virtual authenticators

Browsers expose a virtual authenticator API for testing. Production use is unreliable and doesn't give you real hardware-backed security.

### A phone-based bridge

Gatebridge takes a different approach: a small program on the Linux server presents itself as a security key. When a sign-in request starts, it forwards the challenge to your Android phone over an encrypted relay. The phone signs it inside its security chip after you approve with a fingerprint or face. The signed response returns to the server.

The website or CLI never knows the difference. It sees a normal hardware security key.

## What you need

- A Linux server (VPS, bastion, dev box) where you can run a small program.
- An Android phone with fingerprint or face unlock.
- A relay (self-hosted, or Gatebridge's managed one).

## Summary

For remote and headless Linux, a phone-backed bridge gives you hardware-key sign-in without buying hardware. It's not a replacement for physical keys in every context, but where you can't plug in a key, it's the pragmatic path.

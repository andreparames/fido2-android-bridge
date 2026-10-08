"""Relay mode detection: classic vs managed (managed-relay plan, phase A).

Managed iff the relay URL's host (properly parsed) is exactly
``relay.gatebridge.app`` (case-insensitive). Scheme, port, and path are
ignored when the host matches. Look-alike hosts (``evil-relay.gatebridge.app``,
``relay.gatebridge.app.evil.com``) and unparsable URLs are classic, so an
unknown or future layout never accidentally routes to the Gatebridge API.
"""

from __future__ import annotations

from urllib.parse import urlparse

MANAGED_RELAY_HOST = "relay.gatebridge.app"


def is_managed_relay(url: str | None) -> bool:
    """Return True if `url` targets the managed relay host (fail closed)."""
    if not url:
        return False
    try:
        hostname = urlparse(url).hostname
    except ValueError:
        # Malformed URLs (e.g. unbalanced IPv6 brackets) must not crash the
        # daemon: treat them as classic.
        return False
    if hostname is None:
        return False
    return hostname.lower() == MANAGED_RELAY_HOST
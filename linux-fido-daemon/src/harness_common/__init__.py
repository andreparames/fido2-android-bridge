"""Shared integration harness infrastructure for daemon ↔ Android testing.

Provides configuration and Centrifugo client wrappers used by both
``mock_daemon`` (tests the Android app) and ``mock_phone`` (tests the daemon).
"""

from harness_common.config import HarnessConfig

__all__ = ["HarnessConfig"]

"""MockDaemon — publish synthetic CTAP2 requests and validate Android responses.

This module acts as a stand-in for the Linux daemon when testing the Android
app end-to-end through a real Centrifugo broker.  It connects to Centrifugo,
subscribes to ``fidobridge.<channel_id>``, seals and publishes a canned
``getAssertion`` or ``makeCredential`` request, then waits for and validates
the sealed response.
"""

from __future__ import annotations

import argparse
import asyncio
import base64
import json
import logging
import sys
import uuid

from fido_daemon.noise import StaticKeyStore
from fido_daemon.protocol import (
    TYPE_ASSERTION_RESULT,
    TYPE_MAKE_CREDENTIAL_RESULT,
    TYPE_ERROR,
)
from fido_daemon.relay import RelayClient
from harness_common.config import HarnessConfig

logger = logging.getLogger(__name__)

CLIENT_DATA_HASH = b"\x11" * 32


def _build_get_assertion_request(request_id: str) -> dict:
    """Build a PROTOCOL.md §5.1 getAssertion plaintext message."""
    return {
        "version": 1,
        "type": "getAssertion",
        "id": request_id,
        "payload": {
            "clientDataHash": base64.b64encode(CLIENT_DATA_HASH).decode().rstrip("="),
            "rpId": "example.com",
            "allowCredentials": [],
            "option": {"up": True, "uv": True},
        },
    }


def _build_make_credential_request(request_id: str) -> dict:
    """Build a PROTOCOL.md §5.2 makeCredential plaintext message."""
    return {
        "version": 1,
        "type": "makeCredential",
        "id": request_id,
        "payload": {
            "clientDataHash": base64.b64encode(CLIENT_DATA_HASH).decode().rstrip("="),
            "rpId": "example.com",
            "user": {
                "id": base64.b64encode(b"user-1").decode().rstrip("="),
                "name": "alice@example.com",
                "displayName": "Alice",
            },
            "pubKeyCredParams": [{"alg": -7}],
            "excludeCredentials": [],
        },
    }


def _validate_assertion_result(payload: dict) -> list[str]:
    """Validate a PROTOCOL.md §5.3 assertionResult payload. Returns errors."""
    errors = []
    for field in ("credentialId", "authenticatorData", "signature"):
        if field not in payload:
            errors.append(f"missing field: {field}")
        elif not isinstance(payload[field], str):
            errors.append(f"{field}: expected string, got {type(payload[field]).__name__}")
    if "authenticatorData" in payload:
        try:
            auth_data = base64.b64decode(payload["authenticatorData"] + "==")
            if len(auth_data) < 37:
                errors.append(f"authenticatorData too short: {len(auth_data)} bytes")
            else:
                flags = auth_data[32]
                if flags & 0x01 == 0:
                    errors.append("UP flag not set")
                if flags & 0x04 == 0:
                    errors.append("UV flag not set")
        except Exception as e:
            errors.append(f"authenticatorData decode failed: {e}")
    return errors


def _validate_make_credential_result(payload: dict) -> list[str]:
    """Validate a PROTOCOL.md §5.4 makeCredentialResult payload. Returns errors."""
    errors = []
    for field in ("credentialId", "authenticatorData", "attestationObject"):
        if field not in payload:
            errors.append(f"missing field: {field}")
    if "attestationObject" in payload:
        try:
            import fido2.cbor as cbor

            att_obj = cbor.decode(base64.b64decode(payload["attestationObject"] + "=="))
            if att_obj.get("fmt") != "none":
                errors.append(f"fmt is {att_obj.get('fmt')!r}, expected 'none'")
        except Exception as e:
            errors.append(f"attestationObject decode failed: {e}")
    return errors


class MockDaemon(RelayClient):
    """Extends RelayClient with test-specific request building and validation."""

    def __init__(self, config: HarnessConfig) -> None:
        super().__init__(
            url=config.relay_url,
            channel_id=config.channel_id,
            static_private=config.daemon_static_private(),
            token=config.relay_token or "",
        )
        self._config = config

    async def send_and_receive(
        self,
        request: dict,
        timeout: float | None = None,
        retries: int = 0,
        retry_delay: float = 5.0,
    ) -> dict:
        """Seal + publish a request dict and await the parsed response dict.

        ``retries`` re-publishes the same request (same ``id``) on timeout.
        This tolerates the peer subscribing to the channel slightly late, at
        which point the re-published request is received and answered.
        """
        timeout = timeout or self._config.request_timeout
        last_error: BaseException | None = None
        for attempt in range(retries + 1):
            if attempt > 0:
                logger.warning(
                    "retrying id=%s (attempt %d/%d)",
                    request.get("id"),
                    attempt,
                    retries,
                )
                await asyncio.sleep(retry_delay)
            plaintext = json.dumps(request).encode("utf-8")
            try:
                response_bytes = await self.request(plaintext, timeout=timeout)
                return json.loads(response_bytes.decode("utf-8"))
            except asyncio.TimeoutError as exc:
                last_error = exc
                logger.warning(
                    "timeout waiting for response id=%s (attempt %d/%d)",
                    request.get("id"),
                    attempt + 1,
                    retries + 1,
                )
        assert last_error is not None
        raise last_error


async def _run_scenario(
    daemon: MockDaemon, scenario: str, retries: int = 0
) -> bool:
    """Run a single test scenario. Returns True on success."""
    ok = True

    if scenario in ("get-assertion", "all"):
        request_id = str(uuid.uuid4())
        request = _build_get_assertion_request(request_id)
        logger.info("scenario: get-assertion (id=%s)", request_id)

        try:
            response = await daemon.send_and_receive(request, retries=retries)
            logger.info("response type=%s id=%s", response.get("type"), response.get("id"))

            if response.get("id") != request_id:
                logger.error("id mismatch: sent %s, got %s", request_id, response.get("id"))
                ok = False

            if response.get("type") == TYPE_ERROR:
                code = response.get("payload", {}).get("code")
                logger.error("received error response: code=0x%02x", code)
                ok = False
            elif response.get("type") != TYPE_ASSERTION_RESULT:
                logger.error("unexpected response type: %s", response.get("type"))
                ok = False
            else:
                errors = _validate_assertion_result(response.get("payload", {}))
                if errors:
                    for e in errors:
                        logger.error("validation error: %s", e)
                    ok = False
                else:
                    logger.info("get-assertion: OK")
        except asyncio.TimeoutError:
            logger.error("get-assertion: TIMEOUT after %.1fs", daemon._config.request_timeout)
            ok = False
        except Exception as e:
            logger.error("get-assertion: FAILED (%s)", e)
            ok = False

    if scenario in ("make-credential", "all"):
        request_id = str(uuid.uuid4())
        request = _build_make_credential_request(request_id)
        logger.info("scenario: make-credential (id=%s)", request_id)

        try:
            response = await daemon.send_and_receive(request, retries=retries)
            logger.info("response type=%s id=%s", response.get("type"), response.get("id"))

            if response.get("id") != request_id:
                logger.error("id mismatch: sent %s, got %s", request_id, response.get("id"))
                ok = False

            if response.get("type") == TYPE_ERROR:
                code = response.get("payload", {}).get("code")
                logger.error("received error response: code=0x%02x", code)
                ok = False
            elif response.get("type") != TYPE_MAKE_CREDENTIAL_RESULT:
                logger.error("unexpected response type: %s", response.get("type"))
                ok = False
            else:
                errors = _validate_make_credential_result(response.get("payload", {}))
                if errors:
                    for e in errors:
                        logger.error("validation error: %s", e)
                    ok = False
                else:
                    logger.info("make-credential: OK")
        except asyncio.TimeoutError:
            logger.error("make-credential: TIMEOUT after %.1fs", daemon._config.request_timeout)
            ok = False
        except Exception as e:
            logger.error("make-credential: FAILED (%s)", e)
            ok = False

    return ok


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="mock-daemon",
        description="Mock daemon: publishes synthetic CTAP2 requests to test the Android app",
    )
    parser.add_argument(
        "scenario",
        choices=["get-assertion", "make-credential", "all"],
        help="which scenario to run",
    )
    parser.add_argument("--verbose", action="store_true")
    parser.add_argument(
        "--timeout",
        type=float,
        default=None,
        help="override request timeout in seconds (default: from FIDO2_REQUEST_TIMEOUT or 10)",
    )
    parser.add_argument(
        "--retries",
        type=int,
        default=3,
        help="re-publish each request on timeout until a response arrives (default: 3)",
    )
    args = parser.parse_args(argv)

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )

    config = HarnessConfig.from_env()
    if args.timeout is not None:
        object.__setattr__(config, "request_timeout", args.timeout)

    logger.info("channel_id: %s", config.channel_id)
    logger.info("relay: %s", config.relay_url)
    logger.info("token: %s", "set" if config.relay_token else "anonymous")

    async def _run() -> bool:
        daemon = MockDaemon(config)
        try:
            await daemon.connect()
            return await _run_scenario(daemon, args.scenario, retries=args.retries)
        finally:
            await daemon.close()

    ok = asyncio.run(_run())
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())

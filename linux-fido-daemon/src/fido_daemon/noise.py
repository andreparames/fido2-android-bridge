"""Noise IK transport, static key store, and wire envelope (PROTOCOL.md v3).

The daemon is the **responder** in the ``Noise_IK_25519_AESGCM_SHA256``
handshake (PROTOCOL.md §6); the phone is the initiator.  The handshake uses
a prologue pinned to ``FIDO2_BRIDGE_V2``.  Session keys are derived from
per-connection ephemeral keys (perfect forward secrecy) and the transport
rejects replayed/reordered frames via its 64-bit per-direction nonce counters
(PROTOCOL.md §3.2).
"""

from __future__ import annotations

import json
import os
import re
import stat
from dataclasses import dataclass
from pathlib import Path

from cryptography.hazmat.primitives import serialization
from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.asymmetric import x25519
from noise.connection import Keypair, NoiseConnection
from noise.exceptions import NoiseInvalidMessage, NoiseValueError

from fido_daemon.crypto import b64decode, b64encode

NOISE_PROTOCOL_NAME = "Noise_IK_25519_AESGCM_SHA256"
NOISE_PROLOGUE = "FIDO2_BRIDGE_V2"
STATIC_KEY_BYTES = 32

KIND_IK1 = "ik1"
KIND_IK2 = "ik2"
KIND_DATA = "data"
ENVELOPE_KINDS = frozenset({KIND_IK1, KIND_IK2, KIND_DATA})

CHANNEL_ID_PATTERN = re.compile(r"^[0-9a-f]{32}$")


class NoiseError(Exception):
    """Base class for Noise protocol errors."""


class NoiseAuthenticationError(NoiseError):
    """Raised when a handshake or transport message fails authentication."""


@dataclass(frozen=True)
class WireEnvelope:
    channel_id: str
    kind: str
    payload: bytes


def envelope_to_json(envelope: WireEnvelope) -> str:
    return json.dumps(
        {
            "channel_id": envelope.channel_id,
            "kind": envelope.kind,
            "payload": b64encode(envelope.payload),
        }
    )


def envelope_from_json(raw: str) -> WireEnvelope:
    """Strict decode of the PROTOCOL.md §3 wire envelope. Rejects malformed
    input (no silent nulls, unknown keys, mixed base64)."""
    try:
        data = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise ValueError("wire envelope is not valid JSON") from exc
    if not isinstance(data, dict):
        raise ValueError("wire envelope must be a JSON object")
    if set(data) != {"channel_id", "kind", "payload"}:
        raise ValueError(
            "wire envelope keys must be exactly {channel_id, kind, payload}"
        )
    channel_id = data["channel_id"]
    if not isinstance(channel_id, str) or not CHANNEL_ID_PATTERN.fullmatch(channel_id):
        raise ValueError("channel_id must be 32 lowercase hex chars")
    kind = data["kind"]
    if not isinstance(kind, str) or kind not in ENVELOPE_KINDS:
        raise ValueError("kind must be one of ik1, ik2, data")
    payload = b64decode(data["payload"])
    return WireEnvelope(channel_id=channel_id, kind=kind, payload=payload)


class StaticKeyStore:
    """Load/generate the daemon's long-term X25519 static identity key.

    The 32-byte private scalar is stored with mode ``0600``; the public key is
    derived and placed in the pairing URI (PROTOCOL.md §2.4).
    """

    @staticmethod
    def generate() -> bytes:
        private = x25519.X25519PrivateKey.generate()
        return private.private_bytes(
            serialization.Encoding.Raw,
            serialization.PrivateFormat.Raw,
            serialization.NoEncryption(),
        )

    @staticmethod
    def public_key(private_scalar: bytes) -> bytes:
        if len(private_scalar) != STATIC_KEY_BYTES:
            raise ValueError(f"private key must be {STATIC_KEY_BYTES} bytes")
        return (
            x25519.X25519PrivateKey.from_private_bytes(private_scalar)
            .public_key()
            .public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
        )

    @staticmethod
    def load(path: Path) -> bytes:
        data = path.read_bytes()
        if len(data) != STATIC_KEY_BYTES:
            raise ValueError(f"static key file must contain {STATIC_KEY_BYTES} bytes")
        return data

    @staticmethod
    def save(path: Path, private_scalar: bytes) -> None:
        if len(private_scalar) != STATIC_KEY_BYTES:
            raise ValueError(f"private key must be {STATIC_KEY_BYTES} bytes")
        path.parent.mkdir(parents=True, exist_ok=True)
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        try:
            with os.fdopen(fd, "wb") as handle:
                handle.write(private_scalar)
        except BaseException:
            os.unlink(path)
            raise
        os.chmod(path, stat.S_IRUSR | stat.S_IWUSR)

    @classmethod
    def load_or_create(cls, path: Path) -> bytes:
        if path.exists():
            return cls.load(path)
        private = cls.generate()
        cls.save(path, private)
        return private


class NoiseResponderSession:
    """Daemon-side responder session for ``Noise_IK_25519_AESGCM_SHA256``.

    A fresh instance is created for every handshake (one per WebSocket
    session), giving each session fresh ephemeral keys.
    """

    def __init__(self, static_private: bytes, prologue: bytes | str = NOISE_PROLOGUE) -> None:
        if len(static_private) != STATIC_KEY_BYTES:
            raise ValueError(f"static private key must be {STATIC_KEY_BYTES} bytes")
        self._conn = NoiseConnection.from_name(NOISE_PROTOCOL_NAME.encode("ascii"))
        self._conn.set_as_responder()
        self._conn.set_prologue(prologue)
        self._conn.set_keypair_from_private_bytes(Keypair.STATIC, static_private)
        self._conn.start_handshake()
        self._remote_static: bytes | None = None

    @property
    def remote_static(self) -> bytes:
        if self._remote_static is None:
            raise NoiseError("remote static key not learned yet")
        return self._remote_static

    @property
    def handshake_finished(self) -> bool:
        return self._conn.handshake_finished

    def receive_ik1(self, msg1: bytes) -> bytes:
        """Process the initiator's `ik1` and return the `ik2` message to send."""
        if self.handshake_finished:
            raise NoiseAuthenticationError("handshake already finished")
        try:
            self._conn.read_message(msg1)
        except (NoiseInvalidMessage, NoiseValueError, InvalidTag) as exc:
            raise NoiseAuthenticationError("ik1 authentication failed") from exc
        remote = getattr(self._conn.noise_protocol.handshake_state, "rs", None)
        if remote is None or getattr(remote, "public_bytes", None) is None:
            raise NoiseAuthenticationError("ik1 carried no authentic static key")
        self._remote_static = remote.public_bytes
        return bytes(self._conn.write_message(b""))

    def encrypt(self, plaintext: bytes) -> bytes:
        if not self.handshake_finished:
            raise NoiseError("handshake not finished")
        return self._conn.encrypt(plaintext)

    def decrypt(self, ciphertext: bytes) -> bytes:
        if not self.handshake_finished:
            raise NoiseError("handshake not finished")
        try:
            return self._conn.decrypt(ciphertext)
        except (NoiseInvalidMessage, NoiseValueError, InvalidTag) as exc:
            raise NoiseAuthenticationError("transport authentication failed") from exc


class NoiseInitiatorSession:
    """Phone-side initiator session for ``Noise_IK_25519_AESGCM_SHA256``.

    Used by the test phone peers and by the daemon's mock-phone harness tool.
    The daemon itself never instantiates this.
    """

    def __init__(
        self,
        static_private: bytes,
        remote_static_public: bytes,
        prologue: bytes | str = NOISE_PROLOGUE,
    ) -> None:
        if len(static_private) != STATIC_KEY_BYTES:
            raise ValueError(f"static private key must be {STATIC_KEY_BYTES} bytes")
        if len(remote_static_public) != STATIC_KEY_BYTES:
            raise ValueError(f"remote static public key must be {STATIC_KEY_BYTES} bytes")
        self._conn = NoiseConnection.from_name(NOISE_PROTOCOL_NAME.encode("ascii"))
        self._conn.set_as_initiator()
        self._conn.set_prologue(prologue)
        self._conn.set_keypair_from_private_bytes(Keypair.STATIC, static_private)
        self._conn.set_keypair_from_public_bytes(
            Keypair.REMOTE_STATIC, remote_static_public
        )
        self._conn.start_handshake()

    @property
    def handshake_finished(self) -> bool:
        return self._conn.handshake_finished

    def create_ik1(self) -> bytes:
        return bytes(self._conn.write_message(b""))

    def receive_ik2(self, msg2: bytes) -> None:
        try:
            self._conn.read_message(msg2)
        except (NoiseInvalidMessage, NoiseValueError, InvalidTag) as exc:
            raise NoiseAuthenticationError("ik2 authentication failed") from exc

    def encrypt(self, plaintext: bytes) -> bytes:
        if not self.handshake_finished:
            raise NoiseError("handshake not finished")
        return self._conn.encrypt(plaintext)

    def decrypt(self, ciphertext: bytes) -> bytes:
        if not self.handshake_finished:
            raise NoiseError("handshake not finished")
        try:
            return self._conn.decrypt(ciphertext)
        except (NoiseInvalidMessage, NoiseValueError, InvalidTag) as exc:
            raise NoiseAuthenticationError("transport authentication failed") from exc
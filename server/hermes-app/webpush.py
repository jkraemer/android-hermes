"""WebPush payload encryption: RFC 8291 over the RFC 8188 aes128gcm coding."""

import base64
import os
import struct

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

RECORD_SIZE = 4096
_HEADER_SIZE = 16 + 4 + 1 + 65  # salt, record size, key id length, key id
_TAG_SIZE = 16
_PADDING_DELIMITER = b"\x02"  # last (and only) record
# One record: header, plaintext, delimiter, GCM tag.
MAX_PLAINTEXT = RECORD_SIZE - _HEADER_SIZE - len(_PADDING_DELIMITER) - _TAG_SIZE


def b64url_decode(s: str) -> bytes:
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))


def _hkdf(salt: bytes, ikm: bytes, info: bytes, length: int) -> bytes:
    return HKDF(hashes.SHA256(), length, salt, info).derive(ikm)


def encrypt(
    plaintext: bytes,
    p256dh: str,
    auth: str,
    *,
    sender_key: ec.EllipticCurvePrivateKey | None = None,
    salt: bytes | None = None,
) -> bytes:
    """Encrypt `plaintext` for a WebPush subscription's `p256dh` and `auth` keys.

    `sender_key` and `salt` are random unless given (fixed values are for tests).
    Raises ValueError if the plaintext does not fit one record or a key is malformed.
    """
    if len(plaintext) > MAX_PLAINTEXT:
        raise ValueError(f"plaintext exceeds {MAX_PLAINTEXT} bytes")
    try:
        receiver_public = b64url_decode(p256dh)
        auth_secret = b64url_decode(auth)
        receiver_key = ec.EllipticCurvePublicKey.from_encoded_point(
            ec.SECP256R1(), receiver_public
        )
    except ValueError as e:
        raise ValueError("malformed p256dh") from e
    if len(auth_secret) != 16:
        raise ValueError("auth must be 16 bytes")

    sender_key = sender_key or ec.generate_private_key(ec.SECP256R1())
    salt = salt or os.urandom(16)
    sender_public = sender_key.public_key().public_bytes(
        Encoding.X962, PublicFormat.UncompressedPoint
    )

    shared = sender_key.exchange(ec.ECDH(), receiver_key)
    key_info = b"WebPush: info\x00" + receiver_public + sender_public
    ikm = _hkdf(auth_secret, shared, key_info, 32)
    content_key = _hkdf(salt, ikm, b"Content-Encoding: aes128gcm\x00", 16)
    nonce = _hkdf(salt, ikm, b"Content-Encoding: nonce\x00", 12)

    header = salt + struct.pack(">IB", RECORD_SIZE, len(sender_public)) + sender_public
    record = AESGCM(content_key).encrypt(nonce, plaintext + _PADDING_DELIMITER, None)
    return header + record

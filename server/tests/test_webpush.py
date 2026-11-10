import base64

import http_ece
import pytest
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

import webpush

# RFC 8291 section 5.
RFC_PLAINTEXT = b"When I grow up, I want to be a watermelon"
RFC_AUTH_SECRET = "BTBZMqHH6r4Tts7J_aSIgg"
RFC_RECEIVER_PUBLIC = (
    "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcx"
    "aOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"
)
RFC_SENDER_PRIVATE = "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw"
RFC_SALT = "DGv6ra1nlYgDCS1FRnbzlw"
RFC_BODY = (
    "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27ml"
    "mlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPT"
    "pK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN"
)


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def receiver_keys():
    """A fresh receiver key pair as (private key, p256dh, auth)."""
    private = ec.generate_private_key(ec.SECP256R1())
    public = private.public_key().public_bytes(
        encoding=Encoding.X962, format=PublicFormat.UncompressedPoint
    )
    return private, b64url(public), b64url(b"0123456789abcdef")


def test_rfc8291_vector_is_reproduced():
    sender_key = ec.derive_private_key(
        int.from_bytes(webpush.b64url_decode(RFC_SENDER_PRIVATE), "big"), ec.SECP256R1()
    )
    body = webpush.encrypt(
        RFC_PLAINTEXT,
        RFC_RECEIVER_PUBLIC,
        RFC_AUTH_SECRET,
        sender_key=sender_key,
        salt=webpush.b64url_decode(RFC_SALT),
    )
    assert body == webpush.b64url_decode(RFC_BODY)


def test_http_ece_decrypts_our_output():
    private, p256dh, auth = receiver_keys()
    body = webpush.encrypt(RFC_PLAINTEXT, p256dh, auth)
    plaintext = http_ece.decrypt(
        body,
        private_key=private,
        auth_secret=webpush.b64url_decode(auth),
        version="aes128gcm",
    )
    assert plaintext == RFC_PLAINTEXT


def test_oversize_plaintext_is_refused():
    _, p256dh, auth = receiver_keys()
    assert len(webpush.encrypt(b"a" * webpush.MAX_PLAINTEXT, p256dh, auth)) == 4096
    with pytest.raises(ValueError):
        webpush.encrypt(b"a" * (webpush.MAX_PLAINTEXT + 1), p256dh, auth)


def test_each_message_uses_a_fresh_key_and_salt():
    _, p256dh, auth = receiver_keys()
    first = webpush.encrypt(b"same", p256dh, auth)
    second = webpush.encrypt(b"same", p256dh, auth)
    assert first[:86] != second[:86]
    assert first[:16] != second[:16]
    assert first[21:86] != second[21:86]


def test_malformed_keys_are_refused():
    _, p256dh, auth = receiver_keys()
    off_curve = b64url(b"\x04" + b"\x01" * 64)
    with pytest.raises(ValueError):
        webpush.encrypt(b"x", b64url(b"\x04" * 64), auth)
    with pytest.raises(ValueError):
        webpush.encrypt(b"x", off_curve, auth)
    with pytest.raises(ValueError):
        webpush.encrypt(b"x", p256dh, b64url(b"a" * 15))

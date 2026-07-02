"""Encryption-at-rest and PIN hashing.

A Fernet key is generated once and kept in a local file next to the app
(never committed). Google tokens and OAuth client credentials are encrypted
with it before they touch the database; the household PIN is stored only as
a salted PBKDF2 hash.
"""
import base64
import hashlib
import hmac
import os
import secrets

from cryptography.fernet import Fernet, InvalidToken

KEY_FILE = os.environ.get("PARENTOPS_KEY_FILE", ".parentops_key")


def _key():
    if os.path.exists(KEY_FILE):
        with open(KEY_FILE, "rb") as f:
            return f.read().strip()
    k = Fernet.generate_key()
    with open(KEY_FILE, "wb") as f:
        f.write(k)
    try:
        os.chmod(KEY_FILE, 0o600)
    except OSError:  # best effort; not meaningful on Windows
        pass
    return k


def encrypt(text: str) -> str:
    return Fernet(_key()).encrypt(text.encode()).decode()


def decrypt(token: str):
    """Returns the plaintext, or None if the value isn't a valid ciphertext."""
    try:
        return Fernet(_key()).decrypt(token.encode()).decode()
    except (InvalidToken, ValueError):
        return None


def hash_pin(pin: str) -> str:
    salt = secrets.token_bytes(16)
    dk = hashlib.pbkdf2_hmac("sha256", pin.encode(), salt, 200_000)
    return base64.b64encode(salt + dk).decode()


def verify_pin(pin: str, stored: str) -> bool:
    try:
        raw = base64.b64decode(stored)
        salt, dk = raw[:16], raw[16:]
        cand = hashlib.pbkdf2_hmac("sha256", pin.encode(), salt, 200_000)
        return hmac.compare_digest(cand, dk)
    except Exception:
        return False

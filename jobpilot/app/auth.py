import hashlib
import hmac
import secrets

from fastapi import Header, HTTPException

from .db import db


def _hash(pw: str, salt: str) -> str:
    return hashlib.pbkdf2_hmac("sha256", pw.encode(), bytes.fromhex(salt), 200_000).hex()


def _th(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


def signup(email: str, password: str) -> str:
    email = email.strip().lower()
    if "@" not in email or len(password) < 8:
        raise HTTPException(400, "Valid email and a password of 8+ characters are required")
    salt = secrets.token_hex(16)
    with db() as c:
        if c.execute("SELECT 1 FROM users WHERE email=?", (email,)).fetchone():
            raise HTTPException(409, "Email already registered")
        uid = c.execute("INSERT INTO users(email,pw_hash,salt) VALUES(?,?,?)", (email, _hash(password, salt), salt)).lastrowid
        return _new_session(c, uid)


def login(email: str, password: str) -> str:
    with db() as c:
        row = c.execute("SELECT * FROM users WHERE email=?", (email.strip().lower(),)).fetchone()
        if not row or not hmac.compare_digest(row["pw_hash"], _hash(password, row["salt"])):
            raise HTTPException(401, "Wrong email or password")
        return _new_session(c, row["id"])


def _new_session(c, uid: int, scope: str = "full") -> str:
    token = secrets.token_urlsafe(32)
    c.execute("INSERT INTO sessions(token_hash,user_id,scope) VALUES(?,?,?)", (_th(token), uid, scope))
    return token


def new_scoped_token(uid: int, scope: str) -> str:
    with db() as c:
        return _new_session(c, uid, scope)


def _resolve(authorization: str, allowed: tuple[str, ...]) -> int:
    token = authorization.removeprefix("Bearer ").strip()
    if not token:
        raise HTTPException(401, "Not logged in")
    with db() as c:
        row = c.execute("SELECT user_id, scope FROM sessions WHERE token_hash=? AND created_at > datetime('now','-30 days')", (_th(token),)).fetchone()
    if not row or row["scope"] not in allowed:
        raise HTTPException(401, "Session expired")
    return row["user_id"]


def current_user(authorization: str = Header(default="")) -> int:
    return _resolve(authorization, ("full",))


def autofill_user(authorization: str = Header(default="")) -> int:
    """Accepts the full session or the limited extension token."""
    return _resolve(authorization, ("full", "autofill"))

"""Read-only Gmail import of job-alert emails.

Honest scope note: Google has no "only this label" OAuth scope, so we ask for gmail.readonly and then
ONLY ever request messages that match ALERT_QUERY (known job-portal senders). Nothing else is listed or fetched,
and we store only the extracted job fields, never the email itself."""
import base64
import hashlib
import os
import secrets
import time
from urllib.parse import urlencode

import requests
from cryptography.fernet import Fernet, InvalidToken

from ..db import db
from . import email_alerts
from ..store import upsert_jobs

AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
TOKEN_URL = "https://oauth2.googleapis.com/token"
REVOKE_URL = "https://oauth2.googleapis.com/revoke"
API = "https://gmail.googleapis.com/gmail/v1/users/me"
SCOPE = "https://www.googleapis.com/auth/gmail.readonly"

ALERT_SENDERS = ["naukri.com", "naukrigulf.com", "linkedin.com", "indeed.com", "indeed.co.in", "foundit.in", "monsterindia.com",
                 "glassdoor.com", "instahyre.com", "shine.com", "timesjobs.com", "wellfound.com", "cutshort.io", "hirist.tech"]
ALERT_QUERY = "from:(" + " OR ".join(ALERT_SENDERS) + ") newer_than:{days}d"
MAX_MESSAGES_PER_SYNC = 100
MAX_RAW_BYTES = 2_000_000
STATE_TTL = 600


class GmailError(Exception):
    pass


# ---------- config & token vault ----------
def config() -> dict:
    return {"client_id": os.environ.get("GOOGLE_CLIENT_ID", ""), "client_secret": os.environ.get("GOOGLE_CLIENT_SECRET", ""),
            "redirect_uri": os.environ.get("GOOGLE_REDIRECT_URI", "http://localhost:8000/gmail/callback")}


def configured() -> bool:
    c = config()
    return bool(c["client_id"] and c["client_secret"])


def _fernet() -> Fernet:
    key = os.environ.get("GMAIL_TOKEN_KEY")
    if not key:  # dev convenience: a local key file next to the DB. Production must set GMAIL_TOKEN_KEY.
        from ..db import DB_PATH
        path = os.path.join(os.path.dirname(os.path.abspath(DB_PATH)), ".gmail_key")
        if not os.path.exists(path):
            fd = os.open(path, os.O_WRONLY | os.O_CREAT, 0o600)
            with os.fdopen(fd, "w") as f:
                f.write(Fernet.generate_key().decode())
        key = open(path).read().strip()
    return Fernet(key.encode())


def encrypt(s: str) -> str:
    return _fernet().encrypt(s.encode()).decode()


def decrypt(s: str) -> str:
    try:
        return _fernet().decrypt(s.encode()).decode()
    except InvalidToken as e:
        raise GmailError("Stored Gmail connection could not be read. Please reconnect.") from e


# ---------- thin HTTP layer (patched in tests) ----------
def _post(url, **kw):
    return requests.post(url, timeout=20, **kw)


def _get(url, **kw):
    return requests.get(url, timeout=20, **kw)


# ---------- OAuth ----------
def _challenge(verifier: str) -> str:
    return base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()


def start_auth(user_id: int) -> str:
    if not configured():
        raise GmailError("Gmail import is not configured on this server (missing GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET).")
    state, verifier = secrets.token_urlsafe(24), secrets.token_urlsafe(48)
    with db() as c:
        c.execute("DELETE FROM oauth_states WHERE created_at < ?", (time.time() - STATE_TTL,))
        c.execute("INSERT INTO oauth_states(state,user_id,verifier,created_at) VALUES(?,?,?,?)", (state, user_id, verifier, time.time()))
    cfg = config()
    return AUTH_URL + "?" + urlencode({
        "client_id": cfg["client_id"], "redirect_uri": cfg["redirect_uri"], "response_type": "code", "scope": SCOPE,
        "access_type": "offline", "prompt": "consent", "include_granted_scopes": "false", "state": state,
        "code_challenge": _challenge(verifier), "code_challenge_method": "S256"})


def finish_auth(state: str, code: str) -> int:
    """Validates the one-time state, exchanges the code, stores the encrypted refresh token. Returns user_id."""
    with db() as c:
        row = c.execute("SELECT * FROM oauth_states WHERE state=?", (state,)).fetchone()
        c.execute("DELETE FROM oauth_states WHERE state=?", (state,))  # single use
    if not row or time.time() - row["created_at"] > STATE_TTL:
        raise GmailError("This sign-in link expired. Please start again.")
    cfg = config()
    r = _post(TOKEN_URL, data={"code": code, "client_id": cfg["client_id"], "client_secret": cfg["client_secret"],
                               "redirect_uri": cfg["redirect_uri"], "grant_type": "authorization_code", "code_verifier": row["verifier"]})
    if r.status_code != 200:
        raise GmailError("Google did not accept the sign-in. Please try again.")
    tok = r.json()
    if SCOPE not in (tok.get("scope") or "").split():
        raise GmailError("Read access to Gmail was not granted, so nothing was connected.")
    if not tok.get("refresh_token"):
        raise GmailError("Google did not issue offline access. Remove JobPilot at myaccount.google.com/permissions and connect again.")
    prof = _get(API + "/profile", headers={"Authorization": "Bearer " + tok["access_token"]})
    email = prof.json().get("emailAddress", "") if prof.status_code == 200 else ""
    with db() as c:
        c.execute("""INSERT INTO gmail_accounts(user_id,email,refresh_enc,last_error) VALUES(?,?,?,NULL)
                     ON CONFLICT(user_id) DO UPDATE SET email=excluded.email, refresh_enc=excluded.refresh_enc, last_error=NULL,
                     connected_at=CURRENT_TIMESTAMP""", (row["user_id"], email, encrypt(tok["refresh_token"])))
        c.execute("INSERT INTO audit(user_id,action,detail) VALUES(?,?,?)", (row["user_id"], "gmail_connected", email))
    return row["user_id"]


def _access_token(user_id: int) -> str:
    with db() as c:
        row = c.execute("SELECT refresh_enc FROM gmail_accounts WHERE user_id=?", (user_id,)).fetchone()
    if not row:
        raise GmailError("Gmail is not connected.")
    cfg = config()
    r = _post(TOKEN_URL, data={"client_id": cfg["client_id"], "client_secret": cfg["client_secret"],
                               "refresh_token": decrypt(row["refresh_enc"]), "grant_type": "refresh_token"})
    if r.status_code != 200:
        msg = "Google access was revoked or expired. Please reconnect Gmail."
        with db() as c:
            c.execute("UPDATE gmail_accounts SET last_error=?, auto_sync=0 WHERE user_id=?", (msg, user_id))
        raise GmailError(msg)
    return r.json()["access_token"]


def disconnect(user_id: int) -> bool:
    """Revokes at Google (best effort) and deletes our copy of the token."""
    with db() as c:
        row = c.execute("SELECT refresh_enc FROM gmail_accounts WHERE user_id=?", (user_id,)).fetchone()
    revoked = False
    if row:
        try:
            revoked = _post(REVOKE_URL, data={"token": decrypt(row["refresh_enc"])}).status_code == 200
        except Exception:
            revoked = False
    with db() as c:
        c.execute("DELETE FROM gmail_accounts WHERE user_id=?", (user_id,))
        c.execute("DELETE FROM gmail_seen WHERE user_id=?", (user_id,))
        c.execute("INSERT INTO audit(user_id,action,detail) VALUES(?,?,?)", (user_id, "gmail_disconnected", f"revoked_at_google={revoked}"))
    return revoked


# ---------- sync ----------
def sync(user_id: int, days: int = 14) -> dict:
    token = _access_token(user_id)
    h = {"Authorization": "Bearer " + token}
    q = ALERT_QUERY.format(days=max(1, min(days, 60)))
    ids, page = [], None
    while len(ids) < MAX_MESSAGES_PER_SYNC:
        params = {"q": q, "maxResults": 50, **({"pageToken": page} if page else {})}
        r = _get(API + "/messages", headers=h, params=params)
        if r.status_code != 200:
            raise GmailError("Gmail refused the request. Please reconnect.")
        body = r.json()
        ids += [m["id"] for m in body.get("messages", [])]
        page = body.get("nextPageToken")
        if not page:
            break
    ids = ids[:MAX_MESSAGES_PER_SYNC]
    with db() as c:
        seen = {r["msg_id"] for r in c.execute("SELECT msg_id FROM gmail_seen WHERE user_id=?", (user_id,))}
    new_ids = [i for i in ids if i not in seen]
    tally = {"messages_checked": len(new_ids), "messages_with_jobs": 0, "added": 0, "duplicates_merged": 0}
    for mid in new_ids:
        r = _get(f"{API}/messages/{mid}", headers=h, params={"format": "raw"})
        if r.status_code != 200:
            continue  # leave unmarked so the next sync retries
        raw_b64 = r.json().get("raw", "")
        if len(raw_b64) > MAX_RAW_BYTES * 4 // 3:
            raw = b""
        else:
            raw = base64.urlsafe_b64decode(raw_b64 + "=" * (-len(raw_b64) % 4))
        jobs = email_alerts.parse_eml(raw) if raw else []
        if jobs:
            res = upsert_jobs(jobs, "email", user_id=user_id, private=True)
            tally["messages_with_jobs"] += 1
            tally["added"] += res["added"]
            tally["duplicates_merged"] += res["duplicates_merged"]
        with db() as c:
            c.execute("INSERT OR IGNORE INTO gmail_seen(user_id,msg_id) VALUES(?,?)", (user_id, mid))
    with db() as c:
        c.execute("UPDATE gmail_accounts SET last_sync=CURRENT_TIMESTAMP, last_added=?, last_error=NULL WHERE user_id=?", (tally["added"], user_id))
        c.execute("INSERT INTO audit(user_id,action,detail) VALUES(?,?,?)", (user_id, "gmail_sync", str(tally)))
    return tally


def auto_sync_all(has_consent) -> list[dict]:
    """Called by the background loop. Skips users who revoked the email consent."""
    with db() as c:
        users = [r["user_id"] for r in c.execute("SELECT user_id FROM gmail_accounts WHERE auto_sync=1")]
    out = []
    for uid in users:
        if not has_consent(uid, "email_alerts"):
            continue
        try:
            out.append({"user_id": uid, **sync(uid, days=3)})
        except Exception as e:  # one user's failure must not stop the rest
            out.append({"user_id": uid, "error": str(e)})
    return out

import base64
from urllib.parse import parse_qs, urlparse

import pytest

from app.db import db
from app.ingest import gmail
from conftest import make_user
from test_core import _eml


class R:
    def __init__(self, data=None, status=200):
        self._d, self.status_code = data or {}, status

    def json(self):
        return self._d


class FakeGoogle:
    """Stands in for Google's OAuth and Gmail endpoints."""

    def __init__(self):
        self.calls, self.revoked = [], []
        self.token_scope = gmail.SCOPE
        self.refresh_ok = True
        self.messages = {}
        self.list_params = []

    def post(self, url, **kw):
        self.calls.append(("POST", url))
        if url == gmail.REVOKE_URL:
            self.revoked.append(kw["data"]["token"])
            return R(status=200)
        d = kw["data"]
        if d["grant_type"] == "authorization_code":
            assert d["code_verifier"], "PKCE verifier missing"
            return R({"access_token": "AT1", "refresh_token": "RT-secret-123", "scope": self.token_scope}) if d["code"] == "good" else R(status=400)
        return R({"access_token": "AT2"}) if self.refresh_ok else R({"error": "invalid_grant"}, 400)

    def get(self, url, **kw):
        self.calls.append(("GET", url))
        assert kw["headers"]["Authorization"].startswith("Bearer AT")
        if url.endswith("/profile"):
            return R({"emailAddress": "me@gmail.com"})
        if url.endswith("/messages"):
            self.list_params.append(kw["params"])
            return R({"messages": [{"id": i} for i in self.messages]})
        mid = url.rsplit("/", 1)[1]
        return R({"raw": base64.urlsafe_b64encode(self.messages[mid]).decode().rstrip("=")})


@pytest.fixture()
def google(monkeypatch):
    monkeypatch.setenv("GOOGLE_CLIENT_ID", "cid.apps.googleusercontent.com")
    monkeypatch.setenv("GOOGLE_CLIENT_SECRET", "csecret")
    monkeypatch.setenv("GOOGLE_REDIRECT_URI", "http://localhost:8000/gmail/callback")
    g = FakeGoogle()
    monkeypatch.setattr(gmail, "_post", g.post)
    monkeypatch.setattr(gmail, "_get", g.get)
    return g


def _user(client, email, consent=True):
    h = make_user(client, email)
    if consent:
        client.post("/api/consents", headers=h, json={"scope": "email_alerts", "granted": True})
    return h


def _connect(client, h, google):
    url = client.post("/api/gmail/connect", headers=h).json()["url"]
    state = parse_qs(urlparse(url).query)["state"][0]
    r = client.get(f"/gmail/callback?code=good&state={state}", follow_redirects=False)
    return url, state, r


def test_not_configured(client, monkeypatch):
    monkeypatch.delenv("GOOGLE_CLIENT_ID", raising=False)
    h = _user(client, "gm0@x.com")
    assert client.get("/api/gmail/status", headers=h).json()["configured"] is False
    r = client.post("/api/gmail/connect", headers=h)
    assert r.status_code == 400 and "not configured" in r.json()["detail"]


def test_connect_needs_consent_and_builds_safe_auth_url(client, google):
    h = _user(client, "gm1@x.com", consent=False)
    assert client.post("/api/gmail/connect", headers=h).status_code == 403
    client.post("/api/consents", headers=h, json={"scope": "email_alerts", "granted": True})
    q = parse_qs(urlparse(client.post("/api/gmail/connect", headers=h).json()["url"]).query)
    assert q["scope"] == [gmail.SCOPE] and "send" not in q["scope"][0] and q["access_type"] == ["offline"]
    assert q["code_challenge_method"] == ["S256"] and q["code_challenge"][0] and q["state"][0]
    assert q["redirect_uri"] == ["http://localhost:8000/gmail/callback"]


def test_callback_stores_encrypted_token_and_state_is_single_use(client, google):
    h = _user(client, "gm2@x.com")
    _, state, r = _connect(client, h, google)
    assert r.status_code in (302, 307) and r.headers["location"] == "/gmail?connected=1"
    st = client.get("/api/gmail/status", headers=h).json()
    assert st["connected"] and st["account"]["email"] == "me@gmail.com"
    with db() as c:
        enc = c.execute("SELECT refresh_enc FROM gmail_accounts WHERE email='me@gmail.com'").fetchone()["refresh_enc"]
    assert "RT-secret-123" not in enc and gmail.decrypt(enc) == "RT-secret-123"
    again = client.get(f"/gmail/callback?code=good&state={state}", follow_redirects=False)
    assert "error=" in again.headers["location"]  # replayed state rejected


def test_callback_rejects_forged_state_and_missing_scope(client, google):
    h = _user(client, "gm3@x.com")
    r = client.get("/gmail/callback?code=good&state=forged", follow_redirects=False)
    assert "error=" in r.headers["location"]
    google.token_scope = "openid"
    _, _, r = _connect(client, h, google)
    assert "error=" in r.headers["location"]
    assert client.get("/api/gmail/status", headers=h).json()["connected"] is False
    r = client.get("/gmail/callback?error=access_denied", follow_redirects=False)
    assert "cancelled" in r.headers["location"]


def test_sync_imports_private_deduped_and_is_incremental(client, google):
    h = _user(client, "gm4@x.com")
    other = _user(client, "gm4b@x.com")
    _connect(client, h, google)
    boring = b"From: a@b.com\nContent-Type: text/html\n\n<html><body><a href='https://x.com'>hello</a></body></html>"
    google.messages = {"m1": _eml(), "m2": boring}
    r = client.post("/api/gmail/sync", headers=h).json()
    assert r["messages_checked"] == 2 and r["messages_with_jobs"] == 1 and r["added"] + r["duplicates_merged"] == 2
    q = google.list_params[0]["q"]
    assert q.startswith("from:(") and "naukri.com" in q and "newer_than:" in q
    mine = {j["job"]["title"] for j in client.get("/api/jobs?include_filtered=true", headers=h).json()}
    theirs = {j["job"]["title"] for j in client.get("/api/jobs?include_filtered=true", headers=other).json()}
    assert {"Python Developer", "Data Analyst"} <= mine and not ({"Python Developer"} & theirs)
    again = client.post("/api/gmail/sync", headers=h).json()
    assert again["messages_checked"] == 0 and again["added"] == 0  # nothing re-processed
    google.messages["m3"] = _eml()
    third = client.post("/api/gmail/sync", headers=h).json()
    assert third["messages_checked"] == 1 and third["added"] == 0 and third["duplicates_merged"] == 2
    with db() as c:  # email bodies are never stored
        assert c.execute("SELECT COUNT(*) n FROM jobs WHERE description LIKE '%Unsubscribe%'").fetchone()["n"] == 0


def test_sync_requires_consent_and_connection(client, google):
    h = _user(client, "gm5@x.com")
    assert client.post("/api/gmail/sync", headers=h).status_code == 400  # not connected
    _connect(client, h, google)
    client.post("/api/consents", headers=h, json={"scope": "email_alerts", "granted": False})
    assert client.get("/api/gmail/status", headers=h).json()["connected"] is False  # withdrawing consent disconnects
    assert google.revoked == ["RT-secret-123"]
    assert client.post("/api/gmail/sync", headers=h).status_code == 403


def test_disconnect_revokes_and_deletes(client, google):
    h = _user(client, "gm6@x.com")
    _connect(client, h, google)
    google.messages = {"m1": _eml()}
    client.post("/api/gmail/sync", headers=h)
    r = client.post("/api/gmail/disconnect", headers=h).json()
    assert r["revoked_at_google"] is True and google.revoked[-1] == "RT-secret-123"
    with db() as c:
        assert c.execute("SELECT COUNT(*) n FROM gmail_accounts WHERE email='me@gmail.com' AND user_id=(SELECT id FROM users WHERE email='gm6@x.com')").fetchone()["n"] == 0
    actions = [a["action"] for a in client.get("/api/audit", headers=h).json()]
    assert "gmail_connected" in actions and "gmail_sync" in actions and "gmail_disconnected" in actions


def test_revoked_at_google_surfaces_clear_error_and_stops_auto(client, google):
    h = _user(client, "gm7@x.com")
    _connect(client, h, google)
    google.refresh_ok = False
    r = client.post("/api/gmail/sync", headers=h)
    assert r.status_code == 400 and "reconnect" in r.json()["detail"].lower()
    acc = client.get("/api/gmail/status", headers=h).json()["account"]
    assert acc["last_error"] and acc["auto_sync"] == 0


def test_auto_sync_skips_users_without_consent(client, google):
    h1, h2 = _user(client, "gm8@x.com"), _user(client, "gm9@x.com")
    for h in (h1, h2):
        _connect(client, h, google)
    google.messages = {"m1": _eml()}
    with db() as c:  # simulate a consent withdrawn without the disconnect hook (defence in depth)
        uid2 = c.execute("SELECT id FROM users WHERE email='gm9@x.com'").fetchone()["id"]
        c.execute("INSERT INTO consents(user_id,scope,granted) VALUES(?,?,0)", (uid2, "email_alerts"))
        c.execute("INSERT OR REPLACE INTO gmail_accounts(user_id,email,refresh_enc,auto_sync) VALUES(?,?,?,1)", (uid2, "x@y.z", gmail.encrypt("rt")))
    from app.main import _has_consent
    res = gmail.auto_sync_all(_has_consent)
    ids = {r["user_id"] for r in res}
    assert uid2 not in ids and len(ids) >= 1


def test_delete_account_removes_gmail_data(client, google):
    h = _user(client, "gm10@x.com")
    _connect(client, h, google)
    google.messages = {"m1": _eml()}
    client.post("/api/gmail/sync", headers=h)
    with db() as c:
        uid = c.execute("SELECT id FROM users WHERE email='gm10@x.com'").fetchone()["id"]
    assert client.delete("/api/account", headers=h).status_code == 200
    with db() as c:
        for t in ("gmail_accounts", "gmail_seen", "oauth_states"):
            assert c.execute(f"SELECT COUNT(*) n FROM {t} WHERE user_id=?", (uid,)).fetchone()["n"] == 0, t

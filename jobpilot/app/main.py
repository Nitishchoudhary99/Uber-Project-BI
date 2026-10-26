import json
import os
import re
from datetime import datetime, timedelta, timezone
from urllib.parse import quote

from fastapi import Depends, FastAPI, File, HTTPException, UploadFile
from fastapi.responses import FileResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

from . import analytics, auth, cvgen, db as dbm, matching, resume
from .db import audit, db, jload
from .ingest import ats, email_alerts, gmail, jsonld, net, sample
from .skills import extract_skills, pretty_map
from .store import upsert_jobs

import asyncio
from contextlib import asynccontextmanager


@asynccontextmanager
async def lifespan(_app):
    """Background auto-import. GMAIL_SYNC_MINUTES=0 turns it off."""
    minutes = int(os.environ.get("GMAIL_SYNC_MINUTES", "60"))
    task = None
    if minutes > 0 and gmail.configured():
        async def loop():
            while True:
                await asyncio.sleep(minutes * 60)
                await asyncio.to_thread(gmail.auto_sync_all, _has_consent)
        task = asyncio.create_task(loop())
    yield
    if task:
        task.cancel()


app = FastAPI(title="JobPilot", version="0.2.0", lifespan=lifespan)
dbm.init()
STATIC = os.path.join(os.path.dirname(__file__), "..", "static")
MAX_UPLOAD = 5_000_000

SCOPES = {
    "email_alerts": "Read job-alert emails you upload or forward (we never log in to Naukri/LinkedIn)",
    "public_feeds": "Fetch public job boards and company career pages",
    "autofill": "Let the browser extension read your profile to fill forms (you confirm every submit)",
}
STATUSES = ["prepared", "applied", "interview", "offer", "rejected", "withdrawn"]

DEFAULT_PROFILE = {
    "name": "", "email": "", "phone": "", "headline": "", "summary": "", "years_experience": 0, "skills": [],
    "experiences": [], "education": [], "projects": [], "locations": [], "remote_ok": True, "min_salary_lpa": None,
    "notice_days": None, "current_ctc_lpa": None, "expected_ctc_lpa": None, "target_titles": [], "excluded_companies": [],
    "links": {}, "work_authorization": "Indian citizen", "screening_answers": {},
}


# ---------- models ----------
class Creds(BaseModel):
    email: str
    password: str


class ConsentIn(BaseModel):
    scope: str
    granted: bool


class SourceIn(BaseModel):
    kind: str
    ref: str = Field(max_length=300)


class FeedbackIn(BaseModel):
    signal: str


class AppPatch(BaseModel):
    status: str | None = None
    notes: str | None = None
    follow_up: str | None = None


# ---------- helpers ----------
def _profile(uid: int) -> dict:
    with db() as c:
        row = c.execute("SELECT data FROM profiles WHERE user_id=?", (uid,)).fetchone()
    return {**DEFAULT_PROFILE, **(jload(row["data"], {}) if row else {})}


def _save_profile(uid: int, p: dict):
    p["skills"] = clean_skills(p.get("skills", []))
    with db() as c:
        c.execute("INSERT INTO profiles(user_id,data) VALUES(?,?) ON CONFLICT(user_id) DO UPDATE SET data=excluded.data, updated_at=CURRENT_TIMESTAMP",
                  (uid, json.dumps(p)))
        audit(c, uid, "profile_saved")


def clean_skills(skills) -> list[str]:
    out = []
    for s in skills or []:
        s = str(s).strip()
        if not s:
            continue
        found = extract_skills(s)
        out.append(found[0] if len(found) == 1 else s.lower())
    return list(dict.fromkeys(out))


def _has_consent(uid: int, scope: str) -> bool:
    with db() as c:
        row = c.execute("SELECT granted FROM consents WHERE user_id=? AND scope=? ORDER BY id DESC LIMIT 1", (uid, scope)).fetchone()
    return bool(row and row["granted"])


def _need(uid: int, scope: str):
    if not _has_consent(uid, scope):
        raise HTTPException(403, f"Consent needed: {SCOPES[scope]}. Grant it under Sources & Consent.")


def _visible_jobs(c, uid: int):
    return c.execute("SELECT * FROM jobs WHERE added_by IS NULL OR id IN (SELECT job_id FROM job_owners WHERE user_id=?)", (uid,)).fetchall()


def _job_dict(j) -> dict:
    d = dict(j)
    d["skills"] = jload(d["skills"], [])
    d["sources"] = jload(d["sources"], [])
    d["age_days"] = matching.age_days(d["posted_at"])
    d["fingerprint"] = None
    return d


def _feedback(c, uid: int) -> dict:
    return {r["job_id"]: r["signal"] for r in c.execute("SELECT job_id, signal FROM feedback WHERE user_id=?", (uid,))}


def _ranked(uid: int, include_filtered=False):
    p = _profile(uid)
    with db() as c:
        jobs = _visible_jobs(c, uid)
        fb = _feedback(c, uid)
    return p, matching.rank(jobs, p, include_filtered, fb)


def _match_view(r: dict) -> dict:
    return {"job": _job_dict(r["job"]), "score": r["score"], "matched": r["matched"], "missing": r["missing"], "flags": r["flags"],
            "reasons": r["reasons"], "breakdown": r["breakdown"], "filtered_out": r["filtered_out"], "feedback": r["feedback"]}


# ---------- auth ----------
@app.post("/api/signup")
def signup(c: Creds):
    return {"token": auth.signup(c.email, c.password)}


@app.post("/api/login")
def login(c: Creds):
    return {"token": auth.login(c.email, c.password)}


# ---------- profile ----------
@app.get("/api/profile")
def get_profile(uid: int = Depends(auth.current_user)):
    return _profile(uid)


@app.put("/api/profile")
def put_profile(body: dict, uid: int = Depends(auth.current_user)):
    p = {**DEFAULT_PROFILE, **{k: v for k, v in body.items() if k in DEFAULT_PROFILE}}
    _save_profile(uid, p)
    return _profile(uid)


@app.post("/api/profile/upload")
async def upload_cv(file: UploadFile = File(...), uid: int = Depends(auth.current_user)):
    data = await file.read(MAX_UPLOAD + 1)
    if len(data) > MAX_UPLOAD:
        raise HTTPException(413, "File too large (5 MB max)")
    try:
        text = resume.extract_text(file.filename or "cv.txt", data)
    except Exception as e:
        raise HTTPException(422, f"Could not read this file: {e}")
    if len(text.strip()) < 40:
        raise HTTPException(422, "No readable text found. If this is a scanned PDF, upload a text-based PDF or DOCX.")
    parsed = resume.parse_resume(text)
    cur = _profile(uid)
    merged = {**cur, **{k: v for k, v in parsed.items() if v not in ("", [], 0, None)}}
    _save_profile(uid, merged)
    return {"profile": _profile(uid), "note": "Parsed automatically - please review every section before you rely on it."}


# ---------- consent / privacy ----------
@app.get("/api/consents")
def consents(uid: int = Depends(auth.current_user)):
    return [{"scope": s, "description": d, "granted": _has_consent(uid, s)} for s, d in SCOPES.items()]


@app.post("/api/consents")
def set_consent(body: ConsentIn, uid: int = Depends(auth.current_user)):
    if body.scope not in SCOPES:
        raise HTTPException(400, "Unknown scope")
    with db() as c:
        c.execute("INSERT INTO consents(user_id,scope,granted) VALUES(?,?,?)", (uid, body.scope, int(body.granted)))
        audit(c, uid, "consent_granted" if body.granted else "consent_revoked", body.scope)
    if body.scope == "email_alerts" and not body.granted:
        gmail.disconnect(uid)  # withdrawing consent must also drop the live Gmail connection
    return consents(uid)


@app.get("/api/audit")
def get_audit(uid: int = Depends(auth.current_user)):
    with db() as c:
        return [dict(r) for r in c.execute("SELECT action, detail, at FROM audit WHERE user_id=? ORDER BY id DESC LIMIT 100", (uid,))]


@app.get("/api/export")
def export(uid: int = Depends(auth.current_user)):
    with db() as c:
        return {
            "profile": _profile(uid),
            "consents": [dict(r) for r in c.execute("SELECT scope, granted, at FROM consents WHERE user_id=?", (uid,))],
            "applications": [dict(r) for r in c.execute("SELECT job_id, status, method, notes, score, created_at FROM applications WHERE user_id=?", (uid,))],
            "feedback": [dict(r) for r in c.execute("SELECT job_id, signal, at FROM feedback WHERE user_id=?", (uid,))],
            "audit": [dict(r) for r in c.execute("SELECT action, detail, at FROM audit WHERE user_id=?", (uid,))],
        }


@app.delete("/api/account")
def delete_account(uid: int = Depends(auth.current_user)):
    try:
        gmail.disconnect(uid)  # revoke at Google and delete the stored token
    except Exception:
        pass
    with db() as c:
        for t in ("applications", "feedback", "consents", "profiles", "sessions", "watch_sources", "gmail_accounts", "gmail_seen", "oauth_states"):
            c.execute(f"DELETE FROM {t} WHERE user_id=?", (uid,))
        c.execute("DELETE FROM job_owners WHERE user_id=?", (uid,))
        c.execute("DELETE FROM jobs WHERE added_by IS NOT NULL AND id NOT IN (SELECT job_id FROM job_owners)")
        c.execute("DELETE FROM audit WHERE user_id=?", (uid,))
        c.execute("DELETE FROM users WHERE id=?", (uid,))
    return {"deleted": True}


# ---------- data capture ----------
def _watch(c, uid, kind, ref, count):
    c.execute("""INSERT INTO watch_sources(user_id,kind,ref,last_run,last_count) VALUES(?,?,?,CURRENT_TIMESTAMP,?)
                 ON CONFLICT(user_id,kind,ref) DO UPDATE SET last_run=CURRENT_TIMESTAMP, last_count=excluded.last_count""", (uid, kind, ref, count))


def _run_source(uid: int, kind: str, ref: str) -> dict:
    try:
        if kind in ats.CONNECTORS:
            raws, source = ats.CONNECTORS[kind](ref), f"{kind}"
        elif kind == "career_page":
            raws, source = jsonld.fetch(ref), "career_page"
        else:
            raise HTTPException(400, "Unknown source kind")
    except net.FetchError as e:
        raise HTTPException(400, str(e))
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(502, f"Could not fetch that source: {e}")
    res = upsert_jobs(raws, source)
    with db() as c:
        _watch(c, uid, kind, ref, len(raws))
        audit(c, uid, "ingest", f"{kind}:{ref} -> {res}")
    return res


@app.post("/api/ingest/sample")
def ingest_sample(uid: int = Depends(auth.current_user)):
    a = upsert_jobs(sample.jobs(), "sample")
    b = upsert_jobs(sample.duplicate_variants(), "sample_portal_b")
    with db() as c:
        audit(c, uid, "ingest", "sample data")
    return {"added": a["added"] + b["added"], "duplicates_merged": a["duplicates_merged"] + b["duplicates_merged"], "total_seen": a["total_seen"] + b["total_seen"]}


@app.post("/api/ingest/source")
def ingest_source(body: SourceIn, uid: int = Depends(auth.current_user)):
    _need(uid, "public_feeds")
    return _run_source(uid, body.kind, body.ref.strip())


@app.post("/api/ingest/email")
async def ingest_email(file: UploadFile = File(...), uid: int = Depends(auth.current_user)):
    _need(uid, "email_alerts")
    data = await file.read(MAX_UPLOAD + 1)
    if len(data) > MAX_UPLOAD:
        raise HTTPException(413, "File too large")
    jobs = email_alerts.parse_eml(data)
    if not jobs:
        raise HTTPException(422, "No job links recognised. Upload an alert email (.eml) from Naukri, LinkedIn, Indeed, Foundit...")
    res = upsert_jobs(jobs, "email", user_id=uid, private=True)
    with db() as c:
        audit(c, uid, "ingest", f"email {file.filename}: {res}")
    return {**res, "note": "Alert emails list titles only; open the job link for the full description. Skills come from the title."}


@app.post("/api/ingest/refresh")
def refresh(uid: int = Depends(auth.current_user)):
    _need(uid, "public_feeds")
    with db() as c:
        srcs = [dict(r) for r in c.execute("SELECT kind, ref FROM watch_sources WHERE user_id=?", (uid,))]
    results = []
    for s in srcs:
        try:
            results.append({**s, **_run_source(uid, s["kind"], s["ref"])})
        except HTTPException as e:
            results.append({**s, "error": e.detail})
    return results


@app.get("/api/sources")
def sources(uid: int = Depends(auth.current_user)):
    with db() as c:
        return [dict(r) for r in c.execute("SELECT kind, ref, last_run, last_count FROM watch_sources WHERE user_id=?", (uid,))]


# ---------- jobs ----------
@app.get("/api/jobs")
def list_jobs(min_score: int = 0, include_filtered: bool = False, q: str = "", limit: int = 100, uid: int = Depends(auth.current_user)):
    _, ranked = _ranked(uid, include_filtered)
    out = [_match_view(r) for r in ranked if r["score"] >= min_score]
    if q:
        ql = q.lower()
        out = [r for r in out if ql in (r["job"]["title"] + " " + r["job"]["company"] + " " + r["job"]["location"]).lower()]
    return out[:max(1, min(limit, 300))]


def _job_or_404(c, uid, job_id):
    j = c.execute("SELECT * FROM jobs WHERE id=? AND (added_by IS NULL OR id IN (SELECT job_id FROM job_owners WHERE user_id=?))", (job_id, uid)).fetchone()
    if not j:
        raise HTTPException(404, "Job not found")
    return j


@app.get("/api/jobs/{job_id}")
def job_detail(job_id: int, uid: int = Depends(auth.current_user)):
    p = _profile(uid)
    with db() as c:
        j = _job_or_404(c, uid, job_id)
        jobs = _visible_jobs(c, uid)
        fb = _feedback(c, uid)
        app_row = c.execute("SELECT * FROM applications WHERE user_id=? AND job_id=?", (uid, job_id)).fetchone()
    r = next((x for x in matching.rank(jobs, p, True, fb) if x["job"]["id"] == job_id), None)
    return {**_match_view(r), "application": dict(app_row) if app_row else None}


@app.post("/api/jobs/{job_id}/cv")
def make_cv(job_id: int, uid: int = Depends(auth.current_user)):
    p = _profile(uid)
    if not p["experiences"] and not p["skills"]:
        raise HTTPException(422, "Add your experience or skills to your profile first")
    with db() as c:
        j = _job_or_404(c, uid, job_id)
        audit(c, uid, "cv_generated", f"job {job_id}")
    res = cvgen.tailor(p, j)
    if not res["verified"]:
        raise HTTPException(500, "CV failed the no-fabrication check: " + "; ".join(res["problems"][:3]))
    return res


@app.post("/api/jobs/{job_id}/feedback")
def feedback(job_id: int, body: FeedbackIn, uid: int = Depends(auth.current_user)):
    with db() as c:
        _job_or_404(c, uid, job_id)
        if body.signal == "clear":
            c.execute("DELETE FROM feedback WHERE user_id=? AND job_id=?", (uid, job_id))
        elif body.signal in ("save", "skip"):
            c.execute("INSERT INTO feedback(user_id,job_id,signal) VALUES(?,?,?) ON CONFLICT(user_id,job_id) DO UPDATE SET signal=excluded.signal, at=CURRENT_TIMESTAMP",
                      (uid, job_id, body.signal))
        else:
            raise HTTPException(400, "signal must be save, skip or clear")
    return {"ok": True}


@app.post("/api/jobs/{job_id}/apply")
def prepare_apply(job_id: int, uid: int = Depends(auth.current_user)):
    """Builds the application pack. Nothing is submitted on the employer's site: the user clicks submit there."""
    p = _profile(uid)
    with db() as c:
        j = _job_or_404(c, uid, job_id)
    cv = cvgen.tailor(p, j)
    method = "email" if j["apply_email"] else "link"
    _, ranked = _ranked(uid, True)
    score = next((r["score"] for r in ranked if r["job"]["id"] == job_id), None)
    with db() as c:
        c.execute("""INSERT INTO applications(user_id,job_id,status,method,score,cv_html) VALUES(?,?,?,?,?,?)
                     ON CONFLICT(user_id,job_id) DO UPDATE SET cv_html=excluded.cv_html, updated_at=CURRENT_TIMESTAMP""",
                  (uid, job_id, "prepared", method, score, cv["html"]))
        audit(c, uid, "apply_prepared", f"job {job_id} via {method}")
    out = {"method": method, "apply_url": j["url"], "cv_html": cv["html"], "cv_text": cv["text"], "diff": cv["diff"]}
    if j["apply_email"]:
        subject = f"Application: {j['title']} - {p['name']}"
        body = f"Hello,\n\nI would like to apply for the {j['title']} role at {j['company']}. My CV is attached.\n\nRegards,\n{p['name']}\n{p['phone']}"
        out["mailto"] = f"mailto:{j['apply_email']}?subject={quote(subject)}&body={quote(body)}"
    out["next_step"] = "Open the apply page (extension fills the form), submit it yourself, then mark the application as Applied."
    return out


# ---------- applications ----------
@app.get("/api/applications")
def applications(uid: int = Depends(auth.current_user)):
    with db() as c:
        rows = c.execute("""SELECT a.id, a.job_id, a.status, a.method, a.notes, a.score, a.created_at, a.updated_at, a.follow_up,
                            j.title, j.company, j.location, j.url FROM applications a JOIN jobs j ON j.id=a.job_id
                            WHERE a.user_id=? ORDER BY a.updated_at DESC""", (uid,)).fetchall()
    return [dict(r) for r in rows]


@app.patch("/api/applications/{app_id}")
def patch_application(app_id: int, body: AppPatch, uid: int = Depends(auth.current_user)):
    if body.status and body.status not in STATUSES:
        raise HTTPException(400, f"status must be one of {STATUSES}")
    with db() as c:
        row = c.execute("SELECT * FROM applications WHERE id=? AND user_id=?", (app_id, uid)).fetchone()
        if not row:
            raise HTTPException(404, "Not found")
        follow = body.follow_up
        if body.status == "applied" and follow is None and not row["follow_up"]:
            follow = (datetime.now(timezone.utc) + timedelta(days=7)).date().isoformat()
        c.execute("UPDATE applications SET status=COALESCE(?,status), notes=COALESCE(?,notes), follow_up=COALESCE(?,follow_up), updated_at=CURRENT_TIMESTAMP WHERE id=?",
                  (body.status, body.notes, follow, app_id))
        audit(c, uid, "application_updated", f"{app_id} -> {body.status}")
    return {"ok": True}


# ---------- insights ----------
@app.get("/api/analytics")
def get_analytics(uid: int = Depends(auth.current_user)):
    p, ranked = _ranked(uid, True)
    with db() as c:
        jobs = _visible_jobs(c, uid)
        apps = c.execute("SELECT status FROM applications WHERE user_id=?", (uid,)).fetchall()
    visible = [r for r in ranked if not r["filtered_out"]]
    return {"market": analytics.market(jobs, matching.profile_skills(p)), "personal": analytics.personal(visible, apps, matching.profile_skills(p)),
            "filtered_out_count": len(ranked) - len(visible)}


@app.get("/api/digest")
def digest(uid: int = Depends(auth.current_user)):
    p, ranked = _ranked(uid)
    today = datetime.now(timezone.utc).date().isoformat()
    with db() as c:
        due = c.execute("""SELECT a.id, j.title, j.company, a.follow_up FROM applications a JOIN jobs j ON j.id=a.job_id
                           WHERE a.user_id=? AND a.status='applied' AND a.follow_up<=?""", (uid, today)).fetchall()
        stale_saved = c.execute("""SELECT j.title, j.company FROM feedback f JOIN jobs j ON j.id=f.job_id
                                   WHERE f.user_id=? AND f.signal='save' AND f.job_id NOT IN (SELECT job_id FROM applications WHERE user_id=?)""", (uid, uid)).fetchall()
    top = [_match_view(r) for r in ranked if r["score"] >= 50][:5]
    return {"top_matches": top, "follow_ups_due": [dict(r) for r in due], "saved_not_applied": [dict(r) for r in stale_saved][:5],
            "needs_profile": not (p["skills"] or p["experiences"])}


# ---------- extension ----------
@app.post("/api/extension-token")
def extension_token(uid: int = Depends(auth.current_user)):
    """A separate token the browser extension can use. It can only read the autofill profile, nothing else."""
    _need(uid, "autofill")
    with db() as c:
        audit(c, uid, "extension_token_issued")
    return {"token": auth.new_scoped_token(uid, "autofill")}


@app.get("/api/autofill")
def autofill(uid: int = Depends(auth.autofill_user)):
    _need(uid, "autofill")
    p = _profile(uid)
    cur = p["experiences"][0] if p["experiences"] else {}
    first, _, last = p["name"].partition(" ")
    with db() as c:
        audit(c, uid, "autofill_profile_read")
    return {
        "fields": {
            "full_name": p["name"], "first_name": first, "last_name": last.strip(), "email": p["email"], "phone": p["phone"],
            "headline": p["headline"], "current_title": cur.get("title", ""), "current_company": cur.get("company", ""),
            "years_experience": str(p["years_experience"] or ""), "linkedin": p["links"].get("linkedin", ""), "github": p["links"].get("github", ""),
            "portfolio": p["links"].get("portfolio", ""), "location": (p["locations"] or [""])[0], "notice_days": str(p["notice_days"] or ""),
            "work_authorization": p["work_authorization"], "summary": p["summary"], "skills": ", ".join(p["skills"]),
        },
        "ask_user": {"current_ctc_lpa": p["current_ctc_lpa"], "expected_ctc_lpa": p["expected_ctc_lpa"]},
        "screening_answers": p["screening_answers"],
    }


@app.get("/api/meta")
def meta():
    return {"statuses": STATUSES, "scopes": SCOPES, "connectors": list(ats.CONNECTORS) + ["career_page"], "pretty": pretty_map()}


# ---------- Gmail auto-import ----------
class AutoIn(BaseModel):
    enabled: bool


def _gmail_call(fn, *a):
    try:
        return fn(*a)
    except gmail.GmailError as e:
        raise HTTPException(400, str(e))


@app.get("/api/gmail/status")
def gmail_status(uid: int = Depends(auth.current_user)):
    with db() as c:
        row = c.execute("SELECT email, connected_at, last_sync, last_added, last_error, auto_sync FROM gmail_accounts WHERE user_id=?", (uid,)).fetchone()
    return {"configured": gmail.configured(), "consent": _has_consent(uid, "email_alerts"), "connected": bool(row), "account": dict(row) if row else None,
            "reads_only_from": gmail.ALERT_SENDERS, "query": gmail.ALERT_QUERY.format(days=14), "redirect_uri": gmail.config()["redirect_uri"]}


@app.post("/api/gmail/connect")
def gmail_connect(uid: int = Depends(auth.current_user)):
    _need(uid, "email_alerts")
    return {"url": _gmail_call(gmail.start_auth, uid)}


@app.get("/gmail/callback")
def gmail_callback(code: str = "", state: str = "", error: str = ""):
    if error or not code or not state:
        return RedirectResponse("/gmail?error=" + quote("Gmail connection was cancelled."))
    try:
        gmail.finish_auth(state, code)
    except gmail.GmailError as e:
        return RedirectResponse("/gmail?error=" + quote(str(e)))
    return RedirectResponse("/gmail?connected=1")


@app.post("/api/gmail/sync")
def gmail_sync(uid: int = Depends(auth.current_user)):
    _need(uid, "email_alerts")
    return _gmail_call(gmail.sync, uid)


@app.post("/api/gmail/auto")
def gmail_auto(body: AutoIn, uid: int = Depends(auth.current_user)):
    with db() as c:
        c.execute("UPDATE gmail_accounts SET auto_sync=? WHERE user_id=?", (int(body.enabled), uid))
        audit(c, uid, "gmail_auto_sync", str(body.enabled))
    return {"auto_sync": body.enabled}


@app.post("/api/gmail/disconnect")
def gmail_disconnect(uid: int = Depends(auth.current_user)):
    return {"disconnected": True, "revoked_at_google": gmail.disconnect(uid)}


@app.get("/gmail")
def gmail_page():
    return FileResponse(os.path.join(STATIC, "gmail.html"))


@app.get("/")
def index():
    return FileResponse(os.path.join(STATIC, "index.html"))


app.mount("/static", StaticFiles(directory=STATIC), name="static")

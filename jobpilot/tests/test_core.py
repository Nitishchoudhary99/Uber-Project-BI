import copy
import io
import json

from app import cvgen, matching, resume
from app.db import db
from app.ingest import email_alerts, jsonld, net
from app.normalize import fingerprint
from app.skills import extract_skills
from conftest import CV, make_user


def test_skills_no_false_positives():
    assert extract_skills("We react quickly to the rest of the spring season") == []
    assert "go" not in extract_skills("Let us go and see")
    assert {"c++", "c#", "node.js", "react"} <= set(extract_skills("C++, C#, Node.js and React.js"))


def test_fingerprint_collapses_portal_variants():
    a = fingerprint("Senior Data Analyst", "Quanta Health", "Hyderabad, Telangana")
    b = fingerprint("Data Analyst", "Quanta Health Pvt Ltd", "Hyderabad")
    assert a == b
    assert a != fingerprint("Data Analyst", "Quanta Health", "Mumbai")


def test_resume_parse():
    p = resume.parse_resume(CV)
    assert p["name"] == "Rahul Sharma" and p["email"] == "rahul@mail.com"
    assert len(p["experiences"]) == 2 and p["experiences"][0]["company"] == "Acme Tech"
    assert {"react", "typescript", "git"} <= set(p["skills"])
    assert p["years_experience"] > 4


def _setup(client, email):
    h = make_user(client, email)
    r = client.post("/api/profile/upload", headers=h, files={"file": ("cv.txt", CV.encode())})
    assert r.status_code == 200
    client.put("/api/profile", headers=h, json={**client.get("/api/profile", headers=h).json(), "locations": ["Gurugram", "Delhi"], "remote_ok": True})
    client.post("/api/ingest/sample", headers=h)
    return h


def test_end_to_end_and_dedupe(client):
    h = _setup(client, "a@x.com")
    with db() as c:
        n = c.execute("SELECT COUNT(*) n FROM jobs").fetchone()["n"]
    assert n == 29  # 29 originals; the 8 re-posted variants merged, not duplicated
    jobs = client.get("/api/jobs", headers=h).json()
    assert jobs and jobs[0]["score"] >= jobs[-1]["score"]
    top = jobs[0]
    assert "react" in top["matched"] or "typescript" in top["matched"]
    merged = [j for j in jobs if len(j["job"]["sources"]) > 1]
    assert merged, "cross-portal duplicates should carry both sources"


def test_hard_filters_hide_and_explain(client):
    h = _setup(client, "b@x.com")
    visible = {j["job"]["title"] for j in client.get("/api/jobs", headers=h).json()}
    assert "Engineering Manager" not in visible  # needs 8+ yrs
    allj = client.get("/api/jobs?include_filtered=true", headers=h).json()
    em = next(j for j in allj if j["job"]["title"] == "Engineering Manager")
    assert em["filtered_out"]


def test_cv_never_fabricates(client):
    h = _setup(client, "c@x.com")
    jobs = client.get("/api/jobs", headers=h).json()
    jid = jobs[0]["job"]["id"]
    res = client.post(f"/api/jobs/{jid}/cv", headers=h).json()
    assert res["verified"] and res["problems"] == []
    prof = client.get("/api/profile", headers=h).json()
    out_skills = set(res["cv"]["skills"])
    assert out_skills <= set(prof["skills"])
    # the irrelevant bullet is dropped for a frontend role, kept in the profile
    all_bullets = [b for r in res["cv"]["experiences"] for b in r["bullets"]]
    assert all(len(r["bullets"]) <= cvgen.BULLETS_PER_ROLE for r in res["cv"]["experiences"])
    assert any("cricket" in b for e in prof["experiences"] for b in e["bullets"])
    assert not any("cricket" in b for b in all_bullets)


def test_verifier_catches_tampering():
    prof = {**__import__("app.main", fromlist=["DEFAULT_PROFILE"]).DEFAULT_PROFILE, **resume.parse_resume(CV)}
    job = {"title": "Frontend Dev", "company": "Zeta", "description": "React", "skills": json.dumps(["react"])}
    cv = cvgen.tailor(prof, job)["cv"]
    bad = copy.deepcopy(cv)
    bad["skills"].append("kubernetes")
    bad["experiences"][0]["bullets"].append("Led a team of 50 engineers")
    problems = cvgen.verify_no_fabrication(bad, prof)
    assert any("kubernetes" in p for p in problems) and any("Led a team" in p for p in problems)


def test_consent_gates_and_audit(client):
    h = make_user(client, "d@x.com")
    r = client.post("/api/ingest/source", headers=h, json={"kind": "greenhouse", "ref": "acme"})
    assert r.status_code == 403
    assert client.get("/api/autofill", headers=h).status_code == 403
    client.post("/api/consents", headers=h, json={"scope": "autofill", "granted": True})
    assert client.get("/api/autofill", headers=h).status_code == 200
    client.post("/api/consents", headers=h, json={"scope": "autofill", "granted": False})
    assert client.get("/api/autofill", headers=h).status_code == 403
    actions = [a["action"] for a in client.get("/api/audit", headers=h).json()]
    assert "consent_revoked" in actions and "autofill_profile_read" in actions


def test_requires_login(client):
    assert client.get("/api/jobs").status_code == 401
    assert client.get("/api/jobs", headers={"Authorization": "Bearer nope"}).status_code == 401


def test_private_email_jobs_not_visible_to_others(client):
    h1, h2 = make_user(client, "e1@x.com"), make_user(client, "e2@x.com")
    client.post("/api/consents", headers=h1, json={"scope": "email_alerts", "granted": True})
    eml = _eml()
    r = client.post("/api/ingest/email", headers=h1, files={"file": ("a.eml", eml)})
    assert r.status_code == 200 and r.json()["added"] >= 2
    t1 = {j["job"]["title"] for j in client.get("/api/jobs?include_filtered=true", headers=h1).json()}
    t2 = {j["job"]["title"] for j in client.get("/api/jobs?include_filtered=true", headers=h2).json()}
    assert "Python Developer" in t1 and "Python Developer" not in t2


def _eml() -> bytes:
    html = """<html><body><table>
    <tr><td><a href="https://www.naukri.com/job-listings-python-developer-acme-pune-2-to-5-years-123?src=x">Python Developer</a>
    <div>Acme Software</div><div>Pune</div><div>2-5 Yrs</div><div>8-12 LPA</div></td></tr>
    <tr><td><a href="https://www.naukri.com/job-listings-data-analyst-zeta-delhi-1-to-3-years-456?src=x">Data Analyst</a>
    <div>Zeta Analytics</div><div>Delhi</div><div>1-3 Yrs</div></td></tr>
    <tr><td><a href="https://www.naukri.com/unsubscribe">Unsubscribe</a></td></tr></table></body></html>"""
    return (f"From: Naukri Jobs <jobs@naukri.com>\nTo: me@x.com\nSubject: Jobs for you\nMIME-Version: 1.0\n"
            f"Content-Type: text/html; charset=utf-8\n\n{html}").encode()


def test_email_parser_extracts_cards():
    jobs = email_alerts.parse_eml(_eml())
    assert len(jobs) == 2
    j = jobs[0]
    assert j["title"] == "Python Developer" and j["company"] == "Acme Software" and j["location"] == "Pune"
    assert j["_source"] == "email:naukri" and "?" not in j["url"]


def test_jsonld_parser():
    html = """<script type="application/ld+json">{"@context":"https://schema.org","@type":"JobPosting","title":"QA Engineer",
    "hiringOrganization":{"name":"Zeta"},"jobLocation":{"address":{"addressLocality":"Pune","addressRegion":"MH"}},
    "description":"<p>Selenium</p>","baseSalary":{"currency":"INR","value":{"minValue":800000,"maxValue":1200000,"unitText":"YEAR"}}}</script>"""
    j = jsonld.parse_html(html, "https://z.com/j/1")[0]
    assert j["company"] == "Zeta" and j["location"] == "Pune, MH" and (j["salary_min"], j["salary_max"]) == (8.0, 12.0)


def test_ssrf_blocked():
    for url in ("http://127.0.0.1:8000/", "http://169.254.169.254/latest/meta-data/", "file:///etc/passwd", "http://localhost/"):
        try:
            net.get(url, robots=False)
            assert False, url
        except net.FetchError:
            pass


def test_delete_account_erases_everything(client):
    h = _setup(client, "f@x.com")
    client.post("/api/consents", headers=h, json={"scope": "email_alerts", "granted": True})
    client.post("/api/ingest/email", headers=h, files={"file": ("a.eml", _eml())})
    with db() as c:
        uid = c.execute("SELECT id FROM users WHERE email='f@x.com'").fetchone()["id"]
        assert c.execute("SELECT COUNT(*) n FROM job_owners WHERE user_id=?", (uid,)).fetchone()["n"] == 2
    assert client.delete("/api/account", headers=h).status_code == 200
    assert client.get("/api/profile", headers=h).status_code == 401
    with db() as c:
        assert c.execute("SELECT COUNT(*) n FROM users WHERE email='f@x.com'").fetchone()["n"] == 0
        for t in ("profiles", "consents", "audit", "job_owners", "sessions"):
            assert c.execute(f"SELECT COUNT(*) n FROM {t} WHERE user_id=?", (uid,)).fetchone()["n"] == 0, t


def test_two_users_same_private_job_each_see_it_and_delete_is_scoped(client):
    h1, h2 = make_user(client, "g1@x.com"), make_user(client, "g2@x.com")
    for h in (h1, h2):
        client.post("/api/consents", headers=h, json={"scope": "email_alerts", "granted": True})
        assert client.post("/api/ingest/email", headers=h, files={"file": ("a.eml", _eml())}).status_code == 200
    for h in (h1, h2):
        assert "Data Analyst" in {j["job"]["title"] for j in client.get("/api/jobs?include_filtered=true", headers=h).json() if j["job"]["company"] == "Zeta Analytics"}
    client.delete("/api/account", headers=h1)
    assert any(j["job"]["company"] == "Zeta Analytics" for j in client.get("/api/jobs?include_filtered=true", headers=h2).json())


def test_extension_token_is_limited(client):
    h = make_user(client, "ext@x.com")
    assert client.post("/api/extension-token", headers=h).status_code == 403  # needs consent first
    client.post("/api/consents", headers=h, json={"scope": "autofill", "granted": True})
    tok = client.post("/api/extension-token", headers=h).json()["token"]
    eh = {"Authorization": "Bearer " + tok}
    assert client.get("/api/autofill", headers=eh).status_code == 200
    for path in ("/api/profile", "/api/jobs", "/api/export", "/api/applications"):
        assert client.get(path, headers=eh).status_code == 401, path
    assert client.delete("/api/account", headers=eh).status_code == 401
    client.post("/api/consents", headers=h, json={"scope": "autofill", "granted": False})
    assert client.get("/api/autofill", headers=eh).status_code == 403  # revoking consent kills extension access immediately


class _Resp:
    def __init__(self, data):
        self._d = data

    def json(self):
        return self._d


def test_ats_connectors_map_documented_shapes(monkeypatch):
    """Shapes follow the public Greenhouse / Lever / Ashby docs. NOT yet verified against the live services."""
    from app.ingest import ats
    from app.normalize import normalize
    gh = {"meta": {"name": "Acme"}, "jobs": [{"title": "Backend Engineer", "location": {"name": "Bengaluru"}, "absolute_url": "https://x/1",
          "content": "&lt;p&gt;Python, FastAPI and PostgreSQL. 3-6 years.&lt;/p&gt;", "updated_at": "2026-10-01T10:00:00Z"}]}
    lv = [{"text": "SRE", "categories": {"location": "Remote"}, "workplaceType": "remote", "descriptionPlain": "Kubernetes, Terraform",
           "lists": [{"text": "Requirements", "content": "AWS"}], "hostedUrl": "https://l/1", "createdAt": 1760000000000}]
    ab = {"jobs": [{"title": "Data Analyst", "location": "Pune", "isRemote": False, "descriptionHtml": "<p>SQL, Power BI</p>", "jobUrl": "https://a/1", "publishedAt": "2026-10-02"}]}
    for fn, payload, arg in ((ats.greenhouse, gh, "acme"), (ats.lever, lv, "acme"), (ats.ashby, ab, "acme")):
        monkeypatch.setattr(ats.net, "get", lambda url, robots=True, _p=payload: _Resp(_p))
        raws = fn(arg)
        job = normalize(raws[0], fn.__name__)
        assert job and job["title"] and job["skills"], fn.__name__
    monkeypatch.setattr(ats.net, "get", lambda url, robots=True: _Resp(gh))
    j = normalize(ats.greenhouse("acme")[0], "greenhouse")
    assert {"python", "fastapi", "postgresql"} <= set(j["skills"]) and j["exp_min"] == 3.0
    for bad in ("../etc/passwd", "a b", "x" * 100, ""):
        try:
            ats.greenhouse(bad)
            assert False, bad
        except net.FetchError:
            pass

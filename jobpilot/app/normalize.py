"""Turn raw job records from any source into one shape, and fingerprint them for dedupe."""
import hashlib
import html
import re
from datetime import datetime, timezone

from bs4 import BeautifulSoup

from .skills import extract_skills

_COMPANY_NOISE = re.compile(r"\b(pvt|private|ltd|limited|inc|llc|llp|corp|corporation|technologies|technology|solutions|india)\b\.?", re.I)
_TITLE_NOISE = re.compile(r"\b(sr|senior|jr|junior)\b\.?|\(.*?\)|[-–|].*$", re.I)


def clean_html(raw: str) -> str:
    if not raw:
        return ""
    soup = BeautifulSoup(html.unescape(raw), "lxml")
    for br in soup.find_all(["br", "li", "p", "div"]):
        br.append("\n")
    return re.sub(r"\n{3,}", "\n\n", re.sub(r"[ \t]+", " ", soup.get_text())).strip()


def _norm(s: str, pat=None) -> str:
    s = (s or "").lower()
    if pat:
        s = pat.sub(" ", s)
    return re.sub(r"[^a-z0-9]+", " ", s).strip()


def city_of(location: str) -> str:
    return _norm((location or "").split(",")[0])


def fingerprint(title: str, company: str, location: str) -> str:
    key = "|".join([_norm(title, _TITLE_NOISE), _norm(company, _COMPANY_NOISE), city_of(location)])
    return hashlib.sha1(key.encode()).hexdigest()


_LPA = re.compile(r"(?:₹|rs\.?|inr)?\s*(\d{1,3}(?:\.\d+)?)\s*(?:-|to|–)\s*(\d{1,3}(?:\.\d+)?)\s*(?:lpa|lakhs?|l\b)", re.I)
_EXP = re.compile(r"(\d{1,2})\s*(?:\+|-|to|–)?\s*(\d{1,2})?\s*(?:\+)?\s*(?:years|yrs|year)", re.I)


def parse_salary(text: str):
    m = _LPA.search(text or "")
    return (float(m.group(1)), float(m.group(2))) if m else (None, None)


def parse_experience(text: str):
    m = _EXP.search(text or "")
    if not m:
        return None, None
    lo = float(m.group(1))
    hi = float(m.group(2)) if m.group(2) else None
    return lo, hi


def normalize(raw: dict, source: str) -> dict | None:
    title = (raw.get("title") or "").strip()
    company = (raw.get("company") or "").strip()
    if not title or not company:
        return None
    desc = clean_html(raw.get("description") or "")
    location = (raw.get("location") or "").strip()
    remote = bool(raw.get("remote")) or "remote" in location.lower() or "work from home" in desc.lower()[:400]
    smin, smax = raw.get("salary_min"), raw.get("salary_max")
    if smin is None:
        smin, smax = parse_salary(f"{title} {desc}")
    emin, emax = raw.get("exp_min"), raw.get("exp_max")
    if emin is None:
        emin, emax = parse_experience(f"{raw.get('experience') or ''} {desc[:1500]}")
    return {
        "title": title, "company": company, "location": location, "remote": int(remote),
        "description": desc, "skills": extract_skills(f"{title}\n{desc}"),
        "url": raw.get("url"), "apply_email": raw.get("apply_email"),
        "salary_min": smin, "salary_max": smax, "exp_min": emin, "exp_max": emax,
        "posted_at": raw.get("posted_at") or datetime.now(timezone.utc).isoformat(),
        "source": source,
    }

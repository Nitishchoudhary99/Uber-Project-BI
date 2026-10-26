"""Job <-> profile matching: hard filters first, then an explainable score."""
import math
import re
from collections import Counter
from datetime import datetime, timezone

from .db import jload
from .normalize import city_of
from .skills import SKILLS, extract_skills

W_SKILLS, W_TEXT, W_TITLE, W_EXP = 55, 20, 15, 10
STALE_DAYS = 45

_TOKEN = re.compile(r"[a-z][a-z0-9+#.]{1,}")
_STOP = set("the and for with you our are will this that have has from your their who all can not but its into more also per etc job role team work years year experience".split())


def tokens(text: str) -> list[str]:
    return [t for t in _TOKEN.findall((text or "").lower()) if t not in _STOP]


def build_idf(job_rows) -> dict[str, float]:
    df: Counter = Counter()
    n = 0
    for r in job_rows:
        n += 1
        df.update(set(tokens(f"{r['title']} {r['description']}")))
    return {t: math.log((1 + n) / (1 + c)) + 1 for t, c in df.items()}


def _tfidf(toks: list[str], idf: dict[str, float]) -> dict[str, float]:
    tf = Counter(toks)
    return {t: (1 + math.log(c)) * idf.get(t, 1.0) for t, c in tf.items()}


def _cos(a: dict, b: dict) -> float:
    if not a or not b:
        return 0.0
    dot = sum(v * b.get(k, 0) for k, v in a.items())
    na = math.sqrt(sum(v * v for v in a.values()))
    nb = math.sqrt(sum(v * v for v in b.values()))
    return dot / (na * nb) if na and nb else 0.0


def profile_text(p: dict) -> str:
    parts = [p.get("headline", ""), p.get("summary", ""), " ".join(p.get("skills", [])), " ".join(p.get("target_titles", []))]
    for e in p.get("experiences", []):
        parts += [e.get("title", ""), *e.get("bullets", [])]
    for pr in p.get("projects", []):
        parts += [pr.get("name", ""), *pr.get("bullets", [])]
    return " ".join(parts)


def profile_skills(p: dict) -> set[str]:
    declared = set(p.get("skills", []))
    return declared | set(extract_skills(profile_text(p)))


def age_days(posted_at: str | None) -> int | None:
    if not posted_at:
        return None
    try:
        dt = datetime.fromisoformat(posted_at.replace("Z", "+00:00"))
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        return max(0, (datetime.now(timezone.utc) - dt).days)
    except ValueError:
        return None


def hard_filters(job, p: dict) -> list[str]:
    """Reasons a job fails the user's non-negotiables. Empty list == passes."""
    why = []
    if job["company"] and job["company"].lower() in {c.lower() for c in p.get("excluded_companies", [])}:
        why.append("Company is on your exclude list")
    locs = [city_of(l) for l in p.get("locations", []) if l]
    if locs and not (job["remote"] and p.get("remote_ok", True)):
        jc = city_of(job["location"])
        if not jc or not any(l and (l in jc or jc in l) for l in locs):
            why.append(f"Location ({job['location'] or 'unknown'}) outside your preferred cities")
    years = float(p.get("years_experience") or 0)
    if job["exp_min"] is not None and years + 1 < job["exp_min"]:
        why.append(f"Needs {job['exp_min']:g}+ yrs; you have {years:g}")
    if job["exp_max"] is not None and years - 3 > job["exp_max"]:
        why.append(f"Role targets up to {job['exp_max']:g} yrs; you have {years:g} (over-qualified)")
    floor = p.get("min_salary_lpa")
    if floor and job["salary_max"] is not None and job["salary_max"] < floor:
        why.append(f"Pays up to {job['salary_max']:g} LPA, below your {floor:g} LPA floor")
    return why


def score_job(job, p: dict, idf: dict[str, float], pvec: dict, pskills: set[str]) -> dict:
    jskills = jload(job["skills"], [])
    matched = [s for s in jskills if s in pskills]
    missing = [s for s in jskills if s not in pskills]
    cov = len(matched) / len(jskills) if jskills else 0.0
    skill_part = W_SKILLS * cov

    text_sim = _cos(pvec, _tfidf(tokens(f"{job['title']} {job['description']}"), idf))
    text_part = W_TEXT * min(1.0, text_sim * 2.2)

    targets = [t.lower() for t in p.get("target_titles", [])] + [p.get("headline", "").lower()]
    ttoks = set(tokens(job["title"]))
    best = 0.0
    for t in targets:
        tt = set(tokens(t))
        if tt:
            best = max(best, len(tt & ttoks) / len(tt | ttoks))
    title_part = W_TITLE * min(1.0, best * 1.8)

    years = float(p.get("years_experience") or 0)
    lo, hi = job["exp_min"], job["exp_max"]
    if lo is None:
        exp_part = W_EXP * 0.6
    elif years < lo:
        exp_part = W_EXP * max(0.0, 1 - (lo - years) / 3)
    elif hi is not None and years > hi:
        exp_part = W_EXP * max(0.3, 1 - (years - hi) / 6)
    else:
        exp_part = W_EXP

    score = round(skill_part + text_part + title_part + exp_part)
    age = age_days(job["posted_at"])
    flags = []
    if age is not None and age > STALE_DAYS:
        flags.append(f"Posted {age} days ago - may be filled")
    if not job["description"] or len(job["description"]) < 60:
        flags.append("Thin description - match is less certain")
    if not jskills:
        flags.append("No skills detected in listing")
    reasons = []
    if matched:
        reasons.append("You have: " + ", ".join(matched[:6]))
    if missing:
        reasons.append("Missing: " + ", ".join(missing[:5]))
    return {
        "score": max(0, min(100, score)), "matched": matched, "missing": missing, "flags": flags, "reasons": reasons,
        "breakdown": {"skills": round(skill_part), "text": round(text_part), "title": round(title_part), "experience": round(exp_part)},
        "age_days": age,
    }


def rank(jobs, p: dict, include_filtered: bool = False, feedback: dict | None = None):
    idf = build_idf(jobs)
    pvec = _tfidf(tokens(profile_text(p)), idf)
    pskills = profile_skills(p)
    feedback = feedback or {}
    out = []
    for j in jobs:
        sig = feedback.get(j["id"])
        if sig == "skip" and not include_filtered:
            continue
        why = hard_filters(j, p)
        if why and not include_filtered:
            continue
        s = score_job(j, p, idf, pvec, pskills)
        if sig == "save":
            s["score"] = min(100, s["score"] + 3)
        out.append({"job": j, "filtered_out": why, "feedback": sig, **s})
    out.sort(key=lambda r: (bool(r["filtered_out"]), -r["score"]))
    return out

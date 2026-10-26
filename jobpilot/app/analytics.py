"""Market analytics (what employers want) and personal analytics (where you stand and what to do next)."""
import statistics
from collections import Counter

from .db import jload
from .normalize import city_of
from .skills import category


def _pct(n, d):
    return round(100 * n / d) if d else 0


def market(jobs, pskills: set[str]) -> dict:
    n = len(jobs)
    src, city, skills, mids, exp_band, fresh = Counter(), Counter(), Counter(), [], Counter(), Counter()
    remote = 0
    from .matching import age_days
    for j in jobs:
        for s in jload(j["sources"], []):
            src[s.split(":")[0] if s.startswith("email") else s] += 1
        if j["remote"]:
            remote += 1
        c = city_of(j["location"])
        if c and not j["remote"]:
            city[c.title()] += 1
        for s in jload(j["skills"], []):
            skills[s] += 1
        if j["salary_min"] is not None and j["salary_max"] is not None:
            mids.append((j["salary_min"] + j["salary_max"]) / 2)
        if j["exp_min"] is not None:
            lo = j["exp_min"]
            exp_band["0-1 yrs" if lo < 1 else "1-3 yrs" if lo < 3 else "3-6 yrs" if lo < 6 else "6+ yrs"] += 1
        a = age_days(j["posted_at"])
        if a is not None:
            fresh["Last 7 days" if a <= 7 else "8-30 days" if a <= 30 else "30+ days"] += 1
    return {
        "total_jobs": n, "remote_pct": _pct(remote, n),
        "by_source": [{"name": k, "count": v} for k, v in src.most_common()],
        "by_city": [{"name": k, "count": v} for k, v in city.most_common(8)],
        "top_skills": [{"name": k, "count": v, "pct": _pct(v, n), "category": category(k), "you_have": k in pskills}
                       for k, v in skills.most_common(15)],
        "median_salary_lpa": round(statistics.median(mids), 1) if mids else None,
        "experience_bands": [{"name": k, "count": exp_band[k]} for k in ("0-1 yrs", "1-3 yrs", "3-6 yrs", "6+ yrs") if exp_band[k]],
        "freshness": [{"name": k, "count": fresh[k]} for k in ("Last 7 days", "8-30 days", "30+ days") if fresh[k]],
    }


def personal(ranked: list[dict], apps: list, pskills: set[str]) -> dict:
    buckets = Counter()
    for r in ranked:
        s = r["score"]
        buckets["80-100" if s >= 80 else "60-79" if s >= 60 else "40-59" if s >= 40 else "0-39"] += 1
    good = [r for r in ranked if r["score"] >= 40]
    miss, unlock = Counter(), Counter()
    for r in good:
        for s in r["missing"]:
            miss[s] += 1
        if 0 < len(r["missing"]) <= 2:
            for s in r["missing"]:
                unlock[s] += 1
    gaps = [{"skill": s, "category": category(s), "jobs_need_it": c, "jobs_it_would_unlock": unlock[s]}
            for s, c in sorted(miss.items(), key=lambda kv: (-unlock[kv[0]], -kv[1]))[:8]]
    funnel = Counter(a["status"] for a in apps)
    applied = sum(funnel[k] for k in ("applied", "interview", "offer", "rejected"))
    pay = [(r["job"]["salary_min"] + r["job"]["salary_max"]) / 2 for r in ranked
           if r["score"] >= 60 and r["job"]["salary_min"] is not None and r["job"]["salary_max"] is not None]
    return {
        "match_distribution": [{"name": k, "count": buckets[k]} for k in ("80-100", "60-79", "40-59", "0-39")],
        "skill_gaps": gaps,
        "funnel": {k: funnel[k] for k in ("prepared", "applied", "interview", "offer", "rejected")},
        "response_rate_pct": _pct(funnel["interview"] + funnel["offer"], applied) if applied else None,
        "median_pay_good_matches_lpa": round(statistics.median(pay), 1) if pay else None,
        "minutes_saved_estimate": len(apps) * 35,
        "good_matches": len([r for r in ranked if r["score"] >= 60]),
    }

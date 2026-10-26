"""Public job-board APIs (no keys, no login): Greenhouse, Lever, Ashby."""
import re
from datetime import datetime, timezone

from . import net

_SLUG = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_-]{0,60}$")


def _slug(s: str) -> str:
    s = s.strip()
    if not _SLUG.match(s):
        raise net.FetchError("Invalid board name")
    return s


def greenhouse(board: str) -> list[dict]:
    data = net.get(f"https://boards-api.greenhouse.io/v1/boards/{_slug(board)}/jobs?content=true", robots=False).json()
    return [{
        "title": j.get("title"), "company": (data.get("meta") or {}).get("name") or board,
        "location": (j.get("location") or {}).get("name", ""), "description": j.get("content", ""),
        "url": j.get("absolute_url"), "posted_at": j.get("updated_at"),
    } for j in data.get("jobs", [])]


def lever(company: str) -> list[dict]:
    data = net.get(f"https://api.lever.co/v0/postings/{_slug(company)}?mode=json", robots=False).json()
    out = []
    for j in data:
        cat = j.get("categories") or {}
        ts = j.get("createdAt")
        out.append({
            "title": j.get("text"), "company": company, "location": cat.get("location", ""),
            "remote": (j.get("workplaceType") == "remote"),
            "description": (j.get("descriptionPlain") or "") + "\n" + "\n".join(
                f"{l.get('text','')}: {l.get('content','')}" for l in j.get("lists", [])),
            "url": j.get("hostedUrl"),
            "posted_at": datetime.fromtimestamp(ts / 1000, timezone.utc).isoformat() if ts else None,
        })
    return out


def ashby(org: str) -> list[dict]:
    data = net.get(f"https://api.ashbyhq.com/posting-api/job-board/{_slug(org)}", robots=False).json()
    return [{
        "title": j.get("title"), "company": org, "location": j.get("location", ""),
        "remote": bool(j.get("isRemote")), "description": j.get("descriptionHtml") or j.get("descriptionPlain", ""),
        "url": j.get("jobUrl"), "posted_at": j.get("publishedAt"),
    } for j in data.get("jobs", [])]


CONNECTORS = {"greenhouse": greenhouse, "lever": lever, "ashby": ashby}

"""Career pages that publish schema.org JobPosting (required for Google Jobs)."""
import json

from bs4 import BeautifulSoup

from . import net


def _walk(node):
    if isinstance(node, list):
        for n in node:
            yield from _walk(n)
    elif isinstance(node, dict):
        t = node.get("@type")
        if t == "JobPosting" or (isinstance(t, list) and "JobPosting" in t):
            yield node
        for k in ("@graph", "itemListElement", "item"):
            if k in node:
                yield from _walk(node[k])


def _loc(jl) -> str:
    if isinstance(jl, list):
        jl = jl[0] if jl else {}
    addr = (jl or {}).get("address", {}) if isinstance(jl, dict) else {}
    if isinstance(addr, str):
        return addr
    return ", ".join(x for x in (addr.get("addressLocality"), addr.get("addressRegion")) if x)


def parse_html(html: str, page_url: str = "") -> list[dict]:
    jobs = []
    for tag in BeautifulSoup(html, "lxml").find_all("script", type="application/ld+json"):
        try:
            data = json.loads(tag.string or "")
        except ValueError:
            continue
        for jp in _walk(data):
            org = jp.get("hiringOrganization") or {}
            company = org.get("name") if isinstance(org, dict) else str(org)
            sal = ((jp.get("baseSalary") or {}).get("value") or {}) if isinstance(jp.get("baseSalary"), dict) else {}
            lo, hi = sal.get("minValue"), sal.get("maxValue")
            inr = (jp.get("baseSalary") or {}).get("currency") == "INR" if isinstance(jp.get("baseSalary"), dict) else False
            unit = sal.get("unitText", "YEAR") if isinstance(sal, dict) else "YEAR"
            if inr and lo and unit == "YEAR":
                lo, hi = float(lo) / 1e5, float(hi or lo) / 1e5
            else:
                lo = hi = None
            jobs.append({
                "title": jp.get("title"), "company": company, "location": _loc(jp.get("jobLocation")),
                "remote": jp.get("jobLocationType") == "TELECOMMUTE", "description": jp.get("description", ""),
                "url": jp.get("url") or page_url, "posted_at": jp.get("datePosted"),
                "salary_min": lo, "salary_max": hi,
            })
    return jobs


def fetch(url: str) -> list[dict]:
    r = net.get(url)
    return parse_html(r.text, url)

"""Tailored CV generation that can only SELECT, REORDER and RE-EMPHASISE what is already in the profile.

Nothing is invented: every skill, role, bullet and number in the output is checked against the profile
(`verify_no_fabrication`). The user sees a diff before using a CV."""
import html

from .db import jload
from .matching import tokens
from .skills import extract_skills, pretty

BULLETS_PER_ROLE = 4


def _relevance(text: str, jtoks: set[str], jskills: set[str]) -> float:
    toks = set(tokens(text))
    sk = set(extract_skills(text))
    return len(toks & jtoks) + 3 * len(sk & jskills)


def tailor(profile: dict, job, match: dict | None = None) -> dict:
    jskills = set(jload(job["skills"], []))
    jtoks = set(tokens(f"{job['title']} {job['description']}"))
    pskills = list(dict.fromkeys(profile.get("skills", [])))
    ordered = [s for s in jskills_order(job) if s in pskills] + [s for s in pskills if s not in jskills]
    ordered = list(dict.fromkeys(ordered))

    roles, diff = [], []
    kept_total = dropped_total = 0
    for e in profile.get("experiences", []):
        bl = e.get("bullets", [])
        ranked = sorted(range(len(bl)), key=lambda i: (-_relevance(bl[i], jtoks, jskills), i))
        keep = ranked[:BULLETS_PER_ROLE]
        keep_sorted = sorted(keep, key=lambda i: (-_relevance(bl[i], jtoks, jskills), i))
        roles.append({**{k: e.get(k, "") for k in ("title", "company", "start", "end")}, "bullets": [bl[i] for i in keep_sorted]})
        kept_total += len(keep)
        dropped_total += len(bl) - len(keep)
        if keep_sorted != sorted(keep_sorted):
            diff.append(f"Reordered bullets under '{e.get('title','')}' so the most relevant come first")
    if dropped_total:
        diff.append(f"Left out {dropped_total} less-relevant bullet(s) to keep the CV focused (they stay in your profile)")

    moved = [s for s in ordered[:len(jskills & set(pskills))]]
    if moved and ordered != pskills:
        diff.append("Moved matching skills to the top: " + ", ".join(moved[:8]))

    projects = sorted(profile.get("projects", []), key=lambda p: -_relevance(" ".join([p.get("name", ""), *p.get("bullets", [])]), jtoks, jskills))[:2]

    years = float(profile.get("years_experience") or 0)
    headline = profile.get("headline", "")
    strong = [s for s in ordered if s in jskills][:4] or ordered[:4]
    base_summary = (profile.get("summary") or "").strip()
    gen = f"{headline or 'Professional'} with {years:g} years of experience" if years else (headline or "Professional")
    if strong:
        gen += f" in {', '.join(pretty(x) for x in strong)}"
    summary = (gen + ". " + base_summary).strip()[:700]
    diff.append("Summary rebuilt from your own headline, experience years and the skills this job asks for")

    cv = {
        "name": profile.get("name", ""), "email": profile.get("email", ""), "phone": profile.get("phone", ""),
        "links": profile.get("links", {}), "headline": headline, "summary": summary,
        "skills": ordered, "experiences": roles, "education": profile.get("education", []), "projects": projects,
        "target": {"title": job["title"], "company": job["company"]},
    }
    problems = verify_no_fabrication(cv, profile)
    return {"cv": cv, "html": render_html(cv), "text": render_text(cv), "diff": diff, "verified": not problems, "problems": problems,
            "stats": {"bullets_kept": kept_total, "bullets_left_out": dropped_total}}


def jskills_order(job) -> list[str]:
    return jload(job["skills"], [])


def verify_no_fabrication(cv: dict, profile: dict) -> list[str]:
    """Every factual element in the CV must exist in the profile. Returns a list of violations."""
    bad = []
    pskills = set(profile.get("skills", []))
    for s in cv["skills"]:
        if s not in pskills:
            bad.append(f"skill not in profile: {s}")
    pbul = {(e.get("title"), b) for e in profile.get("experiences", []) for b in e.get("bullets", [])}
    ptitles = {(e.get("title"), e.get("company"), e.get("start"), e.get("end")) for e in profile.get("experiences", [])}
    for e in cv["experiences"]:
        if (e["title"], e["company"], e["start"], e["end"]) not in ptitles:
            bad.append(f"role not in profile: {e['title']}")
        for b in e["bullets"]:
            if (e["title"], b) not in pbul:
                bad.append(f"bullet not in profile: {b[:50]}")
    if cv["headline"] != profile.get("headline", ""):
        bad.append("headline differs from profile")
    pedu = {str(x) for x in profile.get("education", [])}
    for ed in cv["education"]:
        if str(ed) not in pedu:
            bad.append("education not in profile")
    return bad


def _e(s) -> str:
    return html.escape(str(s or ""))


def render_html(cv: dict) -> str:
    contact = " &bull; ".join(_e(x) for x in (cv["email"], cv["phone"], *cv.get("links", {}).values()) if x)
    exp = "".join(
        f"<div class='role'><div class='rh'><b>{_e(r['title'])}</b>{' &mdash; ' + _e(r['company']) if r['company'] else ''}"
        f"<span class='dt'>{_e(r['start'])} &ndash; {_e(r['end'])}</span></div><ul>"
        + "".join(f"<li>{_e(b)}</li>" for b in r["bullets"]) + "</ul></div>"
        for r in cv["experiences"])
    edu = "".join(f"<div>{_e(e.get('degree'))}{' &mdash; ' + _e(e.get('institution')) if e.get('institution') else ''}</div>" for e in cv["education"])
    proj = "".join(f"<div class='role'><b>{_e(p.get('name'))}</b><ul>" + "".join(f"<li>{_e(b)}</li>" for b in p.get("bullets", [])) + "</ul></div>" for p in cv["projects"])
    sec = lambda t, body: f"<h2>{t}</h2>{body}" if body else ""
    return f"""<!doctype html><html><head><meta charset='utf-8'><title>{_e(cv['name'])} - CV</title><style>
body{{font-family:Arial,Helvetica,sans-serif;max-width:780px;margin:24px auto;color:#111;font-size:13.5px;line-height:1.45}}
h1{{margin:0;font-size:26px}}h2{{font-size:14px;border-bottom:1px solid #999;margin:16px 0 6px;text-transform:uppercase;letter-spacing:.06em}}
.sub{{color:#444}}.rh{{display:flex;justify-content:space-between}}.dt{{color:#555}}ul{{margin:4px 0 8px 18px;padding:0}}
@media print{{body{{margin:0}}}}</style></head><body>
<h1>{_e(cv['name'])}</h1><div class='sub'>{_e(cv['headline'])}</div><div class='sub'>{contact}</div>
{sec('Summary', '<div>' + _e(cv['summary']) + '</div>')}{sec('Skills', '<div>' + _e(', '.join(pretty(x) for x in cv['skills'])) + '</div>')}
{sec('Experience', exp)}{sec('Projects', proj)}{sec('Education', edu)}</body></html>"""


def render_text(cv: dict) -> str:
    out = [cv["name"], cv["headline"], " | ".join(x for x in (cv["email"], cv["phone"]) if x), "", "SUMMARY", cv["summary"], "",
           "SKILLS", ", ".join(pretty(x) for x in cv["skills"]), "", "EXPERIENCE"]
    for r in cv["experiences"]:
        out.append(f"{r['title']} - {r['company']} ({r['start']} - {r['end']})")
        out += [f"  - {b}" for b in r["bullets"]]
    if cv["projects"]:
        out += ["", "PROJECTS"] + [f"{p.get('name')}" for p in cv["projects"]]
    out += ["", "EDUCATION"] + [e.get("degree", "") for e in cv["education"]]
    return "\n".join(out)

"""Parse job-alert emails (Naukri, LinkedIn, Indeed, Foundit...) that the user forwards or exports as .eml.

We read mail the user already receives; we never log in to those portals."""
import email
import re
from email import policy
from urllib.parse import urlparse

from bs4 import BeautifulSoup

JOB_LINK = re.compile(
    r"(naukri\.com/job-listings|linkedin\.com/(comm/)?jobs/view|indeed\.[a-z.]+/(viewjob|rc/clk|pagead)|"
    r"foundit\.in/job|monsterindia\.com/.*job|glassdoor\.[a-z.]+/job-listing|instahyre\.com/job)", re.I)
SKIP_TEXT = re.compile(r"^(view|apply|see|unsubscribe|manage|jobs?|more|click|get|edit|new)\b", re.I)
SALARY_LINE = re.compile(r"(lpa|lakh|₹|rs\.?|\bl\b)", re.I)
EXP_LINE = re.compile(r"\d+\s*(?:-|to|–)?\s*\d*\s*(?:yrs?|years?)", re.I)


def _source(url: str, sender: str) -> str:
    h = (urlparse(url).hostname or "") + " " + sender.lower()
    for key in ("naukri", "linkedin", "indeed", "foundit", "monster", "glassdoor", "instahyre"):
        if key in h:
            return f"email:{key}"
    return "email:other"


def _block_lines(a) -> list[str]:
    node = a
    for _ in range(5):  # climb to the enclosing card/row
        parent = node.parent
        if not parent:
            break
        node = parent
        n_links = sum(1 for x in node.find_all("a", href=True) if JOB_LINK.search(x["href"]))
        if n_links > 1:
            break
        if node.name in ("tr", "table", "li") and len(node.get_text(strip=True)) > len(a.get_text(strip=True)) + 5:
            break
    text = node.get_text("\n", strip=True)
    return [l.strip() for l in text.split("\n") if l.strip()]


def parse_eml(raw: bytes) -> list[dict]:
    msg = email.message_from_bytes(raw, policy=policy.default)
    sender = str(msg.get("From", ""))
    body = msg.get_body(preferencelist=("html",))
    if body is None:
        return []
    soup = BeautifulSoup(body.get_content(), "lxml")
    seen, jobs = set(), []
    for a in soup.find_all("a", href=True):
        href = a["href"]
        if not JOB_LINK.search(href):
            continue
        title = " ".join(a.get_text(" ", strip=True).split())
        if not title or SKIP_TEXT.match(title) or len(title) > 120:
            continue
        clean_url = href.split("?")[0]
        if clean_url in seen:
            continue
        seen.add(clean_url)
        lines = [l for l in _block_lines(a) if l != title and not SKIP_TEXT.match(l)]
        company = next((l for l in lines if 2 < len(l) < 70 and not SALARY_LINE.search(l) and not EXP_LINE.search(l)), "")
        rest = [l for l in lines if l != company]
        loc = next((l for l in rest if 2 < len(l) < 70 and not SALARY_LINE.search(l) and not EXP_LINE.search(l)), "")
        extra = " ".join(l for l in rest if SALARY_LINE.search(l) or EXP_LINE.search(l))
        jobs.append({"title": title, "company": company, "location": loc, "description": extra,
                     "experience": extra, "url": clean_url, "_source": _source(href, sender)})
    return jobs

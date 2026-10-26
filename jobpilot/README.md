# JobPilot

One place for the whole job hunt: **capture jobs → de-duplicate → match → analyse → tailored CV → fast apply → track**,
built so that nothing depends on breaking a portal's terms of service.

## Run it

```bash
cd jobpilot
pip install -r requirements.txt
python -m uvicorn app.main:app --port 8000      # open http://localhost:8000
python -m pytest -q                              # 27 tests
NODE_PATH=$(npm root -g) node extension/test_filler.js   # autofill check (needs playwright)
```
`JOBPILOT_DB` sets the SQLite path (default `data/jobpilot.db`).

Try it in 2 minutes: sign up → Profile → upload a CV → Sources & Consent → *Load sample jobs* → Jobs / Insights.

## What is built

| Stage | What it does | Code |
|---|---|---|
| Profile | Parses a PDF/DOCX/TXT CV into a structured "fact bank" the user reviews | `app/resume.py` |
| Capture | Greenhouse / Lever / Ashby public boards, career pages with schema.org `JobPosting`, **Gmail auto-import of job-alert emails** (page `/gmail`, setup in `docs/GMAIL_SETUP.md`), manual `.eml` upload, sample data | `app/ingest/` |
| De-dupe | Same job on several portals becomes one listing (company + title + city fingerprint) | `app/normalize.py`, `app/store.py` |
| Match | Hard filters (city/remote, experience, salary floor, excluded companies) then an explainable score: skills 55, wording 20, role 15, experience 10 | `app/matching.py` |
| Analyse | Market view (demand, cities, freshness, pay) and personal view (skill gaps that unlock the most jobs, funnel, response rate) | `app/analytics.py` |
| Tailor CV | Selects, reorders and re-emphasises the user's own facts per job; a verifier rejects any skill/role/bullet not in the profile | `app/cvgen.py` |
| Apply | Builds an application pack (apply link or draft email + CV). The user submits and marks it applied; follow-up reminder is set | `app/main.py` |
| Autofill | Chrome extension fills any form from the profile; never submits, never fills salary/gender/consent-type questions, never overwrites typed values | `extension/` |
| Track | Applications table with status, follow-up date, notes | UI |
| Privacy | Per-scope consent with revoke, audit log, data export, full account deletion, private email-sourced jobs | `app/main.py` |

## Deliberate design choices
- **No portal logins, no scraping of Naukri/LinkedIn.** Jobs from those portals arrive through the alert emails the user already receives.
- **"One tap" is honest:** nothing is submitted on an employer's site automatically. True one-tap needs partner/ATS apply APIs (later phase).
- **CVs never invent anything.** `verify_no_fabrication` runs on every generated CV and the API refuses to return one that fails.
- Extension code is a separate limited token: it can read only the autofill profile, and revoking consent cuts it off immediately.

## Known gaps (read before putting real users on it)
- **Live ATS connectors are untested against the real services.** They are written to the documented response shapes and covered by mock tests only (the build sandbox had no access to those hosts). Test with a real board name before relying on them.
- Resume parsing is heuristic; scanned PDFs are not supported (no OCR). Always review the parsed profile.
- Alert emails carry titles only, so skills for those jobs come from the title.
- No rate limiting / brute-force protection on login, no email verification or password reset, SQLite single-node. Add before launch.
- Gmail import is tested against mocked Google endpoints only (no real Google account was available in the build sandbox). Do one real end-to-end connect with your own account first. `gmail.readonly` needs Google verification before public launch (see `docs/GMAIL_SETUP.md`). Outlook is not built.
- HTTPS and a production secret/session policy are the deployer's job.
- DPDP: the controls exist (consent, export, delete) but this is not legal advice; get a compliance review before launch.
- Outbound fetching resolves DNS locally for SSRF protection; behind a proxy-only network it will report "Cannot resolve".

## Roadmap
1. Outlook import; Google verification for public launch
2. Email/push digest
3. Optional LLM rephrasing inside the verifier's guardrails
4. Employer/ATS apply APIs and partner programs (LinkedIn, Naukri, Indeed) for true one-tap
5. Mobile app, Hindi/regional UI

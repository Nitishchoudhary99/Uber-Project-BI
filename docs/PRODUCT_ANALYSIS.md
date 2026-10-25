# Job Search Automation Platform: Product Analysis

## 1. The daily frustration, broken down

| # | Pain | Today | Our fix |
|---|------|-------|---------|
| 1 | Checking 5+ portals every day | Manual, repetitive | One unified feed, deduplicated across sources |
| 2 | 90% of results are irrelevant | Keyword search only | Hard filters + semantic match + explainable score |
| 3 | Same job reposted on many portals | Duplicates everywhere | Cross-source dedupe (company + title + location + fingerprint) |
| 4 | Rewriting the CV per job | 20-40 min per application | Truthful per-job CV in seconds |
| 5 | Retyping the same details in every form | 5-15 min per form | Profile-based autofill, one confirmation |
| 6 | Applying to dead or fake listings | Wasted effort, ghosting | Freshness + legitimacy scoring |
| 7 | No idea what happened after applying | Spreadsheets | Application tracker with follow-up nudges |
| 8 | Missing jobs posted overnight | Late applicants lose | Instant alerts, apply within minutes |

Core promise: **open the app once a day, see only the best-fit jobs, tap once.**

## 2. Consented access: what works and what does not

Consent screens are required for every connection, and they must be revocable. But the access method matters more than the consent.

### Safe and recommended
| Method | Covers | Notes |
|--------|--------|-------|
| **User connects Gmail/Outlook (read-only, job-label scoped)** | Naukri, LinkedIn, Indeed, Foundit job-alert emails | The user already receives these alerts. We parse them. No platform terms are violated. Cheapest way to cover major portals. |
| **Public ATS job-board APIs** | Greenhouse, Lever, Ashby, SmartRecruiters, Workable | Official, free, structured data, and direct apply endpoints exist for many. |
| **Company career pages with JobPosting schema** | Most Indian and global employers | Google Jobs requires `JobPosting` structured data. Fetch respecting robots.txt. |
| **Official partner/API programs** | LinkedIn (apply with LinkedIn / talent partners), Naukri (recruiter/partner API), Indeed (partner APIs) | Slower to get, but the only fully legitimate route to in-platform apply. Start the application early. |
| **User-side browser extension, user-driven** | Autofill on any form | Runs on a page the user opened, fills fields, the user clicks submit. Assistive, not a bot. |

### Avoid
- Server-side logins with stored Naukri or LinkedIn passwords.
- Headless-browser scraping or bulk auto-applying on those platforms.
- Mass auto-apply with no review step. It produces low-quality applications and gets accounts flagged.

### Where "one tap" is genuinely possible
1. ATS jobs with an apply API: true one-tap.
2. Email-based applications (many Indian SMEs): we send the tailored CV from the user's connected mailbox.
3. Everything else: autofill plus a single review-and-submit tap.

## 3. Matching: how "exact match" is defined

1. **Hard filters (binary):** location or remote, salary floor, experience band, notice period, work authorization, must-have skills, excluded companies.
2. **Semantic score (0-100):** embeddings of profile vs. job description, plus skill-graph overlap (React ≈ frontend; Tally ≈ accounting software).
3. **Explainability:** every job shows "matches: X, Y, Z; missing: A (learnable)". No black box.
4. **Learning loop:** swipe, save and skip signals tune weights per user.
5. **Quality gate:** drop listings older than N days, listings with no company, and duplicates.

## 4. Per-job CV generation: the guardrails

- Input is the user's verified profile (a fact bank). The model may only **select, reorder and rephrase** facts. It must never add skills, titles, employers, dates or numbers.
- ATS-friendly single-column template, keywords mirrored from the JD only where the user truly has the skill.
- A "what changed" diff is shown before each use; the user can lock any line.
- Outputs: PDF plus plain text, with versions stored per application.

## 5. Autofill design

- Canonical profile schema (identity, education, experience, notice period, current and expected CTC, links, work authorization, custom Q&A bank).
- Field mapper: heuristics first (label, name, autocomplete attributes), then an LLM fallback for unknown fields, with confidence scores.
- Low-confidence or sensitive fields (salary, gender, disability, consent checkboxes) are always shown to the user instead of silently filled.
- Learns answers to repeated screening questions.

## 6. Trust, legal and safety

- **DPDP Act (India) compliance:** explicit purpose-specific consent, data minimization, export and delete, breach process, India data residency preferred.
- OAuth tokens only. Never store third-party passwords.
- Per-source consent toggles and an audit log of everything the platform did on the user's behalf.
- No fabricated experience, ever. This is both an ethical rule and the product's reputation.
- Rate limits and robots.txt respected on every fetch.

## 7. Phased roadmap

| Phase | Scope | Outcome |
|-------|-------|---------|
| 0 (2 wks) | Profile schema, resume parser, consent flows | Verified profile in 2 minutes |
| 1 (4-6 wks) | Gmail alert ingestion + Greenhouse/Lever/Ashby feeds, dedupe, match scoring, daily digest | Replaces daily searching |
| 2 (4 wks) | Tailored CV generator with diff view | Per-job CV in seconds |
| 3 (4-6 wks) | Browser extension autofill + application tracker | Fast apply anywhere |
| 4 (ongoing) | Apply APIs, email-apply, partner programs (LinkedIn, Naukri, Indeed), mobile app, regional languages | True one-tap |

## 8. Success metrics
- Time spent searching per day (target: under 5 min).
- Relevant-job precision (user "good match" rate above 70%).
- Time from job posted to applied.
- Applications per interview (quality signal).

## 9. Open decisions
1. Web-first or mobile-first?
2. Stack confirmation (proposed: Next.js, FastAPI, Postgres + pgvector, Claude API).
3. Start the LinkedIn/Naukri partner-program applications now (long lead time)?
4. Target segment for the MVP (freshers, IT professionals, blue-collar, etc.).

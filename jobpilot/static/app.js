"use strict";
const $app = document.getElementById("app");
const S = { pretty: {}, token: localStorage.getItem("jp_token") || "", view: "today", profile: null, cache: {} };
const VIEWS = [["today", "Today"], ["jobs", "Jobs"], ["profile", "Profile"], ["sources", "Sources & Consent"], ["apps", "Applications"], ["insights", "Insights"]];

const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const chips = (a, cls = "") => (a || []).map((x) => `<span class="chip ${cls}">${esc((S.pretty || {})[x] || x)}</span>`).join("");
const scoreCls = (n) => (n >= 75 ? "s-hi" : n >= 50 ? "s-mid" : "s-lo");

async function api(path, opts = {}) {
  const headers = { Authorization: "Bearer " + S.token, ...(opts.body && !(opts.body instanceof FormData) ? { "Content-Type": "application/json" } : {}) };
  const res = await fetch("/api" + path, { ...opts, headers, body: opts.body && !(opts.body instanceof FormData) ? JSON.stringify(opts.body) : opts.body });
  if (res.status === 401 && S.token) { logout(); throw new Error("Please log in again"); }
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(typeof data.detail === "string" ? data.detail : "Something went wrong");
  return data;
}
function toast(msg) {
  document.querySelectorAll(".toast").forEach((t) => t.remove());
  const t = document.createElement("div"); t.className = "toast"; t.textContent = msg; document.body.appendChild(t); setTimeout(() => t.remove(), 3500);
}
const guard = (fn) => async (...a) => { try { await fn(...a); } catch (e) { toast(e.message); } };
function logout() { localStorage.removeItem("jp_token"); S.token = ""; S.profile = null; draw(); }

/* ---------- shell ---------- */
function draw() {
  if (!S.token) return drawAuth();
  $app.innerHTML = `<div class="top"><span class="brand">🚀 JobPilot</span>${VIEWS.map(([k, l]) => `<button class="nav ${S.view === k ? "on" : ""}" data-act="nav" data-view="${k}">${l}</button>`).join("")}<span class="spacer"></span><button class="nav" data-act="logout">Log out</button></div><main id="main"></main><div id="drawer"></div>`;
  load(S.view);
}
function drawAuth(mode = "login", err = "") {
  $app.innerHTML = `<div class="auth card"><h1>🚀 JobPilot</h1><p class="muted">Stop searching every day. Your best-fit jobs, a tailored CV for each, and fast applying - with your consent at every step.</p>
  <form id="authform" data-mode="${mode}"><label>Email</label><input type="email" name="email" required autocomplete="email"><label>Password (8+ characters)</label><input type="password" name="password" required minlength="8" autocomplete="${mode === "login" ? "current-password" : "new-password"}">
  <div class="err">${esc(err)}</div><button class="btn" style="width:100%">${mode === "login" ? "Log in" : "Create account"}</button></form>
  <p class="small muted" style="text-align:center"><a href="#" data-act="authmode" data-mode="${mode === "login" ? "signup" : "login"}">${mode === "login" ? "New here? Create an account" : "Have an account? Log in"}</a></p></div>`;
}
async function load(view) {
  S.view = view;
  const main = document.getElementById("main"); if (!main) return;
  main.innerHTML = `<p class="muted">Loading…</p>`;
  try {
    S.profile = await api("/profile");
    if (!Object.keys(S.pretty).length) S.pretty = (await api("/meta")).pretty;
    main.innerHTML = await ({ today: vToday, jobs: vJobs, profile: vProfile, sources: vSources, apps: vApps, insights: vInsights }[view])();
    document.querySelectorAll(".nav").forEach((b) => b.classList.toggle("on", b.dataset.view === view));
  } catch (e) { main.innerHTML = `<p class="err">${esc(e.message)}</p>`; }
}

/* ---------- Today ---------- */
function jobCard(r) {
  const j = r.job;
  return `<div class="card job" data-act="open" data-id="${j.id}"><div class="score ${scoreCls(r.score)}">${r.score}</div><div style="flex:1;min-width:0">
  <h3>${esc(j.title)}</h3><div class="muted small">${esc(j.company)} · ${esc(j.location || "Location n/a")}${j.remote && !/remote/i.test(j.location || "") ? " · Remote" : ""}${j.salary_min ? ` · ${j.salary_min}-${j.salary_max} LPA` : ""}${j.exp_min != null ? ` · ${j.exp_min}${j.exp_max ? "-" + j.exp_max : "+"} yrs` : ""}</div>
  <div>${chips(r.matched.slice(0, 6), "good")}${chips(r.missing.slice(0, 3), "warn")}</div>
  ${(r.flags || []).map((f) => `<div class="small" style="color:var(--warn)">⚠ ${esc(f)}</div>`).join("")}
  ${j.sources.length > 1 ? `<div class="small muted">Seen on ${j.sources.length} sources - merged into one listing</div>` : ""}</div>
  <div class="small muted">${j.age_days != null ? j.age_days + "d ago" : ""}</div></div>`;
}
async function vToday() {
  const [d, cons, srcs] = await Promise.all([api("/digest"), api("/consents"), api("/sources")]);
  const p = S.profile;
  const steps = [["Add your profile (upload a CV or fill it in)", !d.needs_profile], ["Choose what we may use (Sources & Consent)", cons.some((c) => c.granted)], ["Bring in jobs (sample data, a company board, or an alert email)", d.top_matches.length > 0 || srcs.length > 0]];
  const onboarding = steps.every((s) => s[1]) ? "" : `<div class="card"><h2>Get set up in 3 steps</h2>${steps.map(([t, ok], i) => `<div class="step"><span class="dot ${ok ? "done" : ""}">${ok ? "✓" : i + 1}</span>${esc(t)}</div>`).join("")}
  <div class="row" style="margin-top:8px"><button class="btn" data-act="nav" data-view="profile">Profile</button><button class="btn alt" data-act="nav" data-view="sources">Sources & Consent</button></div></div>`;
  return `<h1>${p.name ? "Hi " + esc(p.name.split(" ")[0]) + " 👋" : "Today"}</h1><p class="muted">Your whole job search in one place. Only matches that fit your filters appear here.</p>${onboarding}
  ${d.follow_ups_due.length ? `<div class="card"><h2>Follow-ups due</h2>${d.follow_ups_due.map((f) => `<div>• ${esc(f.title)} @ ${esc(f.company)} <span class="muted">(due ${esc(f.follow_up)})</span></div>`).join("")}</div>` : ""}
  <h2>Top matches for you</h2>${d.top_matches.length ? d.top_matches.map(jobCard).join("") : `<div class="card muted">No matches yet. Add your skills and bring in some jobs.</div>`}
  ${d.saved_not_applied.length ? `<div class="card"><h3>Saved, not applied yet</h3>${d.saved_not_applied.map((s) => `<div>• ${esc(s.title)} @ ${esc(s.company)}</div>`).join("")}</div>` : ""}`;
}

/* ---------- Jobs ---------- */
let jobFilter = { q: "", min: 40, filtered: false };
async function vJobs() {
  const rows = await api(`/jobs?min_score=${jobFilter.min}&include_filtered=${jobFilter.filtered}&q=${encodeURIComponent(jobFilter.q)}`);
  return `<h1>Jobs</h1><div class="card row"><input type="text" id="jq" placeholder="Search title, company, city" value="${esc(jobFilter.q)}" style="flex:2;min-width:180px">
  <label class="row small" style="margin:0">Min match <input type="number" id="jmin" min="0" max="100" value="${jobFilter.min}" style="width:70px"></label>
  <label class="row small" style="margin:0"><input type="checkbox" id="jfil" ${jobFilter.filtered ? "checked" : ""}> Show filtered-out</label><button class="btn" data-act="jobsapply">Apply filters</button></div>
  <p class="muted small">${rows.length} job(s). Hard filters (location, experience, salary floor, excluded companies) are applied first; scores explain themselves.</p>
  ${rows.map((r) => jobCard(r) + (r.filtered_out.length ? `<div class="small" style="margin:-8px 0 12px 8px;color:var(--bad)">Filtered out: ${esc(r.filtered_out.join("; "))}</div>` : "")).join("") || `<div class="card muted">Nothing here. Try lowering the minimum match, or add jobs under Sources.</div>`}`;
}
async function openJob(id) {
  const r = await api("/jobs/" + id), j = r.job, b = r.breakdown;
  const bar = (n, v, max) => `<div class="barrow"><span>${n}</span><div class="bar"><i style="width:${(v / max) * 100}%"></i></div><span>${v}/${max}</span></div>`;
  document.getElementById("drawer").innerHTML = `<div class="drawer" data-act="closedrawer"><div class="panel" data-stop="1">
  <div class="row between"><h2>${esc(j.title)}</h2><button class="btn alt" data-act="closedrawer">Close</button></div>
  <div class="muted">${esc(j.company)} · ${esc(j.location || "")}${j.remote && !/remote/i.test(j.location || "") ? " · Remote" : ""}${j.url ? ` · <a href="${esc(j.url)}" target="_blank" rel="noopener noreferrer">Original listing</a>` : ""}</div>
  <div class="card" style="margin-top:12px"><div class="row"><div class="score ${scoreCls(r.score)}">${r.score}</div><div><b>Why this score</b><div class="small muted">${esc((r.reasons || []).join(" · "))}</div></div></div>
  ${bar("Skills", b.skills, 55)}${bar("Wording match", b.text, 20)}${bar("Role fit", b.title, 15)}${bar("Experience", b.experience, 10)}
  <div>${chips(r.matched, "good")}${chips(r.missing, "warn")}</div>${(r.flags || []).map((f) => `<div class="small" style="color:var(--warn)">⚠ ${esc(f)}</div>`).join("")}
  ${r.filtered_out.length ? `<div class="small" style="color:var(--bad)">Outside your filters: ${esc(r.filtered_out.join("; "))}</div>` : ""}</div>
  <div class="row"><button class="btn" data-act="cv" data-id="${id}">✨ Tailor my CV</button><button class="btn" data-act="apply" data-id="${id}">⚡ Prepare to apply</button>
  <button class="btn alt" data-act="fb" data-id="${id}" data-sig="${r.feedback === "save" ? "clear" : "save"}">${r.feedback === "save" ? "★ Saved" : "☆ Save"}</button><button class="btn alt" data-act="fb" data-id="${id}" data-sig="skip">Not for me</button></div>
  <div id="cvbox"></div>
  <div class="card" style="margin-top:12px"><h3>Description</h3><div style="white-space:pre-wrap" class="small">${esc(j.description || "No description in this listing (alert emails only list titles). Open the original listing.")}</div></div></div></div>`;
}
async function tailorCv(id) {
  const box = document.getElementById("cvbox"); box.innerHTML = `<p class="muted">Tailoring…</p>`;
  const r = await api(`/jobs/${id}/cv`, { method: "POST" });
  S.cache.cv = r;
  box.innerHTML = `<div class="card"><h3>Tailored CV ${r.verified ? `<span class="chip good">✓ every line verified from your profile</span>` : ""}</h3>
  <p class="small muted">Nothing is invented: we only pick, reorder and emphasise what you already wrote. What changed:</p><ul class="diff small">${r.diff.map((d) => `<li>${esc(d)}</li>`).join("")}</ul>
  <iframe class="cv" sandbox srcdoc="${esc(r.html)}"></iframe><div class="row" style="margin-top:8px"><button class="btn" data-act="dl" data-kind="html">Download (open → Print → Save as PDF)</button><button class="btn alt" data-act="dl" data-kind="text">Download .txt</button></div></div>`;
}
async function prepareApply(id) {
  const r = await api(`/jobs/${id}/apply`, { method: "POST" });
  S.cache.cv = { html: r.cv_html, text: r.cv_text };
  document.getElementById("cvbox").innerHTML = `<div class="card"><h3>Application pack ready</h3><p class="small muted">${esc(r.next_step)} We never submit anything on your behalf.</p><ol>
  <li>${r.apply_url ? `<a class="btn" href="${esc(r.apply_url)}" target="_blank" rel="noopener noreferrer">Open apply page ↗</a>` : "No apply link in this listing"}</li>
  ${r.mailto ? `<li><a class="btn alt" href="${esc(r.mailto)}">Draft email</a> (attach the CV below)</li>` : ""}
  <li><button class="btn alt" data-act="dl" data-kind="html">Download tailored CV</button></li>
  <li>After you submit: <button class="btn" data-act="markapplied" data-id="${id}">Mark as applied</button></li></ol>
  <iframe class="cv" sandbox srcdoc="${esc(r.cv_html)}"></iframe></div>`;
}
function download(kind) {
  const c = S.cache.cv; if (!c) return;
  const blob = new Blob([kind === "html" ? c.html : c.text], { type: kind === "html" ? "text/html" : "text/plain" });
  const a = document.createElement("a"); a.href = URL.createObjectURL(blob); a.download = "tailored-cv." + (kind === "html" ? "html" : "txt"); a.click(); URL.revokeObjectURL(a.href);
}

/* ---------- Profile ---------- */
const roleBlock = (e = {}) => `<div class="card role"><div class="grid g2"><div><label>Title</label><input type="text" data-k="title" value="${esc(e.title)}"></div><div><label>Company</label><input type="text" data-k="company" value="${esc(e.company)}"></div>
<div><label>Start</label><input type="text" data-k="start" value="${esc(e.start)}" placeholder="Jan 2022"></div><div><label>End</label><input type="text" data-k="end" value="${esc(e.end)}" placeholder="Present"></div></div>
<label>Achievements (one per line - keep them true; CVs are built only from these)</label><textarea data-k="bullets">${esc((e.bullets || []).join("\n"))}</textarea><button class="btn danger" data-act="rmrole" type="button">Remove role</button></div>`;
async function vProfile() {
  const p = S.profile, f = (k, v, t = "text", extra = "") => `<label>${k}</label><input type="${t}" id="p_${v}" value="${esc(p[v] ?? "")}" ${extra}>`;
  return `<h1>Profile</h1><div class="card"><h2>Fast start</h2><p class="muted small">Upload a text-based PDF, DOCX or TXT. We read it locally on our server to prefill the form - always review it afterwards.</p><input type="file" id="cvfile" accept=".pdf,.docx,.txt"></div>
  <div class="card"><h2>You</h2><div class="grid g2"><div>${f("Full name", "name")}</div><div>${f("Email", "email", "email")}</div><div>${f("Phone", "phone")}</div><div>${f("Headline (your current/usual title)", "headline")}</div><div>${f("Years of experience", "years_experience", "number", 'step="0.5" min="0"')}</div><div>${f("Notice period (days)", "notice_days", "number")}</div></div>
  <label>Summary</label><textarea id="p_summary">${esc(p.summary)}</textarea></div>
  <div class="card"><h2>What you want (hard filters)</h2><div class="grid g2"><div><label>Preferred cities (comma separated)</label><input type="text" id="p_locations" value="${esc((p.locations || []).join(", "))}"></div>
  <div><label>Open to remote?</label><select id="p_remote_ok"><option value="true" ${p.remote_ok ? "selected" : ""}>Yes</option><option value="false" ${p.remote_ok ? "" : "selected"}>No</option></select></div>
  <div>${f("Minimum salary (LPA)", "min_salary_lpa", "number")}</div><div>${f("Expected CTC (LPA)", "expected_ctc_lpa", "number")}</div>
  <div><label>Target job titles (comma separated)</label><input type="text" id="p_target_titles" value="${esc((p.target_titles || []).join(", "))}"></div><div><label>Exclude companies (comma separated)</label><input type="text" id="p_excluded_companies" value="${esc((p.excluded_companies || []).join(", "))}"></div></div></div>
  <div class="card"><h2>Skills</h2><textarea id="p_skills" placeholder="react, typescript, sql">${esc((p.skills || []).join(", "))}</textarea></div>
  <div class="card"><h2>Experience</h2><div id="roles">${(p.experiences || []).map(roleBlock).join("")}</div><button class="btn alt" data-act="addrole" type="button">+ Add role</button></div>
  <div class="card"><h2>Education</h2><textarea id="p_education" placeholder="One per line, e.g. B.Tech Computer Science, 2020">${esc((p.education || []).map((e) => e.degree + (e.institution ? " - " + e.institution : "")).join("\n"))}</textarea></div>
  <div class="card"><h2>Links</h2><div class="grid g3"><div><label>LinkedIn</label><input type="text" id="l_linkedin" value="${esc((p.links || {}).linkedin)}"></div><div><label>GitHub</label><input type="text" id="l_github" value="${esc((p.links || {}).github)}"></div><div><label>Portfolio</label><input type="text" id="l_portfolio" value="${esc((p.links || {}).portfolio)}"></div></div></div>
  <button class="btn" data-act="saveprofile">Save profile</button>`;
}
const csv = (id) => document.getElementById(id).value.split(",").map((s) => s.trim()).filter(Boolean);
const num = (id) => { const v = document.getElementById(id).value; return v === "" ? null : Number(v); };
async function saveProfile() {
  const roles = [...document.querySelectorAll(".role")].map((r) => { const g = (k) => r.querySelector(`[data-k=${k}]`).value.trim(); return { title: g("title"), company: g("company"), start: g("start"), end: g("end"), bullets: g("bullets").split("\n").map((s) => s.trim()).filter(Boolean) }; }).filter((r) => r.title);
  const val = (id) => document.getElementById(id).value.trim();
  const p = { ...S.profile, name: val("p_name"), email: val("p_email"), phone: val("p_phone"), headline: val("p_headline"), summary: val("p_summary"), years_experience: num("p_years_experience") || 0, notice_days: num("p_notice_days"),
    locations: csv("p_locations"), remote_ok: val("p_remote_ok") === "true", min_salary_lpa: num("p_min_salary_lpa"), expected_ctc_lpa: num("p_expected_ctc_lpa"), target_titles: csv("p_target_titles"), excluded_companies: csv("p_excluded_companies"),
    skills: csv("p_skills"), experiences: roles, education: val("p_education").split("\n").map((s) => s.trim()).filter(Boolean).map((d) => ({ degree: d, institution: "", year: (d.match(/(19|20)\d{2}/) || [""])[0] })),
    links: { linkedin: val("l_linkedin"), github: val("l_github"), portfolio: val("l_portfolio") } };
  S.profile = await api("/profile", { method: "PUT", body: p }); toast("Profile saved ✓");
}

/* ---------- Sources & consent ---------- */
async function vSources() {
  const [cons, srcs, aud, meta] = await Promise.all([api("/consents"), api("/sources"), api("/audit"), api("/meta")]);
  return `<h1>Sources & Consent</h1><p class="muted">You decide what we may touch. Every grant is logged and can be revoked any time. We never ask for your Naukri/LinkedIn password and never log in to them for you.</p>
  <div class="card"><h2>Permissions</h2>${cons.map((c) => `<label class="row" style="margin:8px 0;color:var(--text)"><input type="checkbox" data-act="consent" data-scope="${esc(c.scope)}" ${c.granted ? "checked" : ""}> <span>${esc(c.description)}</span></label>`).join("")}</div>
  <div class="grid g2"><div class="card"><h2>1 · Try with sample jobs</h2><p class="small muted">29 fictional jobs (plus re-posted duplicates) so you can see matching, merging and analytics immediately.</p><button class="btn" data-act="sample">Load sample jobs</button></div>
  <div class="card"><h2>2 · Company job boards</h2><p class="small muted">Public Greenhouse / Lever / Ashby boards, or a career page that publishes JobPosting data.</p>
  <select id="src_kind">${meta.connectors.map((k) => `<option value="${k}">${k === "career_page" ? "career page URL" : k + " board name"}</option>`).join("")}</select><input type="text" id="src_ref" placeholder="e.g. stripe  (or https://company.com/careers)" style="margin-top:6px">
  <div class="row" style="margin-top:8px"><button class="btn" data-act="addsource">Fetch jobs</button><button class="btn alt" data-act="refresh">Refresh all</button></div></div>
  <div class="card"><h2>3 · Job-alert emails</h2><p class="small muted">Naukri, LinkedIn, Indeed, Foundit… already email you alerts. Export one as <b>.eml</b> and upload it. Needs the "alert emails" permission.</p><input type="file" id="emlfile" accept=".eml"></div></div>
  ${srcs.length ? `<div class="card"><h2>Watched sources</h2><table><tr><th>Type</th><th>Reference</th><th>Last run</th><th>Jobs</th></tr>${srcs.map((s) => `<tr><td>${esc(s.kind)}</td><td>${esc(s.ref)}</td><td>${esc(s.last_run)}</td><td>${s.last_count}</td></tr>`).join("")}</table></div>` : ""}
  <div class="card"><h2>4 · Browser extension (autofill)</h2><p class="small muted">Fills application forms on any site from your profile. It never presses Submit, and flags salary-type questions for you to answer. Load the <code>extension/</code> folder in Chrome (chrome://extensions → Developer mode → Load unpacked), then paste this code in its popup. The code can only read your autofill profile.</p>
  <div class="row"><button class="btn" data-act="exttoken">Generate extension code</button></div><div id="extcode" class="small" style="word-break:break-all;margin-top:8px"></div></div>
  <div class="card"><h2>Your data (DPDP-style controls)</h2><div class="row"><button class="btn alt" data-act="export">Download all my data</button><button class="btn danger" data-act="deleteaccount">Delete my account and data</button></div>
  <h3 style="margin-top:14px">Activity log</h3><div class="small muted" style="max-height:180px;overflow:auto">${aud.map((a) => `<div>${esc(a.at)} · ${esc(a.action)} ${esc(a.detail)}</div>`).join("") || "Nothing yet"}</div></div>`;
}

/* ---------- Applications ---------- */
async function vApps() {
  const apps = await api("/applications"), meta = await api("/meta");
  if (!apps.length) return `<h1>Applications</h1><div class="card muted">Nothing tracked yet. Open a job and press "Prepare to apply".</div>`;
  return `<h1>Applications</h1><div class="card" style="overflow:auto"><table><tr><th>Job</th><th>Match</th><th>Status</th><th>Follow-up</th><th>Notes</th></tr>${apps.map((a) => `<tr data-app="${a.id}"><td><b>${esc(a.title)}</b><div class="small muted">${esc(a.company)}</div></td><td>${a.score ?? ""}</td>
  <td><select data-act="appstatus">${meta.statuses.map((s) => `<option ${s === a.status ? "selected" : ""}>${s}</option>`).join("")}</select></td><td><input type="date" data-act="appfollow" value="${esc(a.follow_up || "")}"></td><td><input type="text" data-act="appnotes" value="${esc(a.notes || "")}" placeholder="Add note"></td></tr>`).join("")}</table></div>`;
}

/* ---------- Insights ---------- */
const hbars = (rows, label = "name", val = "count", suffix = "") => { const max = Math.max(1, ...rows.map((r) => r[val])); return rows.map((r) => `<div class="barrow"><span>${esc(r[label])}</span><div class="bar"><i style="width:${(r[val] / max) * 100}%"></i></div><span>${r[val]}${suffix}</span></div>`).join("") || `<p class="muted small">No data yet</p>`; };
async function vInsights() {
  const a = await api("/analytics"), m = a.market, p = a.personal;
  return `<h1>Insights</h1><div class="grid g3"><div class="card"><div class="muted small">Jobs tracked</div><h1>${m.total_jobs}</h1></div><div class="card"><div class="muted small">Good matches (60+)</div><h1>${p.good_matches}</h1></div>
  <div class="card"><div class="muted small">Filtered out by your rules</div><h1>${a.filtered_out_count}</h1></div><div class="card"><div class="muted small">Median pay in market</div><h1>${m.median_salary_lpa ?? "–"}<span class="small muted"> LPA</span></h1></div>
  <div class="card"><div class="muted small">Median pay of your good matches</div><h1>${p.median_pay_good_matches_lpa ?? "–"}<span class="small muted"> LPA</span></h1></div><div class="card"><div class="muted small">Time saved (est.)</div><h1>${Math.round(p.minutes_saved_estimate / 60 * 10) / 10}<span class="small muted"> hrs</span></h1></div></div>
  <div class="grid g2"><div class="card"><h2>Your skill gaps - learn these next</h2><p class="small muted">"Unlocks" = jobs where this is one of only 1-2 things you are missing.</p>${p.skill_gaps.map((g) => `<div class="barrow" style="grid-template-columns:1fr auto"><span><b>${esc(S.pretty[g.skill] || g.skill)}</b> <span class="chip">${esc(g.category)}</span></span><span class="small">needed by ${g.jobs_need_it} · unlocks ${g.jobs_it_would_unlock}</span></div>`).join("") || `<p class="muted small">Add skills and jobs to see gaps</p>`}</div>
  <div class="card"><h2>Your match quality</h2>${hbars(p.match_distribution)}</div>
  <div class="card"><h2>Most in-demand skills</h2>${m.top_skills.map((s) => `<div class="barrow"><span>${esc(S.pretty[s.name] || s.name)} ${s.you_have ? "✓" : ""}</span><div class="bar"><i style="width:${s.pct}%"></i></div><span>${s.pct}%</span></div>`).join("") || `<p class="muted small">No data yet</p>`}<p class="small muted">✓ = you have it</p></div>
  <div class="card"><h2>Where jobs are</h2>${hbars(m.by_city)}<p class="small muted">${m.remote_pct}% of listings are remote</p></div>
  <div class="card"><h2>Where jobs came from</h2>${hbars(m.by_source)}</div><div class="card"><h2>Freshness</h2>${hbars(m.freshness)}<h3 style="margin-top:12px">Experience asked</h3>${hbars(m.experience_bands)}</div>
  <div class="card"><h2>Your funnel</h2>${hbars(Object.entries(p.funnel).map(([name, count]) => ({ name, count })))}<p class="small muted">${p.response_rate_pct != null ? "Interview/offer rate: " + p.response_rate_pct + "%" : "Mark applications as applied to see your response rate"}</p></div></div>`;
}

/* ---------- events ---------- */
document.addEventListener("click", guard(async (ev) => {
  const el = ev.target.closest("[data-act]"); if (!el) return;
  if (el.dataset.act === "closedrawer" && ev.target.closest("[data-stop]") && el.classList.contains("drawer")) return;
  const act = el.dataset.act, id = el.dataset.id;
  switch (act) {
    case "nav": S.view = el.dataset.view; draw(); break;
    case "logout": logout(); break;
    case "authmode": ev.preventDefault(); drawAuth(el.dataset.mode); break;
    case "open": await openJob(id); break;
    case "closedrawer": document.getElementById("drawer").innerHTML = ""; break;
    case "cv": await tailorCv(id); break;
    case "apply": await prepareApply(id); break;
    case "dl": download(el.dataset.kind); break;
    case "fb": await api(`/jobs/${id}/feedback`, { method: "POST", body: { signal: el.dataset.sig } }); toast(el.dataset.sig === "skip" ? "Hidden from your list" : "Updated"); document.getElementById("drawer").innerHTML = ""; load(S.view); break;
    case "markapplied": { const apps = await api("/applications"); const a = apps.find((x) => x.job_id == id); await api("/applications/" + a.id, { method: "PATCH", body: { status: "applied" } }); toast("Marked as applied. Follow-up reminder set for 7 days."); break; }
    case "jobsapply": jobFilter = { q: document.getElementById("jq").value, min: Number(document.getElementById("jmin").value) || 0, filtered: document.getElementById("jfil").checked }; load("jobs"); break;
    case "addrole": document.getElementById("roles").insertAdjacentHTML("beforeend", roleBlock()); break;
    case "rmrole": el.closest(".role").remove(); break;
    case "saveprofile": await saveProfile(); break;
    case "sample": { const r = await api("/ingest/sample", { method: "POST" }); toast(`Added ${r.added} jobs, merged ${r.duplicates_merged} duplicates`); load("sources"); break; }
    case "addsource": { const r = await api("/ingest/source", { method: "POST", body: { kind: document.getElementById("src_kind").value, ref: document.getElementById("src_ref").value } }); toast(`Added ${r.added}, merged ${r.duplicates_merged}`); load("sources"); break; }
    case "refresh": { const r = await api("/ingest/refresh", { method: "POST" }); toast(`Refreshed ${r.length} source(s)`); load("sources"); break; }
    case "exttoken": { const r = await api("/extension-token", { method: "POST" }); document.getElementById("extcode").innerHTML = `Server: <code>${esc(location.origin)}</code><br>Code: <code>${esc(r.token)}</code>`; break; }
    case "export": { const d = await api("/export"); const a = document.createElement("a"); a.href = URL.createObjectURL(new Blob([JSON.stringify(d, null, 2)], { type: "application/json" })); a.download = "my-jobpilot-data.json"; a.click(); break; }
    case "deleteaccount": if (confirm("Permanently delete your account, profile, applications and logs? This cannot be undone.")) { await api("/account", { method: "DELETE" }); logout(); } break;
  }
}));
document.addEventListener("change", guard(async (ev) => {
  const el = ev.target, act = el.dataset.act;
  if (el.id === "cvfile" && el.files[0]) { const fd = new FormData(); fd.append("file", el.files[0]); const r = await api("/profile/upload", { method: "POST", body: fd }); toast(r.note); load("profile"); }
  if (el.id === "emlfile" && el.files[0]) { const fd = new FormData(); fd.append("file", el.files[0]); const r = await api("/ingest/email", { method: "POST", body: fd }); toast(`Added ${r.added} jobs from the email (${r.duplicates_merged} already known)`); load("sources"); }
  if (act === "consent") { await api("/consents", { method: "POST", body: { scope: el.dataset.scope, granted: el.checked } }); toast(el.checked ? "Permission granted" : "Permission revoked"); }
  const row = el.closest("[data-app]");
  if (row && act === "appstatus") await api("/applications/" + row.dataset.app, { method: "PATCH", body: { status: el.value } });
  if (row && act === "appfollow") await api("/applications/" + row.dataset.app, { method: "PATCH", body: { follow_up: el.value } });
  if (row && act === "appnotes") await api("/applications/" + row.dataset.app, { method: "PATCH", body: { notes: el.value } });
}));
document.addEventListener("submit", guard(async (ev) => {
  if (ev.target.id !== "authform") return;
  ev.preventDefault();
  const f = new FormData(ev.target), mode = ev.target.dataset.mode;
  try { const r = await fetch("/api/" + mode, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ email: f.get("email"), password: f.get("password") }) }); const d = await r.json(); if (!r.ok) throw new Error(d.detail || "Failed"); S.token = d.token; localStorage.setItem("jp_token", d.token); draw(); }
  catch (e) { drawAuth(mode, e.message); }
}));
draw();

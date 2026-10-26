"use strict";
const main = document.getElementById("main");
const token = localStorage.getItem("jp_token") || "";
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const qs = new URLSearchParams(location.search);

function toast(msg) {
  document.querySelectorAll(".toast").forEach((t) => t.remove());
  const t = document.createElement("div"); t.className = "toast"; t.textContent = msg; document.body.appendChild(t); setTimeout(() => t.remove(), 4000);
}
async function api(path, opts = {}) {
  const res = await fetch("/api" + path, { ...opts, headers: { Authorization: "Bearer " + token, ...(opts.body ? { "Content-Type": "application/json" } : {}) }, body: opts.body ? JSON.stringify(opts.body) : undefined });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(typeof data.detail === "string" ? data.detail : "Something went wrong");
  return data;
}
const run = (fn) => async (...a) => { try { await fn(...a); } catch (e) { toast(e.message); } };

async function draw() {
  if (!token) { main.innerHTML = `<div class="card"><h2>Please log in first</h2><p class="muted">Gmail import is tied to your JobPilot account.</p><a class="btn" href="/" style="text-decoration:none">Go to login</a></div>`; return; }
  let st;
  try { st = await api("/gmail/status"); } catch (e) { main.innerHTML = `<div class="card"><p class="err">${esc(e.message)}</p><a href="/">Log in again</a></div>`; return; }
  const acc = st.account;
  const banner = qs.get("error") ? `<div class="card" style="border-color:var(--bad)"><span class="err">${esc(qs.get("error"))}</span></div>` : qs.get("connected") ? `<div class="card" style="border-color:var(--good)"><span class="ok">✓ Gmail connected. Press "Import now" to pull your latest job alerts.</span></div>` : "";
  main.innerHTML = `<h1>📧 Gmail auto-import</h1><p class="muted">Your Naukri, LinkedIn, Indeed and other alert emails turn into one clean, de-duplicated job list - without you logging in to any portal.</p>${banner}
  ${!st.configured ? `<div class="card" style="border-color:var(--warn)"><h3>⚠ Not set up on this server yet</h3><p class="small">The server needs Google credentials (<code>GOOGLE_CLIENT_ID</code>, <code>GOOGLE_CLIENT_SECRET</code>). See <code>docs/GMAIL_SETUP.md</code>. Authorised redirect URI to register: <code>${esc(st.redirect_uri)}</code></p></div>` : ""}
  <div class="grid g2">
  <div class="card"><h2>Exactly what we read</h2><ul class="small">
    <li>Only emails from job-portal senders: ${st.reads_only_from.map((s) => `<span class="chip">${esc(s)}</span>`).join("")}</li>
    <li>Only the last 14 days (3 days for automatic runs), at most 100 emails per run.</li>
    <li>We keep <b>only the job details</b> (title, company, city, link). The email text itself is never stored.</li>
    <li>Access is <b>read-only</b>. We cannot send, delete or change anything in your mailbox.</li>
    <li>Google does not offer a "these senders only" permission, so this limit is enforced by our search query: <code>${esc(st.query)}</code></li>
    <li>Your Google token is stored encrypted. Disconnecting revokes it at Google and deletes our copy.</li></ul></div>
  <div class="card"><h2>Connection</h2>${acc ? `
    <p><span class="chip good">Connected</span> <b>${esc(acc.email || "your Gmail")}</b></p>
    <p class="small muted">Since ${esc(acc.connected_at)} · Last import: ${esc(acc.last_sync || "never")}${acc.last_sync ? ` (${acc.last_added} new job${acc.last_added === 1 ? "" : "s"})` : ""}</p>
    ${acc.last_error ? `<p class="err small">${esc(acc.last_error)}</p>` : ""}
    <label class="row" style="color:var(--text)"><input type="checkbox" id="auto" ${acc.auto_sync ? "checked" : ""}> Import automatically every hour</label>
    <div class="row" style="margin-top:10px"><button class="btn" id="sync">Import now</button><button class="btn danger" id="disc">Disconnect Gmail</button></div><div id="result" class="small" style="margin-top:10px"></div>` : `
    ${st.consent ? "" : `<p class="small">First allow JobPilot to read alert emails:</p><button class="btn alt" id="consent">✓ I allow reading job-alert emails</button><br><br>`}
    <button class="btn" id="connect" ${st.consent && st.configured ? "" : "disabled"}>Connect Gmail (read-only)</button>
    <p class="small muted">You will be sent to Google to approve. You can remove access any time at myaccount.google.com/permissions.</p>`}</div></div>
  <div class="card"><h3>Heads-up</h3><p class="small muted">Alert emails list job titles but not full descriptions, so skills for those jobs are guessed from the title. Open the original job link for the full text. Jobs found here are private to you.</p></div>`;
  const on = (id, fn) => { const el = document.getElementById(id); if (el) el.onclick = run(fn); };
  on("consent", async () => { await api("/consents", { method: "POST", body: { scope: "email_alerts", granted: true } }); draw(); });
  on("connect", async () => { const r = await api("/gmail/connect", { method: "POST" }); location.href = r.url; });
  on("sync", async () => {
    const b = document.getElementById("sync"); b.disabled = true; b.textContent = "Importing…";
    try { const r = await api("/gmail/sync", { method: "POST" });
      document.getElementById("result").innerHTML = `Checked ${r.messages_checked} new email(s) · ${r.messages_with_jobs} had jobs · <b>${r.added} new job(s)</b> added, ${r.duplicates_merged} already known. <a href="/">See them in JobPilot</a>`;
    } finally { b.disabled = false; b.textContent = "Import now"; }
  });
  on("disc", async () => { if (!confirm("Disconnect Gmail? We will revoke access at Google and delete the stored token. Jobs already imported stay.")) return; await api("/gmail/disconnect", { method: "POST" }); history.replaceState({}, "", "/gmail"); draw(); });
  const auto = document.getElementById("auto"); if (auto) auto.onchange = run(async () => { await api("/gmail/auto", { method: "POST", body: { enabled: auto.checked } }); toast(auto.checked ? "Automatic import on" : "Automatic import off"); });
}
draw();

const $ = (id) => document.getElementById(id), msg = (t) => ($("msg").textContent = t);
chrome.storage.local.get(["server", "code"], (v) => { $("server").value = v.server || ""; $("code").value = v.code || ""; if (v.server && v.code) $("setup").style.display = "none"; });
$("save").onclick = async () => {
  const server = $("server").value.trim().replace(/\/$/, ""), code = $("code").value.trim();
  try {
    const origin = new URL(server).origin + "/*";
    if (!(await chrome.permissions.request({ origins: [origin] }))) return msg("Permission to reach your JobPilot server was declined.");
    chrome.storage.local.set({ server: new URL(server).origin, code }, () => { msg("Saved ✓"); $("setup").style.display = "none"; });
  } catch { msg("Enter a valid server URL."); }
};
$("fill").onclick = async () => {
  const { server, code } = await chrome.storage.local.get(["server", "code"]);
  if (!server || !code) { $("setup").style.display = "block"; return msg("Connect to JobPilot first."); }
  try {
    const r = await fetch(server + "/api/autofill", { headers: { Authorization: "Bearer " + code } });
    if (r.status === 403) return msg("Autofill permission is off. Turn it on in JobPilot → Sources & Consent.");
    if (!r.ok) return msg("Could not load your profile (code expired? generate a new one).");
    const profile = await r.json();
    const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
    await chrome.scripting.executeScript({ target: { tabId: tab.id }, files: ["filler.js"] });
    const [res] = await chrome.scripting.executeScript({ target: { tabId: tab.id }, func: (p) => window.__jobpilotFill(p), args: [profile] });
    const rep = res.result;
    msg(`Filled ${rep.filled.length} field(s) ✓ (green).\n` + (rep.needs_you.length ? `Answer yourself (orange): ${rep.needs_you.join("; ")}\n` : "") + (rep.skipped_files ? "Attach your tailored CV manually.\n" : "") + "Check everything, then press Submit.");
  } catch (e) { msg("Error: " + e.message); }
};

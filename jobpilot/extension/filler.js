/* Injected into the page the user is looking at, only when they press "Fill this form".
 * Fills fields from the profile, highlights what it filled, flags sensitive questions for the user.
 * It never clicks submit and never uploads files. */
(function (root) {
  const RULES = [
    ["email", /e-?mail/],
    ["phone", /(phone|mobile|contact\s*(no|number)|tel\b|whatsapp)/],
    ["first_name", /(first\s*name|given\s*name|fname)/],
    ["last_name", /(last\s*name|sur\s*name|family\s*name|lname)/],
    ["full_name", /(full\s*name|your\s*name|candidate\s*name|^name$|\bname\b)/],
    ["linkedin", /linked\s*in/],
    ["github", /git\s*hub/],
    ["portfolio", /(portfolio|website|personal\s*site)/],
    ["current_company", /(current|present|recent)\s*(company|employer|organi[sz]ation)/],
    ["current_title", /(current|present|recent)\s*(title|designation|role|position)|job\s*title|designation/],
    ["years_experience", /(total|years?\s*of|overall)\s*(work\s*)?experience|experience\s*\(?(in\s*)?years|\byoe\b/],
    ["notice_days", /notice\s*period|joining\s*time|how\s*soon\s*can\s*you\s*join/],
    ["work_authorization", /(work|employment)\s*(authori[sz]ation|permit|eligib)|authori[sz]ed\s*to\s*work|legally\s*(authori[sz]ed|allowed|eligible)|right\s*to\s*work|visa/],
    ["location", /(current\s*)?(city|location|address|where\s*are\s*you\s*based)/],
    ["summary", /(cover\s*letter|about\s*you|summary|tell\s*us\s*about|why\s*(do\s*you|are\s*you)\s*want)/],
    ["skills", /(key\s*)?skills|technolog(y|ies)/],
  ];
  // Never auto-filled; the user must answer these themselves.
  const SENSITIVE = /(ctc|salary|compensation|expected\s*pay|current\s*pay|gender|sex\b|disabilit|caste|religion|veteran|race|ethnic|date\s*of\s*birth|\bdob\b|aadhaar|aadhar|\bpan\b|passport|marital|consent|agree|terms|declaration|criminal|background\s*check)/;

  const clean = (s) => String(s || "").toLowerCase().replace(/[_\-*:]+/g, " ").replace(/\s+/g, " ").trim();

  function labelOf(el) {
    const bits = [];
    if (el.id) { const l = document.querySelector(`label[for="${CSS.escape(el.id)}"]`); if (l) bits.push(l.textContent); }
    const wrap = el.closest("label"); if (wrap) bits.push(wrap.textContent);
    const lb = el.getAttribute("aria-labelledby");
    if (lb) lb.split(/\s+/).forEach((id) => { const n = document.getElementById(id); if (n) bits.push(n.textContent); });
    bits.push(el.getAttribute("aria-label"), el.placeholder, el.name, el.id, el.title);
    return clean(bits.filter(Boolean).join(" "));
  }

  function classify(el) {
    const ac = (el.getAttribute("autocomplete") || "").toLowerCase();
    const acMap = { email: "email", tel: "phone", "given-name": "first_name", "family-name": "last_name", name: "full_name", "organization-title": "current_title", organization: "current_company" };
    const text = labelOf(el);
    if (SENSITIVE.test(text)) return { key: null, sensitive: true, text };
    if (acMap[ac]) return { key: acMap[ac], text };
    if (el.type === "email") return { key: "email", text };
    if (el.type === "tel") return { key: "phone", text };
    for (const [key, re] of RULES) if (re.test(text)) return { key, text };
    return { key: null, text };
  }

  function setValue(el, value) {
    if (el.tagName === "SELECT") {
      const v = clean(value);
      const opt = [...el.options].find((o) => clean(o.textContent) === v || clean(o.value) === v) || [...el.options].find((o) => v && clean(o.textContent).includes(v));
      if (!opt) return false;
      el.value = opt.value;
    } else {
      const proto = el.tagName === "TEXTAREA" ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
      Object.getOwnPropertyDescriptor(proto, "value").set.call(el, value); // works with React-style controlled inputs
    }
    el.dispatchEvent(new Event("input", { bubbles: true }));
    el.dispatchEvent(new Event("change", { bubbles: true }));
    return true;
  }

  function mark(el, color, tip) { el.style.outline = `2px solid ${color}`; el.style.outlineOffset = "1px"; el.title = tip; }

  function fill(profile) {
    const fields = profile.fields || {}, answers = profile.screening_answers || {};
    const report = { filled: [], needs_you: [], skipped_files: 0 };
    const els = [...document.querySelectorAll("input, textarea, select")].filter((el) => {
      if (["hidden", "submit", "button", "image", "reset", "checkbox", "radio", "password"].includes(el.type)) return false;
      if (el.type === "file") { report.skipped_files++; return false; }
      const r = el.getBoundingClientRect(); return !el.disabled && !el.readOnly && (r.width > 0 || r.height > 0);
    });
    for (const el of els) {
      // never overwrite what the user already typed/chose (a dropdown still on its first option counts as untouched)
      if (el.tagName === "SELECT" ? el.selectedIndex > 0 : el.value && el.value.trim()) continue;
      const { key, sensitive, text } = classify(el);
      if (sensitive) { mark(el, "#f59e0b", "JobPilot: please answer this yourself"); report.needs_you.push(text.slice(0, 60)); continue; }
      const saved = Object.entries(answers).find(([q]) => text && clean(q) && text.includes(clean(q)));
      const value = saved ? saved[1] : key ? fields[key] : "";
      if (!value) { if (el.required) { mark(el, "#f59e0b", "JobPilot: required, no saved answer"); report.needs_you.push(text.slice(0, 60)); } continue; }
      if (setValue(el, String(value))) { mark(el, "#16a34a", "Filled by JobPilot - please check"); report.filled.push(key || "saved answer"); }
    }
    return report;
  }

  root.__jobpilotFill = fill;
  root.__jobpilotClassify = classify;
})(typeof window !== "undefined" ? window : globalThis);

// Dev check: node extension/test_filler.js  (needs playwright)
const { chromium } = require("playwright"), path = require("path"), fs = require("fs"), assert = require("assert");
(async () => {
  const b = await chromium.launch(), p = await b.newPage();
  await p.goto("file://" + path.resolve(__dirname, "test_form.html"));
  await p.addScriptTag({ content: fs.readFileSync(path.join(__dirname, "filler.js"), "utf8") });
  const profile = { fields: { full_name: "Rahul Sharma", first_name: "Rahul", email: "rahul@mail.com", phone: "+91 98765 43210", current_company: "Acme Tech", years_experience: "6.2",
    notice_days: "30", linkedin: "https://linkedin.com/in/rahul", location: "Gurugram", summary: "Frontend developer.", work_authorization: "Indian citizen" }, screening_answers: {} };
  const rep = await p.evaluate((pr) => window.__jobpilotFill(pr), profile);
  const v = (id) => p.$eval("#" + id, (e) => e.value);
  assert.equal(await v("a"), "Rahul Sharma"); assert.equal(await p.$eval("[name=em]", (e) => e.value), "rahul@mail.com");
  assert.equal(await p.$eval("[name=mobile_number]", (e) => e.value), "+91 98765 43210"); assert.equal(await v("fn"), "Rahul");
  assert.equal(await v("co"), "Acme Tech"); assert.equal(await v("yoe"), "6.2"); assert.equal(await v("np"), "30");
  assert.equal(await v("li"), "https://linkedin.com/in/rahul"); assert.equal(await v("wa"), "Indian citizen"); assert.equal(await v("why"), "Frontend developer.");
  assert.equal(await v("loc"), "Mumbai", "must not overwrite what the user typed");
  assert.equal(await v("ex"), "", "salary must never be auto-filled"); assert.equal(await v("g"), "Male", "gender must never be changed");
  assert.equal(await v("x"), "", "unknown fields stay empty"); assert.equal(await v("cv"), "");
  assert.equal(await p.evaluate(() => window.__submitted === true), false, "must never submit");
  assert(rep.needs_you.some((t) => /ctc/.test(t)) && rep.needs_you.some((t) => /gender/.test(t)) && rep.skipped_files === 1);
  console.log("filler OK:", JSON.stringify(rep)); await b.close();
})().catch((e) => { console.error("FAIL", e.message); process.exit(1); });

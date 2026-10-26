"""Insert normalized jobs with cross-source de-duplication."""
import json

from .db import db, jload
from .normalize import fingerprint, normalize


def upsert_jobs(raws: list[dict], source: str, user_id: int | None = None, private: bool = False) -> dict:
    added = merged = skipped = 0
    with db() as c:
        for raw in raws:
            src = raw.get("_source", source)
            job = normalize(raw, src)
            if not job:
                skipped += 1
                continue
            fp = fingerprint(job["title"], job["company"], job["location"])
            row = c.execute("SELECT id, sources, description, url, skills FROM jobs WHERE fingerprint=?", (fp,)).fetchone()
            if row:
                srcs = set(jload(row["sources"], []))
                srcs.add(src)
                # keep the richer description / a url if we lacked one
                desc = job["description"] if len(job["description"]) > len(row["description"] or "") else row["description"]
                skills = json.dumps(job["skills"]) if desc == job["description"] else row["skills"]
                if private and user_id:
                    c.execute("INSERT OR IGNORE INTO job_owners(job_id,user_id) VALUES(?,?)", (row["id"], user_id))
                c.execute("UPDATE jobs SET sources=?, description=?, skills=?, url=COALESCE(url,?), last_seen=CURRENT_TIMESTAMP, added_by=CASE WHEN ? THEN added_by ELSE NULL END WHERE id=?",
                          (json.dumps(sorted(srcs)), desc, skills, job["url"], int(private), row["id"]))
                merged += 1
            else:
                c.execute(
                    """INSERT INTO jobs(fingerprint,title,company,location,remote,description,skills,url,apply_email,
                       salary_min,salary_max,exp_min,exp_max,posted_at,sources,added_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                    (fp, job["title"], job["company"], job["location"], job["remote"], job["description"],
                     json.dumps(job["skills"]), job["url"], job["apply_email"], job["salary_min"], job["salary_max"],
                     job["exp_min"], job["exp_max"], job["posted_at"], json.dumps([src]), user_id if private else None))
                if private and user_id:
                    jid = c.execute("SELECT id FROM jobs WHERE fingerprint=?", (fp,)).fetchone()["id"]
                    c.execute("INSERT OR IGNORE INTO job_owners(job_id,user_id) VALUES(?,?)", (jid, user_id))
                added += 1
    return {"added": added, "duplicates_merged": merged, "skipped": skipped, "total_seen": len(raws)}

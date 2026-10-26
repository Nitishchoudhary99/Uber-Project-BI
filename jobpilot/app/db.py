import json
import os
import sqlite3
import threading
from contextlib import contextmanager

DB_PATH = os.environ.get("JOBPILOT_DB", os.path.join(os.path.dirname(__file__), "..", "data", "jobpilot.db"))
_lock = threading.Lock()

SCHEMA = """
CREATE TABLE IF NOT EXISTS users(
  id INTEGER PRIMARY KEY, email TEXT UNIQUE NOT NULL, pw_hash TEXT NOT NULL, salt TEXT NOT NULL,
  created_at TEXT DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE IF NOT EXISTS sessions(
  token_hash TEXT PRIMARY KEY, user_id INTEGER NOT NULL, scope TEXT NOT NULL DEFAULT 'full', created_at TEXT DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE IF NOT EXISTS profiles(user_id INTEGER PRIMARY KEY, data TEXT NOT NULL, updated_at TEXT DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE IF NOT EXISTS consents(
  id INTEGER PRIMARY KEY, user_id INTEGER NOT NULL, scope TEXT NOT NULL, granted INTEGER NOT NULL,
  at TEXT DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE IF NOT EXISTS audit(
  id INTEGER PRIMARY KEY, user_id INTEGER, action TEXT, detail TEXT, at TEXT DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE IF NOT EXISTS jobs(
  id INTEGER PRIMARY KEY, fingerprint TEXT UNIQUE NOT NULL, title TEXT, company TEXT, location TEXT,
  remote INTEGER DEFAULT 0, description TEXT, skills TEXT, url TEXT, apply_email TEXT,
  salary_min REAL, salary_max REAL, exp_min REAL, exp_max REAL, posted_at TEXT,
  sources TEXT, first_seen TEXT DEFAULT CURRENT_TIMESTAMP, last_seen TEXT DEFAULT CURRENT_TIMESTAMP,
  added_by INTEGER);
CREATE TABLE IF NOT EXISTS applications(
  id INTEGER PRIMARY KEY, user_id INTEGER NOT NULL, job_id INTEGER NOT NULL, status TEXT NOT NULL,
  method TEXT, notes TEXT, score REAL, cv_html TEXT, created_at TEXT DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT DEFAULT CURRENT_TIMESTAMP, follow_up TEXT, UNIQUE(user_id, job_id));
CREATE TABLE IF NOT EXISTS feedback(
  user_id INTEGER, job_id INTEGER, signal TEXT, at TEXT DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(user_id, job_id));
CREATE TABLE IF NOT EXISTS job_owners(job_id INTEGER NOT NULL, user_id INTEGER NOT NULL, PRIMARY KEY(job_id,user_id));
CREATE TABLE IF NOT EXISTS watch_sources(
  id INTEGER PRIMARY KEY, user_id INTEGER NOT NULL, kind TEXT NOT NULL, ref TEXT NOT NULL,
  last_run TEXT, last_count INTEGER, UNIQUE(user_id, kind, ref));
"""


def connect():
    path = os.path.abspath(DB_PATH)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    c = sqlite3.connect(path, check_same_thread=False)
    c.row_factory = sqlite3.Row
    c.execute("PRAGMA foreign_keys=ON")
    return c


@contextmanager
def db():
    with _lock:
        c = connect()
        try:
            yield c
            c.commit()
        finally:
            c.close()


def init():
    with db() as c:
        c.executescript(SCHEMA)


def audit(c, user_id, action, detail=""):
    c.execute("INSERT INTO audit(user_id,action,detail) VALUES(?,?,?)", (user_id, action, detail[:500]))


def jload(s, default=None):
    try:
        return json.loads(s) if s else default
    except (TypeError, ValueError):
        return default

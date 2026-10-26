import os
import tempfile

_tmp = tempfile.mkdtemp()
os.environ["JOBPILOT_DB"] = os.path.join(_tmp, "test.db")

import pytest
from fastapi.testclient import TestClient

from app.main import app


@pytest.fixture()
def client():
    return TestClient(app)


def make_user(client, email):
    r = client.post("/api/signup", json={"email": email, "password": "password123"})
    assert r.status_code == 200, r.text
    return {"Authorization": "Bearer " + r.json()["token"]}


CV = """Rahul Sharma
rahul@mail.com | +91 98765 43210
Summary
Frontend developer focused on fast, accessible UIs.
Experience
Frontend Developer - Acme Tech, Jan 2022 - Present
• Built dashboards in React and TypeScript used by 20k users
• Cut page load time by 40% with code splitting
• Organised the office cricket tournament
• Wrote Jest unit tests for shared components
• Mentored two interns on Git workflow
• Maintained Redux state for checkout flow
Web Developer - Beta Labs, Jun 2020 - Dec 2021
• Developed REST APIs using Node.js and MySQL
Skills
React, TypeScript, Node.js, MySQL, Git, Redux, HTML, CSS
Education
B.Tech Computer Science, 2020
"""

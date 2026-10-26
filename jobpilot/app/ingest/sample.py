"""Fictional demo jobs so the product can be explored with zero setup. Clearly labelled source 'sample'."""
from datetime import datetime, timedelta, timezone

_J = [
    # title, company, location, remote, exp, (lpa lo, hi), skills-sentence
    ("Frontend Developer", "Orbit Commerce", "Gurugram, Haryana", 0, "2-5 years", (12, 20), "React, TypeScript, Redux, HTML, CSS, REST APIs and performance optimisation. Git, Jest testing."),
    ("Senior React Engineer", "Lumen Pay", "Remote", 1, "4-8 years", (22, 35), "React, Next.js, TypeScript, GraphQL, CI/CD and mentoring juniors. AWS exposure is a plus."),
    ("Full Stack Developer", "Kiteworks Labs", "Bengaluru, Karnataka", 0, "3-6 years", (16, 28), "Node.js, React, PostgreSQL, Docker, microservices, REST APIs, Agile delivery."),
    ("Backend Engineer (Python)", "Gridline Systems", "Pune, Maharashtra", 0, "3-7 years", (18, 30), "Python, FastAPI, PostgreSQL, Redis, Kafka, Docker, Kubernetes, unit testing."),
    ("Data Analyst", "Meridian Retail", "Mumbai, Maharashtra", 0, "1-4 years", (7, 12), "SQL, Excel, Power BI, data analysis, statistics, stakeholder management and communication."),
    ("Senior Data Analyst", "Quanta Health", "Hyderabad, Telangana", 0, "4-7 years", (14, 22), "SQL, Python, pandas, Tableau, Power BI, A/B testing and statistics, ETL."),
    ("Business Intelligence Developer", "Northwind Foods", "Delhi", 0, "3-6 years", (12, 20), "Power BI, DAX, SQL, ETL, data modelling, Excel and stakeholder communication."),
    ("Machine Learning Engineer", "Neuron Works", "Bengaluru, Karnataka", 0, "3-6 years", (25, 45), "Python, PyTorch, machine learning, deep learning, NLP, AWS, Docker and MLOps."),
    ("Data Engineer", "Streamline Data", "Remote", 1, "3-6 years", (20, 34), "Python, Spark, Airflow, Kafka, SQL, AWS, ETL data pipelines."),
    ("DevOps Engineer", "Cloudhaven", "Noida, Uttar Pradesh", 0, "3-6 years", (14, 24), "AWS, Docker, Kubernetes, Terraform, CI/CD, Linux, Git."),
    ("Site Reliability Engineer", "Pulse Networks", "Bengaluru, Karnataka", 0, "4-8 years", (24, 40), "Kubernetes, Terraform, Linux, Python, AWS, Kafka, CI/CD, incident response."),
    ("Java Backend Developer", "Meridian Bank Tech", "Mumbai, Maharashtra", 0, "4-8 years", (15, 26), "Java, Spring Boot, microservices, MySQL, Kafka, REST APIs, JUnit."),
    ("Android Developer", "Swift Rides", "Gurugram, Haryana", 0, "2-5 years", (10, 18), "Kotlin, Android, REST APIs, Git, unit testing and Agile."),
    ("Flutter Developer", "Appify Studio", "Remote", 1, "2-4 years", (9, 16), "Flutter, Dart, Android, iOS, REST APIs, Git and Firebase."),
    ("QA Automation Engineer", "TestNest", "Chennai, Tamil Nadu", 0, "2-5 years", (8, 15), "Selenium, Java, test automation, CI/CD, Jira, API testing and Agile."),
    ("UI/UX Designer", "Pixelforge", "Bengaluru, Karnataka", 0, "2-5 years", (10, 18), "Figma, UI/UX, wireframing, user research, design systems, HTML and CSS awareness."),
    ("Product Manager", "Lumen Pay", "Bengaluru, Karnataka", 0, "4-8 years", (28, 45), "Product management, roadmap, Agile, SQL, stakeholder management, data analysis and leadership."),
    ("Digital Marketing Executive", "BrightLeaf Media", "Delhi", 0, "1-3 years", (4, 7), "SEO, Google Ads, social media marketing, content marketing and analytics."),
    ("SEO Specialist", "BrightLeaf Media", "Remote", 1, "2-4 years", (6, 10), "SEO, content marketing, analytics, Excel and communication."),
    ("Sales Executive (B2B SaaS)", "Kiteworks Labs", "Gurugram, Haryana", 0, "1-4 years", (6, 12), "B2B sales, lead generation, CRM, Salesforce, communication and presentation."),
    ("Customer Support Associate", "Swift Rides", "Noida, Uttar Pradesh", 0, "0-2 years", (3, 5), "Customer support, communication, CRM and Excel basics."),
    ("Accounts Executive", "Northwind Foods", "Delhi", 0, "2-5 years", (4, 8), "Accounting, Tally, GST, accounts payable, Excel and bookkeeping."),
    ("Financial Analyst", "Quanta Health", "Mumbai, Maharashtra", 0, "2-5 years", (9, 16), "Financial modeling, Excel, SQL, Power BI, valuation and communication."),
    ("HR Recruiter", "TalentBridge", "Pune, Maharashtra", 0, "1-4 years", (4, 8), "Recruitment, talent acquisition, sourcing, communication and HR operations."),
    ("Frontend Intern", "Orbit Commerce", "Gurugram, Haryana", 0, "0-1 years", (2, 4), "HTML, CSS, JavaScript, React basics and Git."),
    ("Software Engineer - Fresher", "Gridline Systems", "Pune, Maharashtra", 0, "0-2 years", (5, 9), "Java or Python, SQL, REST APIs, Git and strong fundamentals."),
    ("Engineering Manager", "Pulse Networks", "Bengaluru, Karnataka", 0, "8-12 years", (45, 70), "Leadership, people management, microservices, AWS, Agile, Kubernetes and stakeholder management."),
    ("Full Stack Developer (MERN)", "Appify Studio", "Remote", 1, "2-5 years", (10, 18), "MongoDB, Express, React, Node.js, TypeScript, REST APIs, Git and Docker."),
    ("Junior Python Developer", "Gridline Systems", "Remote", 1, "1-3 years", (6, 11), "Python, Django, SQL, REST APIs, Git and unit testing."),
]


def jobs() -> list[dict]:
    now = datetime.now(timezone.utc)
    out = []
    for i, (title, company, loc, remote, exp, (lo, hi), skills) in enumerate(_J):
        out.append({
            "title": title, "company": company, "location": loc, "remote": bool(remote),
            "description": f"{company} is hiring a {title}.\nExperience: {exp}.\nSalary: {lo}-{hi} LPA.\n"
                           f"What you'll use: {skills}\nYou will own features end to end, collaborate with the team and ship reliably.",
            "url": f"https://example.com/jobs/{i + 1}", "posted_at": (now - timedelta(days=(i * 3) % 40)).isoformat(),
        })
    return out


def duplicate_variants() -> list[dict]:
    """The same roles re-posted on other portals with different formatting, to show cross-source de-duplication."""
    base = jobs()
    out = []
    for j in base[:8]:
        d = dict(j)
        d["company"] = j["company"] + " Pvt Ltd"
        d["title"] = j["title"].replace("Developer", "Developer ").strip()
        d["url"] = j["url"].replace("example.com/jobs", "example.org/listing")
        out.append(d)
    return out

"""Skill taxonomy + extraction. Canonical skill -> (category, aliases)."""
import re

_RAW = {
    # languages
    "python": ("Language", ["python", "py3"]),
    "java": ("Language", ["java"]),
    "javascript": ("Language", ["javascript", "js", "es6", "ecmascript"]),
    "typescript": ("Language", ["typescript", "ts"]),
    "c++": ("Language", ["c++", "cpp"]),
    "c#": ("Language", ["c#", "csharp"]),
    "go": ("Language", ["golang"]),
    "rust": ("Language", ["rust"]),
    "php": ("Language", ["php"]),
    "ruby": ("Language", ["ruby", "rails", "ruby on rails"]),
    "kotlin": ("Language", ["kotlin"]),
    "swift": ("Language", ["swift", "swiftui"]),
    "scala": ("Language", ["scala"]),
    "sql": ("Data", ["sql", "t-sql", "pl/sql", "plsql"]),
    # web
    "react": ("Frontend", ["react", "react.js", "reactjs", "react native"]),
    "angular": ("Frontend", ["angular", "angularjs"]),
    "vue": ("Frontend", ["vue", "vue.js", "vuejs"]),
    "next.js": ("Frontend", ["next.js", "nextjs"]),
    "html": ("Frontend", ["html", "html5"]),
    "css": ("Frontend", ["css", "css3", "sass", "scss", "tailwind"]),
    "redux": ("Frontend", ["redux"]),
    "node.js": ("Backend", ["node.js", "nodejs", "node", "express", "express.js"]),
    "django": ("Backend", ["django"]),
    "flask": ("Backend", ["flask"]),
    "fastapi": ("Backend", ["fastapi"]),
    "spring": ("Backend", ["spring", "spring boot", "springboot"]),
    ".net": ("Backend", [".net", "dotnet", "asp.net"]),
    "graphql": ("Backend", ["graphql"]),
    "rest api": ("Backend", ["rest", "rest api", "restful", "rest apis"]),
    "microservices": ("Backend", ["microservices", "microservice"]),
    # data
    "postgresql": ("Data", ["postgresql", "postgres"]),
    "mysql": ("Data", ["mysql"]),
    "mongodb": ("Data", ["mongodb", "mongo"]),
    "redis": ("Data", ["redis"]),
    "kafka": ("Data", ["kafka"]),
    "spark": ("Data", ["spark", "pyspark"]),
    "hadoop": ("Data", ["hadoop"]),
    "airflow": ("Data", ["airflow"]),
    "etl": ("Data", ["etl", "elt", "data pipelines", "data pipeline"]),
    "power bi": ("Analytics", ["power bi", "powerbi", "dax"]),
    "tableau": ("Analytics", ["tableau"]),
    "excel": ("Analytics", ["excel", "advanced excel", "vlookup"]),
    "data analysis": ("Analytics", ["data analysis", "data analytics", "analytics"]),
    "statistics": ("Analytics", ["statistics", "statistical"]),
    "pandas": ("Analytics", ["pandas", "numpy"]),
    # ml
    "machine learning": ("ML", ["machine learning", "ml"]),
    "deep learning": ("ML", ["deep learning", "neural networks"]),
    "nlp": ("ML", ["nlp", "natural language processing"]),
    "pytorch": ("ML", ["pytorch"]),
    "tensorflow": ("ML", ["tensorflow", "keras"]),
    "scikit-learn": ("ML", ["scikit-learn", "sklearn"]),
    "llm": ("ML", ["llm", "llms", "generative ai", "genai", "langchain"]),
    # devops/cloud
    "aws": ("Cloud", ["aws", "amazon web services", "ec2", "s3", "lambda"]),
    "azure": ("Cloud", ["azure"]),
    "gcp": ("Cloud", ["gcp", "google cloud"]),
    "docker": ("DevOps", ["docker", "containers"]),
    "kubernetes": ("DevOps", ["kubernetes", "k8s"]),
    "terraform": ("DevOps", ["terraform"]),
    "ci/cd": ("DevOps", ["ci/cd", "cicd", "jenkins", "github actions", "gitlab ci"]),
    "linux": ("DevOps", ["linux", "unix", "bash", "shell scripting"]),
    "git": ("Tools", ["git", "github", "gitlab"]),
    # mobile / qa
    "android": ("Mobile", ["android"]),
    "ios": ("Mobile", ["ios"]),
    "flutter": ("Mobile", ["flutter", "dart"]),
    "selenium": ("QA", ["selenium"]),
    "testing": ("QA", ["unit testing", "test automation", "pytest", "junit", "jest", "qa", "manual testing"]),
    # product / design / business
    "figma": ("Design", ["figma"]),
    "ui/ux": ("Design", ["ui/ux", "ux", "ui design", "user research", "wireframing"]),
    "product management": ("Product", ["product management", "product manager", "roadmap", "prd"]),
    "agile": ("Process", ["agile", "scrum", "kanban", "jira"]),
    "seo": ("Marketing", ["seo", "sem", "google ads"]),
    "digital marketing": ("Marketing", ["digital marketing", "social media marketing", "content marketing"]),
    "sales": ("Business", ["sales", "lead generation", "b2b sales", "business development"]),
    "customer support": ("Business", ["customer support", "customer service", "crm", "salesforce"]),
    "accounting": ("Finance", ["accounting", "tally", "gst", "bookkeeping", "accounts payable"]),
    "financial modeling": ("Finance", ["financial modeling", "financial modelling", "valuation"]),
    "recruitment": ("HR", ["recruitment", "talent acquisition", "sourcing", "hr operations"]),
    "communication": ("Soft", ["communication", "stakeholder management", "presentation"]),
    "leadership": ("Soft", ["leadership", "team lead", "mentoring", "people management"]),
}

SKILLS = {k: v[0] for k, v in _RAW.items()}


def _pattern(alias: str) -> re.Pattern:
    return re.compile(r"(?<![\w+#.])" + re.escape(alias) + r"(?![\w+#]|\.\w)", re.I)


# canonical names that are also ordinary English words: match only via explicit aliases
_NO_SELF_ALIAS = {"go"}
# short acronyms: only count when written upper-case (JS, ML, QA...)
_UPPER_ONLY = {"ml", "ts", "js", "ux", "sem", "qa", "ui", "py3", "es6"}
# plain words: only count when the surrounding text looks technical
_CONTEXT_ONLY = {"rest", "node", "spring", "swift", "rust", "ruby", "react", "angular", "vue", "express", "containers", "sourcing", "roadmap"}
_TECH_HINTS = ("api", "framework", "developer", "engineer", "stack", "experience", "skills", "backend",
               "frontend", "programming", "library", "tools", "technologies", ",", "/", "|", "•")

_PATTERNS = []
for _canon, (_cat, _aliases) in _RAW.items():
    _names = set(_aliases) | (set() if _canon in _NO_SELF_ALIAS else {_canon})
    for _a in _names:
        _PATTERNS.append((_canon, _a.lower(), _pattern(_a)))


def _technical_context(text: str, m: re.Match) -> bool:
    window = text[max(0, m.start() - 50): m.end() + 50].lower()
    return any(h in window for h in _TECH_HINTS)


def extract_skills(text: str) -> list[str]:
    """Return canonical skills found in text, ordered by first appearance."""
    if not text:
        return []
    found: dict[str, int] = {}
    for canon, alias, pat in _PATTERNS:
        for m in pat.finditer(text):
            token = m.group(0)
            if alias in _UPPER_ONLY and not token.isupper():
                continue
            if alias in _CONTEXT_ONLY and not _technical_context(text, m):
                continue
            if canon not in found or m.start() < found[canon]:
                found[canon] = m.start()
    return sorted(found, key=found.get)


def category(skill: str) -> str:
    return SKILLS.get(skill, "Other")


_PRETTY = {"sql": "SQL", "aws": "AWS", "gcp": "GCP", "etl": "ETL", "html": "HTML", "css": "CSS", "php": "PHP", "nlp": "NLP", "llm": "LLM",
           "ios": "iOS", "seo": "SEO", "ci/cd": "CI/CD", "ui/ux": "UI/UX", "rest api": "REST APIs", "node.js": "Node.js", "next.js": "Next.js",
           "typescript": "TypeScript", "javascript": "JavaScript", "postgresql": "PostgreSQL", "mysql": "MySQL", "mongodb": "MongoDB",
           "fastapi": "FastAPI", "graphql": "GraphQL", "power bi": "Power BI", "scikit-learn": "scikit-learn", "pytorch": "PyTorch",
           "tensorflow": "TensorFlow", "c++": "C++", "c#": "C#", ".net": ".NET", "devops": "DevOps", "github": "GitHub", "gst": "GST",
           "crm": "CRM", "qa": "QA", "kafka": "Kafka", "redis": "Redis", "pandas": "pandas", "excel": "Excel", "figma": "Figma"}


def pretty(skill: str) -> str:
    return _PRETTY.get(skill, skill.title() if skill in SKILLS else skill)


def pretty_map() -> dict[str, str]:
    return {k: pretty(k) for k in SKILLS}

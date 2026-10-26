"""Polite, SSRF-safe fetching: http(s) only, no private addresses, honours robots.txt."""
import ipaddress
import socket
from urllib.parse import urlparse
from urllib.robotparser import RobotFileParser

import requests

UA = "JobPilotBot/0.1 (+user-consented personal job search)"
MAX_BYTES = 3_000_000


class FetchError(Exception):
    pass


def _check_host(url: str):
    p = urlparse(url)
    if p.scheme not in ("http", "https") or not p.hostname:
        raise FetchError("Only http(s) URLs are allowed")
    try:
        infos = socket.getaddrinfo(p.hostname, None)
    except socket.gaierror as e:
        raise FetchError(f"Cannot resolve {p.hostname}") from e
    for info in infos:
        ip = ipaddress.ip_address(info[4][0])
        if ip.is_private or ip.is_loopback or ip.is_link_local or ip.is_reserved or ip.is_multicast:
            raise FetchError("Private or internal addresses are not allowed")


def allowed_by_robots(url: str) -> bool:
    p = urlparse(url)
    rp = RobotFileParser()
    try:
        _check_host(f"{p.scheme}://{p.hostname}/robots.txt")
        r = requests.get(f"{p.scheme}://{p.netloc}/robots.txt", headers={"User-Agent": UA}, timeout=8)
        if r.status_code >= 400:
            return True
        rp.parse(r.text.splitlines())
        return rp.can_fetch(UA, url)
    except Exception:
        return True


def get(url: str, *, robots: bool = True, accept="text/html,application/json") -> requests.Response:
    _check_host(url)
    if robots and not allowed_by_robots(url):
        raise FetchError("robots.txt disallows fetching this page")
    r = requests.get(url, headers={"User-Agent": UA, "Accept": accept}, timeout=15, stream=True, allow_redirects=False)
    for _ in range(3):  # follow a few redirects, re-checking each hop
        if r.is_redirect and r.headers.get("Location"):
            nxt = requests.compat.urljoin(url, r.headers["Location"])
            _check_host(nxt)
            url = nxt
            r = requests.get(url, headers={"User-Agent": UA, "Accept": accept}, timeout=15, stream=True, allow_redirects=False)
        else:
            break
    r.raise_for_status()
    body = r.raw.read(MAX_BYTES + 1, decode_content=True)
    if len(body) > MAX_BYTES:
        raise FetchError("Response too large")
    r._content = body
    return r

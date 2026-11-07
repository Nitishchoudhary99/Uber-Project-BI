#!/usr/bin/env node
// PocketHost gateway: the single front door for everything the phone serves.
//
//   /health               -> always 200 (used for readiness checks)
//   /pockethost/status    -> JSON status (local callers, or external with API key)
//   /ollama/*             -> Ollama API (API key required from the internet)
//   /webhook*/ /form*/    -> n8n webhooks & forms (public, that is their purpose)
//   everything else       -> n8n editor (API key required from the internet;
//                            open https://<url>/?key=<KEY> once to set a cookie)
//
// No dependencies: runs on the Node.js that Termux ships.
'use strict';

const http = require('http');
const net = require('net');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const HOME = process.env.HOME || '.';
const STATE_DIR = process.env.PH_STATE || path.join(HOME, '.pockethost');
const RUN_DIR = path.join(STATE_DIR, 'run');
const PORT = parseInt(process.env.GATEWAY_PORT || '8080', 10);
const OLLAMA_PORT = parseInt(process.env.OLLAMA_PORT || '11434', 10);
const N8N_PORT = parseInt(process.env.N8N_PORT || '5678', 10);
const API_KEY = process.env.API_KEY || '';
const VERSION = process.env.PH_VERSION || 'dev';
const COOKIE = 'ph_key';
const STARTED = Date.now();
const PUBLIC_N8N = /^\/(webhook|webhook-test|webhook-waiting|form|form-test|form-waiting)\//;

// ---------- helpers ----------

function readTrim(file) {
  try { return fs.readFileSync(file, 'utf8').trim() || null; } catch { return null; }
}

function pidOf(name) {
  const pid = parseInt(readTrim(path.join(RUN_DIR, `${name}.pid`)) || '', 10);
  if (!pid) return null;
  try { process.kill(pid, 0); return pid; } catch { return null; }
}

function readConfig() {
  const out = {};
  const raw = readTrim(path.join(STATE_DIR, 'config.env')) || '';
  for (const line of raw.split('\n')) {
    const m = line.match(/^([A-Z0-9_]+)=(.*)$/);
    if (!m) continue;
    let v = m[2];
    if (/^'.*'$/.test(v)) v = v.slice(1, -1);
    else v = v.replace(/\\(.)/g, '$1');
    out[m[1]] = v;
  }
  return out;
}

function isLoopback(addr) {
  return addr === '127.0.0.1' || addr === '::1' || addr === '::ffff:127.0.0.1';
}

// Requests that arrive through Cloudflare carry cf-connecting-ip (set by the
// edge, a client cannot remove it). cloudflared itself connects from loopback,
// so the socket address alone is not enough.
function isExternal(req) {
  return Boolean(req.headers['cf-connecting-ip'] || req.headers['x-forwarded-for']) ||
    !isLoopback(req.socket.remoteAddress);
}

function safeEqual(a, b) {
  const A = Buffer.from(String(a));
  const B = Buffer.from(String(b));
  return A.length === B.length && crypto.timingSafeEqual(A, B);
}

function cookies(req) {
  const out = {};
  for (const part of (req.headers.cookie || '').split(';')) {
    const i = part.indexOf('=');
    if (i > 0) out[part.slice(0, i).trim()] = decodeURIComponent(part.slice(i + 1).trim());
  }
  return out;
}

function presentedKey(req, url) {
  const auth = req.headers.authorization || '';
  if (/^Bearer\s+/i.test(auth)) return auth.replace(/^Bearer\s+/i, '').trim();
  if (req.headers['x-api-key']) return String(req.headers['x-api-key']).trim();
  if (url.searchParams.get('key')) return url.searchParams.get('key');
  return cookies(req)[COOKIE] || '';
}

function authorized(req, url) {
  if (!isExternal(req)) return true;
  if (!API_KEY) return false; // never expose anything unauthenticated
  return safeEqual(presentedKey(req, url), API_KEY);
}

function sendJson(res, code, obj, extra = {}) {
  res.writeHead(code, { 'content-type': 'application/json', 'cache-control': 'no-store', ...extra });
  res.end(JSON.stringify(obj));
}

const CORS = {
  'access-control-allow-origin': '*',
  'access-control-allow-headers': 'authorization, content-type, x-api-key',
  'access-control-allow-methods': 'GET, POST, PUT, DELETE, OPTIONS',
};

function loginPage(res) {
  res.writeHead(401, { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' });
  res.end(`<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<title>PocketHost</title><style>
body{font-family:system-ui,sans-serif;background:#0b0f14;color:#e6edf3;display:grid;place-items:center;min-height:100vh;margin:0;padding:16px;box-sizing:border-box}
form{background:#141a22;padding:28px;border-radius:16px;max-width:360px;width:100%}
h1{margin:0 0 6px;font-size:22px}p{color:#9aa7b4;margin:0 0 18px;font-size:14px}
input,button{width:100%;box-sizing:border-box;padding:12px;border-radius:10px;border:1px solid #2a3441;font-size:15px}
input{background:#0b0f14;color:#e6edf3;margin-bottom:12px}button{background:#7c9cff;color:#0b1020;border:0;font-weight:600}
</style></head><body><form method="get" action="/"><h1>&#9889; PocketHost</h1>
<p>This server runs on a phone. Enter the API key shown in the PocketHost app.</p>
<input name="key" type="password" placeholder="API key" autofocus required><button>Unlock</button></form></body></html>`);
}

function status(includeSecrets) {
  const cfg = readConfig();
  const svc = (name, port) => {
    const pid = pidOf(name);
    return { running: Boolean(pid), pid, port: port || null };
  };
  return {
    name: 'pockethost',
    version: VERSION,
    uptimeSec: Math.round((Date.now() - STARTED) / 1000),
    publicUrl: readTrim(path.join(STATE_DIR, 'public_url')),
    apiKey: includeSecrets ? API_KEY : undefined,
    services: {
      gateway: { running: true, pid: process.pid, port: PORT },
      ollama: svc('ollama', OLLAMA_PORT),
      n8n: svc('n8n', N8N_PORT),
      tunnel: svc('tunnel'),
    },
    config: {
      TUNNEL: cfg.TUNNEL || 'quick',
      ENABLE_OLLAMA: cfg.ENABLE_OLLAMA || '1',
      ENABLE_N8N: cfg.ENABLE_N8N || '1',
      PUBLIC_URL: cfg.PUBLIC_URL || '',
      HAS_TUNNEL_TOKEN: cfg.CF_TUNNEL_TOKEN ? '1' : '0',
      BOOT: fs.existsSync(path.join(HOME, '.termux', 'boot', 'pockethost')) ? '1' : '0',
    },
  };
}

// ---------- proxy ----------

function proxy(req, res, { port, name, pathname, rewriteHost, cors }) {
  const headers = { ...req.headers };
  delete headers['x-api-key'];
  if (rewriteHost) {
    // Ollama only accepts loopback Host/Origin when it is bound to loopback.
    headers.host = `127.0.0.1:${port}`;
    delete headers.origin;
    delete headers.referer;
    delete headers.authorization;
    delete headers.cookie;
  } else {
    headers['x-forwarded-host'] = req.headers.host || '';
    headers['x-forwarded-proto'] = isExternal(req) ? 'https' : 'http';
  }
  const upstream = http.request(
    { host: '127.0.0.1', port, method: req.method, path: pathname, headers },
    (up) => {
      const h = { ...up.headers, ...(cors ? CORS : {}) };
      res.writeHead(up.statusCode || 502, h);
      up.pipe(res);
    },
  );
  upstream.on('error', () => {
    if (!res.headersSent) sendJson(res, 502, { error: `${name} is not running on this phone right now` });
    else res.destroy();
  });
  res.on('close', () => { if (!res.writableFinished) upstream.destroy(); });
  req.pipe(upstream);
}

// ---------- server ----------

const server = http.createServer((req, res) => {
  let url;
  try { url = new URL(req.url, 'http://local'); } catch { return sendJson(res, 400, { error: 'bad url' }); }
  const p = url.pathname;

  if (p === '/health') return sendJson(res, 200, { ok: true, version: VERSION });

  if (p === '/pockethost/status') {
    if (!authorized(req, url)) return sendJson(res, 401, { error: 'API key required' });
    return sendJson(res, 200, status(!isExternal(req)));
  }

  if (p === '/ollama' || p.startsWith('/ollama/')) {
    if (req.method === 'OPTIONS') { res.writeHead(204, CORS); return res.end(); }
    if (!authorized(req, url)) {
      return sendJson(res, 401, { error: 'Missing or wrong API key. Send: Authorization: Bearer <key>' }, CORS);
    }
    url.searchParams.delete('key');
    const rest = (p.slice('/ollama'.length) || '/') + (url.search || '');
    return proxy(req, res, { port: OLLAMA_PORT, name: 'Ollama', pathname: rest, rewriteHost: true, cors: true });
  }

  if (PUBLIC_N8N.test(p)) {
    return proxy(req, res, { port: N8N_PORT, name: 'n8n', pathname: req.url });
  }

  if (!authorized(req, url)) {
    const wantsHtml = (req.headers.accept || '').includes('text/html');
    return wantsHtml ? loginPage(res) : sendJson(res, 401, { error: 'API key required' });
  }

  // First visit with ?key=... -> remember it in a cookie and drop it from the URL.
  if (isExternal(req) && url.searchParams.has('key') && req.method === 'GET') {
    url.searchParams.delete('key');
    res.writeHead(302, {
      location: url.pathname + (url.search || ''),
      'set-cookie': `${COOKIE}=${encodeURIComponent(API_KEY)}; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=2592000`,
    });
    return res.end();
  }

  return proxy(req, res, { port: N8N_PORT, name: 'n8n', pathname: req.url });
});

// WebSockets (n8n editor live updates)
server.on('upgrade', (req, socket, head) => {
  let url;
  try { url = new URL(req.url, 'http://local'); } catch { return socket.destroy(); }
  if (!PUBLIC_N8N.test(url.pathname) && !authorized(req, url)) {
    socket.end('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n');
    return;
  }
  const up = net.connect(N8N_PORT, '127.0.0.1', () => {
    let raw = `${req.method} ${req.url} HTTP/${req.httpVersion}\r\n`;
    for (let i = 0; i < req.rawHeaders.length; i += 2) raw += `${req.rawHeaders[i]}: ${req.rawHeaders[i + 1]}\r\n`;
    up.write(raw + '\r\n');
    if (head && head.length) up.write(head);
    socket.pipe(up);
    up.pipe(socket);
  });
  const kill = () => { socket.destroy(); up.destroy(); };
  up.on('error', kill);
  socket.on('error', kill);
  up.on('close', kill);
  socket.on('close', kill);
});

server.keepAliveTimeout = 65000;
server.requestTimeout = 0; // model downloads and long generations stream for minutes
server.listen(PORT, '127.0.0.1', () => {
  console.log(`[gateway] PocketHost ${VERSION} listening on 127.0.0.1:${PORT}`);
  if (!API_KEY) console.log('[gateway] WARNING: no API_KEY set, all external requests will be refused');
});

for (const sig of ['SIGTERM', 'SIGINT']) process.on(sig, () => server.close(() => process.exit(0)));

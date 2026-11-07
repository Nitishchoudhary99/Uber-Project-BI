#!/usr/bin/env bash
# PocketHost CLI - runs Ollama, n8n, a secure gateway and a Cloudflare tunnel
# on an Android phone inside Termux.
#
#   pockethost start [service]     start everything (or one of: ollama gateway tunnel n8n)
#   pockethost stop [service]      stop everything (or one service)
#   pockethost restart [service]
#   pockethost status [--json]
#   pockethost url | key | rotate-key
#   pockethost pull <model> | rm <model> | models
#   pockethost logs <service> [-f]
#   pockethost config [set KEY VALUE]
#   pockethost boot on|off         auto start when the phone boots (needs Termux:Boot)
#   pockethost doctor
set -uo pipefail

PH_VERSION="1.0.0"
PH_DIR="${PH_DIR:-$HOME/.pockethost}"
RUN="$PH_DIR/run"
LOGS="$PH_DIR/logs"
CONF="$PH_DIR/config.env"
URL_FILE="$PH_DIR/public_url"
mkdir -p "$RUN" "$LOGS"

# ---- defaults (overridden by config.env) ----
API_KEY=""
GATEWAY_PORT=8080
OLLAMA_PORT=11434
N8N_PORT=5678
ENABLE_OLLAMA=1
ENABLE_N8N=1
TUNNEL=quick            # quick | token | none
CF_TUNNEL_TOKEN=""
PUBLIC_URL=""
OLLAMA_KEEP_ALIVE=5m
OLLAMA_NUM_PARALLEL=1
OLLAMA_MAX_LOADED_MODELS=1
# shellcheck disable=SC1090
[ -f "$CONF" ] && . "$CONF"

SERVICES=(ollama gateway tunnel n8n)

# ---- output ----
if [ -t 1 ]; then B=$'\033[1m'; G=$'\033[32m'; Y=$'\033[33m'; R=$'\033[31m'; C=$'\033[36m'; N=$'\033[0m'
else B=""; G=""; Y=""; R=""; C=""; N=""; fi
ok()   { printf '%s✔%s %s\n' "$G" "$N" "$*"; }
info() { printf '%s•%s %s\n' "$C" "$N" "$*"; }
warn() { printf '%s!%s %s\n' "$Y" "$N" "$*"; }
err()  { printf '%s✘%s %s\n' "$R" "$N" "$*" >&2; }
die()  { err "$*"; exit 1; }

# ---- process helpers ----
pidfile() { echo "$RUN/$1.pid"; }
pid_of() { local p; p=$(cat "$(pidfile "$1")" 2>/dev/null) || return 1; [ -n "$p" ] && kill -0 "$p" 2>/dev/null && echo "$p"; }
running() { pid_of "$1" >/dev/null; }

rotate_log() {
  local f="$LOGS/$1.log"
  if [ -f "$f" ] && [ "$(wc -c <"$f")" -gt 5000000 ]; then mv -f "$f" "$f.1"; fi
}

spawn() {
  local name=$1; shift
  if running "$name"; then ok "$name already running (pid $(pid_of "$name"))"; return 0; fi
  rotate_log "$name"
  printf '\n===== %s start =====\n' "$(date '+%F %T')" >>"$LOGS/$name.log"
  nohup "$@" >>"$LOGS/$name.log" 2>&1 </dev/null &
  echo $! >"$(pidfile "$name")"
}

stop_one() {
  local name=$1 p i
  p=$(pid_of "$name") || { rm -f "$(pidfile "$name")"; return 0; }
  kill "$p" 2>/dev/null
  for ((i = 0; i < 30; i++)); do kill -0 "$p" 2>/dev/null || break; sleep 0.5; done
  kill -0 "$p" 2>/dev/null && kill -9 "$p" 2>/dev/null
  rm -f "$(pidfile "$name")"
  ok "stopped $name"
}

wait_http() { # url seconds
  local i
  for ((i = 0; i < $2 * 2; i++)); do
    curl -fsS -o /dev/null --max-time 2 "$1" 2>/dev/null && return 0
    sleep 0.5
  done
  return 1
}

need() { command -v "$1" >/dev/null 2>&1; }

gen_key() { printf 'ph_%s' "$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')"; }

set_conf() { # KEY VALUE
  touch "$CONF"
  grep -v "^$1=" "$CONF" >"$CONF.tmp" 2>/dev/null || true
  printf '%s=%q\n' "$1" "$2" >>"$CONF.tmp"
  mv -f "$CONF.tmp" "$CONF"
  chmod 600 "$CONF"
  printf -v "$1" '%s' "$2"
}

ensure_key() {
  if [ -z "$API_KEY" ]; then set_conf API_KEY "$(gen_key)"; info "generated a new API key"; fi
}

valid_model() { [[ "$1" =~ ^[A-Za-z0-9._:/-]+$ ]] || die "invalid model name: $1"; }

# ---- services ----
start_ollama() {
  [ "$ENABLE_OLLAMA" = 1 ] || { info "ollama disabled"; return 0; }
  need ollama || { err "ollama is not installed (pkg install ollama)"; return 1; }
  spawn ollama env OLLAMA_HOST="127.0.0.1:$OLLAMA_PORT" OLLAMA_KEEP_ALIVE="$OLLAMA_KEEP_ALIVE" \
    OLLAMA_NUM_PARALLEL="$OLLAMA_NUM_PARALLEL" OLLAMA_MAX_LOADED_MODELS="$OLLAMA_MAX_LOADED_MODELS" \
    ollama serve
  if wait_http "http://127.0.0.1:$OLLAMA_PORT/api/version" 40; then ok "ollama ready on :$OLLAMA_PORT"
  else err "ollama did not become ready, see: pockethost logs ollama"; return 1; fi
}

start_gateway() {
  need node || { err "node is not installed (pkg install nodejs-lts)"; return 1; }
  [ -f "$PH_DIR/gateway.js" ] || { err "gateway.js missing, reinstall PocketHost"; return 1; }
  ensure_key
  spawn gateway env GATEWAY_PORT="$GATEWAY_PORT" OLLAMA_PORT="$OLLAMA_PORT" N8N_PORT="$N8N_PORT" \
    API_KEY="$API_KEY" PH_VERSION="$PH_VERSION" PH_STATE="$PH_DIR" node "$PH_DIR/gateway.js"
  if wait_http "http://127.0.0.1:$GATEWAY_PORT/health" 15; then ok "gateway ready on :$GATEWAY_PORT"
  else err "gateway did not start, see: pockethost logs gateway"; return 1; fi
}

start_tunnel() {
  case "$TUNNEL" in
  none)
    rm -f "$URL_FILE"; info "tunnel disabled (local only)"; return 0 ;;
  token)
    [ -n "$CF_TUNNEL_TOKEN" ] || { err "TUNNEL=token but CF_TUNNEL_TOKEN is empty"; return 1; }
    need cloudflared || { err "cloudflared is not installed (pkg install cloudflared)"; return 1; }
    spawn tunnel env TUNNEL_TOKEN="$CF_TUNNEL_TOKEN" cloudflared tunnel --no-autoupdate --protocol http2 run
    if [ -n "$PUBLIC_URL" ]; then echo "${PUBLIC_URL%/}" >"$URL_FILE"; ok "tunnel up: ${PUBLIC_URL%/}"
    else rm -f "$URL_FILE"; warn "tunnel running; set PUBLIC_URL so the app can show your domain"; fi ;;
  quick | *)
    need cloudflared || { err "cloudflared is not installed (pkg install cloudflared)"; return 1; }
    if running tunnel && [ -s "$URL_FILE" ]; then ok "tunnel already running: $(cat "$URL_FILE")"; return 0; fi
    stop_one tunnel >/dev/null
    rm -f "$URL_FILE"
    : >"$LOGS/tunnel.log"
    spawn tunnel cloudflared tunnel --no-autoupdate --protocol http2 --url "http://127.0.0.1:$GATEWAY_PORT"
    local i url=""
    for ((i = 0; i < 60; i++)); do
      url=$(grep -oE 'https://[a-z0-9-]+\.trycloudflare\.com' "$LOGS/tunnel.log" | head -n1)
      [ -n "$url" ] && break
      running tunnel || break
      sleep 1
    done
    if [ -n "$url" ]; then echo "$url" >"$URL_FILE"; ok "public URL: $url"
    else err "could not open a quick tunnel, see: pockethost logs tunnel"; return 1; fi ;;
  esac
}

start_n8n() {
  [ "$ENABLE_N8N" = 1 ] || { info "n8n disabled"; return 0; }
  need n8n || { warn "n8n is not installed; skipping (reinstall from the app to add it)"; return 0; }
  local base envs
  base=$(cat "$URL_FILE" 2>/dev/null || true)
  envs=(N8N_PORT="$N8N_PORT" N8N_LISTEN_ADDRESS=127.0.0.1 N8N_SECURE_COOKIE=false
    N8N_DIAGNOSTICS_ENABLED=false N8N_VERSION_NOTIFICATIONS_ENABLED=false
    N8N_PERSONALIZATION_ENABLED=false N8N_HIRING_BANNER_ENABLED=false
    N8N_ENFORCE_SETTINGS_FILE_PERMISSIONS=true N8N_PROXY_HOPS=1)
  if [ -n "$base" ]; then
    envs+=(WEBHOOK_URL="$base/" N8N_EDITOR_BASE_URL="$base/" N8N_PROTOCOL=https N8N_HOST="${base#https://}")
  fi
  spawn n8n env "${envs[@]}" n8n start
  info "n8n is booting (phones need 30-120s)..."
  if wait_http "http://127.0.0.1:$N8N_PORT/healthz" 180; then ok "n8n ready on :$N8N_PORT"
  else warn "n8n still starting; check: pockethost logs n8n"; fi
}

start_svc() {
  case "$1" in
  ollama) start_ollama ;; gateway) start_gateway ;; tunnel) start_tunnel ;; n8n) start_n8n ;;
  *) die "unknown service: $1 (use: ${SERVICES[*]})" ;;
  esac
}

# ---- commands ----
cmd_start() {
  if [ $# -gt 0 ]; then start_svc "$1"; return; fi
  need termux-wake-lock && termux-wake-lock
  printf '%s⚡ Starting PocketHost %s%s\n' "$B" "$PH_VERSION" "$N"
  start_ollama || true
  start_gateway || die "gateway failed; nothing is reachable without it"
  start_tunnel || true
  start_n8n || true
  echo
  cmd_status
}

cmd_stop() {
  if [ $# -gt 0 ]; then stop_one "$1"; [ "$1" = tunnel ] && rm -f "$URL_FILE"; return 0; fi
  local s
  for s in n8n tunnel gateway ollama; do stop_one "$s"; done
  rm -f "$URL_FILE"
  need termux-wake-unlock && termux-wake-unlock
  ok "PocketHost stopped"
}

cmd_restart() {
  if [ $# -gt 0 ]; then
    stop_one "$1"
    # n8n bakes the public URL in at boot, so it follows the tunnel
    if [ "$1" = tunnel ]; then start_tunnel; running n8n && { stop_one n8n; start_n8n; }; else start_svc "$1"; fi
    return
  fi
  cmd_stop; cmd_start
}

json_svc() { local p; p=$(pid_of "$1" || true); printf '"%s":{"running":%s,"pid":%s}' "$1" "$([ -n "$p" ] && echo true || echo false)" "${p:-null}"; }

cmd_status() {
  if [ "${1:-}" = --json ]; then
    local url; url=$(cat "$URL_FILE" 2>/dev/null || true)
    printf '{"version":"%s","publicUrl":%s,"services":{%s,%s,%s,%s}}\n' "$PH_VERSION" \
      "$([ -n "$url" ] && printf '"%s"' "$url" || echo null)" \
      "$(json_svc ollama)" "$(json_svc gateway)" "$(json_svc tunnel)" "$(json_svc n8n)"
    return
  fi
  local s p
  printf '%sPocketHost %s%s\n' "$B" "$PH_VERSION" "$N"
  for s in "${SERVICES[@]}"; do
    if p=$(pid_of "$s"); then printf '  %s●%s %-8s running (pid %s)\n' "$G" "$N" "$s" "$p"
    else printf '  %s○%s %-8s stopped\n' "$R" "$N" "$s"; fi
  done
  if [ -s "$URL_FILE" ]; then
    local u; u=$(cat "$URL_FILE")
    printf '\n  Public URL : %s\n  Ollama API : %s/ollama  (OpenAI: %s/ollama/v1)\n  n8n editor : %s/?key=<API key>\n  Webhooks   : %s/webhook/...\n' "$u" "$u" "$u" "$u" "$u"
  else
    printf '\n  Local only : http://127.0.0.1:%s\n' "$GATEWAY_PORT"
  fi
}

cmd_logs() {
  local s=${1:-}; [ -n "$s" ] || die "usage: pockethost logs <${SERVICES[*]// /|}> [-f]"
  [ -f "$LOGS/$s.log" ] || die "no log for $s yet"
  if [ "${2:-}" = -f ]; then tail -n 200 -f "$LOGS/$s.log"; else tail -n 200 "$LOGS/$s.log"; fi
}

with_ollama() { running ollama || start_ollama || die "ollama is not running"; }

cmd_pull()   { [ $# -ge 1 ] || die "usage: pockethost pull <model>"; valid_model "$1"; with_ollama; OLLAMA_HOST="127.0.0.1:$OLLAMA_PORT" ollama pull "$1"; }
cmd_rm()     { [ $# -ge 1 ] || die "usage: pockethost rm <model>"; valid_model "$1"; with_ollama; OLLAMA_HOST="127.0.0.1:$OLLAMA_PORT" ollama rm "$1"; }
cmd_models() { with_ollama; OLLAMA_HOST="127.0.0.1:$OLLAMA_PORT" ollama list; }

CONF_KEYS="TUNNEL CF_TUNNEL_TOKEN PUBLIC_URL ENABLE_OLLAMA ENABLE_N8N OLLAMA_KEEP_ALIVE OLLAMA_NUM_PARALLEL OLLAMA_MAX_LOADED_MODELS"

cmd_config() {
  if [ "${1:-}" = set ]; then
    [ $# -eq 3 ] || die "usage: pockethost config set KEY VALUE"
    local k=$2 v=$3
    [[ " $CONF_KEYS " == *" $k "* ]] || die "unknown key $k (allowed: $CONF_KEYS)"
    case "$k" in
    TUNNEL) [[ "$v" =~ ^(quick|token|none)$ ]] || die "TUNNEL must be quick, token or none" ;;
    ENABLE_*) [[ "$v" =~ ^[01]$ ]] || die "$k must be 0 or 1" ;;
    OLLAMA_NUM_PARALLEL | OLLAMA_MAX_LOADED_MODELS) [[ "$v" =~ ^[0-9]+$ ]] || die "$k must be a number" ;;
    PUBLIC_URL) [ -z "$v" ] || [[ "$v" =~ ^https://[A-Za-z0-9.-]+/?$ ]] || die "PUBLIC_URL must look like https://ai.example.com" ;;
    esac
    set_conf "$k" "$v"
    ok "$k updated (run: pockethost restart)"
    return
  fi
  local k
  for k in $CONF_KEYS; do
    if [ "$k" = CF_TUNNEL_TOKEN ] && [ -n "$CF_TUNNEL_TOKEN" ]; then echo "$k=********"; else echo "$k=${!k}"; fi
  done
}

cmd_boot() {
  local f="$HOME/.termux/boot/pockethost"
  case "${1:-}" in
  on)
    mkdir -p "$HOME/.termux/boot"
    printf '#!%s/bin/sh\ntermux-wake-lock\n%s/bin/pockethost start >>%s/boot.log 2>&1\n' "${PREFIX:-/usr}" "${PREFIX:-/usr}" "$LOGS" >"$f"
    chmod 700 "$f"; ok "auto-start on boot enabled (install the Termux:Boot app and open it once)" ;;
  off) rm -f "$f"; ok "auto-start on boot disabled" ;;
  *) [ -f "$f" ] && echo on || echo off ;;
  esac
}

cmd_rotate_key() {
  set_conf API_KEY "$(gen_key)"
  ok "new API key: $API_KEY"
  if running gateway; then stop_one gateway; start_gateway; fi
}

cmd_doctor() {
  printf '%sPocketHost doctor%s\n' "$B" "$N"
  local t
  for t in node ollama cloudflared n8n curl; do
    if need "$t"; then ok "$t: $(command -v "$t")"; else warn "$t: missing"; fi
  done
  local mem_total mem_avail
  mem_total=$(awk '/MemTotal/{print int($2/1024)}' /proc/meminfo 2>/dev/null)
  mem_avail=$(awk '/MemAvailable/{print int($2/1024)}' /proc/meminfo 2>/dev/null)
  info "RAM: ${mem_avail:-?} MB free of ${mem_total:-?} MB"
  if [ -n "${mem_total:-}" ]; then
    if [ "$mem_total" -lt 5000 ]; then info "  best models: gemma3:1b, qwen2.5:0.5b, qwen2.5:1.5b"
    elif [ "$mem_total" -lt 9000 ]; then info "  best models: llama3.2:3b, qwen2.5:3b, gemma3:1b"
    else info "  best models: gemma3:4b, phi4-mini, qwen3:4b (7B q4 works, slowly)"; fi
  fi
  info "storage: $(df -h "$HOME" 2>/dev/null | awk 'NR==2{print $4" free"}')"
  if grep -qs '^allow-external-apps *= *true' "$HOME/.termux/termux.properties"; then ok "app control allowed (allow-external-apps=true)"
  else warn "allow-external-apps is off: the PocketHost app cannot control Termux"; fi
  info "auto-start on boot: $(cmd_boot)"
  info "Android 12+ may kill background processes. If services die randomly, run from a PC once:"
  echo "    adb shell \"settings put global settings_enable_monitor_phantom_procs false\""
  info "Also set Termux battery usage to 'Unrestricted' in Android settings."
}

usage() { sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'; }

cmd=${1:-help}; shift || true
case "$cmd" in
start) cmd_start "$@" ;;
stop) cmd_stop "$@" ;;
restart) cmd_restart "$@" ;;
status) cmd_status "$@" ;;
logs) cmd_logs "$@" ;;
pull) cmd_pull "$@" ;;
rm) cmd_rm "$@" ;;
models) cmd_models ;;
url) cat "$URL_FILE" 2>/dev/null || die "no public URL (server stopped or TUNNEL=none)" ;;
key) ensure_key; echo "$API_KEY" ;;
rotate-key) cmd_rotate_key ;;
config) cmd_config "$@" ;;
boot) cmd_boot "$@" ;;
doctor) cmd_doctor ;;
version | --version | -v) echo "$PH_VERSION" ;;
help | -h | --help) usage ;;
*) err "unknown command: $cmd"; usage; exit 1 ;;
esac

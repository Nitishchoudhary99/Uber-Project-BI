#!/usr/bin/env bash
# PocketHost installer for Termux. The PocketHost app runs this for you;
# you can also run it by hand:  bash install.sh
#   SKIP_N8N=1      do not install n8n (saves ~1 GB and 10+ minutes)
#   PH_AUTOSTART=1  start everything when done
set -euo pipefail

SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PH_DIR="$HOME/.pockethost"
step() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
ok() { printf '\033[32m✔\033[0m %s\n' "$*"; }
warn() { printf '\033[33m!\033[0m %s\n' "$*"; }

if [ -z "${PREFIX:-}" ] || [ ! -d "$PREFIX/bin" ] || ! command -v pkg >/dev/null; then
  echo "This installer must run inside Termux." >&2
  exit 1
fi

printf '\033[1m⚡ PocketHost installer\033[0m\n'
echo "Turning this phone into an AI server. Keep Termux open and the phone charging."

mkdir -p "$PH_DIR/run" "$PH_DIR/logs" "$HOME/.termux"

# Let the PocketHost app send commands to Termux.
if ! grep -qs '^allow-external-apps *= *true' "$HOME/.termux/termux.properties"; then
  echo 'allow-external-apps=true' >>"$HOME/.termux/termux.properties"
  termux-reload-settings 2>/dev/null || true
fi

export DEBIAN_FRONTEND=noninteractive
APT_OPTS=(-y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold)

step "Updating Termux packages"
pkg update "${APT_OPTS[@]}" || apt-get update
pkg upgrade "${APT_OPTS[@]}"

step "Installing Ollama, Node.js and Cloudflare tunnel"
pkg install "${APT_OPTS[@]}" nodejs-lts ollama cloudflared curl termux-tools coreutils procps
ok "core packages installed"

if [ "${SKIP_N8N:-0}" != 1 ]; then
  if command -v n8n >/dev/null; then
    ok "n8n already installed ($(n8n --version 2>/dev/null || echo '?'))"
  else
    step "Installing n8n (5-20 minutes on a phone, please wait)"
    pkg install "${APT_OPTS[@]}" python make clang binutils pkg-config libsqlite
    # node-gyp on Termux needs this to build native modules (sqlite3 etc.)
    mkdir -p "$HOME/.gyp"
    [ -f "$HOME/.gyp/include.gypi" ] || echo "{'variables':{'android_ndk_path':''}}" >"$HOME/.gyp/include.gypi"
    if npm install -g n8n --no-audit --no-fund --loglevel=error; then
      ok "n8n installed"
    else
      warn "n8n failed to install. Ollama and the gateway still work."
      warn "Retry later from the app (Settings > Reinstall) or run: npm install -g n8n"
    fi
  fi
fi

step "Installing PocketHost"
install -m 755 "$SRC/pockethost.sh" "$PREFIX/bin/pockethost"
command -v termux-fix-shebang >/dev/null && termux-fix-shebang "$PREFIX/bin/pockethost"
install -m 644 "$SRC/gateway.js" "$PH_DIR/gateway.js"
pockethost key >/dev/null   # creates config + API key on first install
chmod 600 "$PH_DIR/config.env"
ok "pockethost $(pockethost version) installed"

echo
printf '\033[1;32m✔ PocketHost is installed.\033[0m\n'
echo "  Go back to the PocketHost app and tap Start,"
echo "  or run here:  pockethost start"
echo "  Help:         pockethost help   |   pockethost doctor"

if [ "${PH_AUTOSTART:-0}" = 1 ]; then
  step "Starting PocketHost"
  pockethost start || true
fi

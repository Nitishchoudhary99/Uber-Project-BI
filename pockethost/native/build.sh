#!/usr/bin/env bash
# Cross-compiles the server engines for Android (arm64) so they ship inside the
# APK. Nothing else gets installed on the phone.
#
#   libollama.so       Ollama (the real one, full API) built with the Android NDK
#   libcloudflared.so  Cloudflare tunnel client
#
# They are executables, not libraries. Android only allows apps to execute
# files from their native library directory, and only files named lib*.so get
# extracted there, hence the names.
#
# Env: ANDROID_NDK_HOME (or ANDROID_NDK_LATEST_HOME), OLLAMA_VERSION,
#      CLOUDFLARED_VERSION (default: latest release), OUT, WORK, API (min SDK).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="${OUT:-$HERE/out/arm64-v8a}"
WORK="${WORK:-$HERE/work}"
API="${API:-26}"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_LATEST_HOME:-}}"
[ -n "$NDK" ] && [ -d "$NDK" ] || { echo "Set ANDROID_NDK_HOME to an Android NDK" >&2; exit 1; }
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"

latest() {
  local auth=()
  [ -n "${GITHUB_TOKEN:-}" ] && auth=(-H "Authorization: Bearer $GITHUB_TOKEN")
  curl -fsSL "${auth[@]}" "https://api.github.com/repos/$1/releases/latest" |
    sed -n 's/.*"tag_name": *"\([^"]*\)".*/\1/p' | head -n1
}

OLLAMA_VERSION="${OLLAMA_VERSION:-$(latest ollama/ollama)}"
CLOUDFLARED_VERSION="${CLOUDFLARED_VERSION:-$(latest cloudflare/cloudflared)}"
echo "ollama $OLLAMA_VERSION, cloudflared $CLOUDFLARED_VERSION, NDK $NDK"

export CGO_ENABLED=1 GOOS=android GOARCH=arm64 GOTOOLCHAIN=auto
export CC="$TC/aarch64-linux-android$API-clang" CXX="$TC/aarch64-linux-android$API-clang++"

mkdir -p "$OUT" "$WORK"

fetch() { # repo tag dir
  [ -d "$3/.git" ] || git clone --quiet --depth 1 --branch "$2" "https://github.com/$1" "$3"
}

echo "==> Building Ollama"
fetch ollama/ollama "$OLLAMA_VERSION" "$WORK/ollama"
(
  cd "$WORK/ollama"
  # Static C++ runtime so the binary needs nothing beyond Android's system libs.
  CGO_CFLAGS="-O3" CGO_CXXFLAGS="-O3" CGO_LDFLAGS="-static-libstdc++ -llog" \
    go build -trimpath \
    -ldflags "-s -w -X=github.com/ollama/ollama/version.Version=${OLLAMA_VERSION#v}" \
    -o "$OUT/libollama.so" .
)

echo "==> Building cloudflared"
fetch cloudflare/cloudflared "$CLOUDFLARED_VERSION" "$WORK/cloudflared"
(
  cd "$WORK/cloudflared"
  go build -trimpath \
    -ldflags "-s -w -X main.Version=$CLOUDFLARED_VERSION -X main.BuildTime=$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    -o "$OUT/libcloudflared.so" ./cmd/cloudflared
)

# cgo links Ollama's C++ code against the shared C++ runtime; ship it alongside
# (the app sets LD_LIBRARY_PATH to its native lib dir).
cp "$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so" "$OUT/"

echo "==> Checking binaries"
READELF="$TC/llvm-readelf"
for f in "$OUT"/*.so; do
  needed=$("$READELF" -d "$f" | sed -n 's/.*Shared library: \[\(.*\)\]/\1/p' | tr '\n' ' ')
  echo "$(basename "$f"): $(du -h "$f" | cut -f1), needs: ${needed:-nothing}"
  for lib in $needed; do
    case "$lib" in
    libc.so | libm.so | libdl.so | liblog.so) ;;
    libc++_shared.so) [ -f "$OUT/libc++_shared.so" ] || { echo "ERROR: libc++_shared.so missing" >&2; exit 1; } ;;
    *) echo "ERROR: $(basename "$f") depends on $lib, which Android does not provide" >&2; exit 1 ;;
    esac
  done
done

printf 'OLLAMA_VERSION=%s\nCLOUDFLARED_VERSION=%s\n' "${OLLAMA_VERSION#v}" "$CLOUDFLARED_VERSION" >"$OUT/../versions.txt"
echo "Done -> $OUT"

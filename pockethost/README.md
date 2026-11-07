# ⚡ PocketHost

**One Android app that turns your phone into a public AI server.**
Ollama, a secure API gateway and a Cloudflare tunnel are all built into the APK. You don't need Termux, root, a PC or port forwarding.

```
           Internet (HTTPS, works on mobile data / CGNAT)
                              │
┌─────────────────────────────┼──────────── PocketHost app ─┐
│  Foreground service (wakelock, auto-restart, boot start)  │
│                             ▼                             │
│   libcloudflared.so ──► Gateway :8080 (Kotlin)            │
│     (tunnel)              API-key auth, CORS, streaming   │
│                             │                             │
│                             ▼                             │
│                  libollama.so serve :11434                │
│                    (real Ollama, 127.0.0.1 only)          │
│                                                           │
│   UI: power button · models · chat · connect · settings   │
└───────────────────────────────────────────────────────────┘
```

## How it works without other apps

Android lets an app execute binaries that ship inside its APK, as long as they sit in the app's native-library folder. So [`native/build.sh`](native/build.sh) cross-compiles the **real Ollama** and **cloudflared** with the Android NDK (static C++, no extra dependencies). The binaries are packaged as `libollama.so` and `libcloudflared.so`. The app's foreground service runs them as child processes and supervises them.

## Features

| | |
|---|---|
| **Single app** | Install the APK, tap Start, and the server runs. |
| **Real Ollama** | The full Ollama API, any model from ollama.com, and its OpenAI-compatible endpoints. |
| **Public HTTPS URL** | A free quick tunnel, or your own domain through a Cloudflare tunnel token. |
| **Secure gateway** | Every request from the internet needs the API key (constant-time check, rotate in one tap). The engine listens only on 127.0.0.1. |
| **Built to stay up 24/7** | Foreground service with wakelock and Wi-Fi lock. Crashed processes restart with backoff, and the server can start on boot. |
| **Model manager** | A catalogue matched to your phone's RAM, with download progress and delete. |
| **On-device chat** | Test models and see tokens/sec. |
| **Connect screen** | A copy-ready base URL and key, plus snippets for curl, Python, n8n, LangChain and Open WebUI. |
| **Live logs** | Tap any service to see its output. |
| **Device health** | Battery, temperature (with a throttling warning), RAM and storage. |

## Install

Download `PocketHost.apk` from the latest **Actions → PocketHost Android** run (artifact `PocketHost-apk`), install it and tap **Start my server**. It needs an arm64 phone running Android 8 or newer.

## API

| Path | What |
|---|---|
| `/v1/...` | OpenAI-compatible (`/v1/chat/completions`, `/v1/models`, `/v1/embeddings`) |
| `/api/...` | Native Ollama API (`/api/chat`, `/api/generate`, `/api/tags`…) |
| `/health` | Readiness check, no key required |

```bash
curl https://<your-url>/v1/chat/completions \
  -H "Authorization: Bearer <API key>" -H "Content-Type: application/json" \
  -d '{"model":"gemma3:1b","messages":[{"role":"user","content":"Hello!"}]}'
```

**Using it from n8n** (cloud, Docker or PC): create an OpenAI credential, set Base URL to `https://<your-url>/v1`, and use your API key.

## Which models fit?

| Phone RAM | Good choices |
|---|---|
| 4 GB | `gemma3:1b`, `qwen2.5:0.5b`, `qwen2.5:1.5b` |
| 6–8 GB | `llama3.2:3b`, `qwen2.5:3b`, `qwen3:1.7b` |
| 12 GB+ | `gemma3:4b`, `qwen3:4b`, `phi4-mini`, 7B q4 (slow) |

## Build

```bash
# 1. engines (needs Go + Android NDK)
ANDROID_NDK_HOME=/path/to/ndk bash pockethost/native/build.sh
mkdir -p pockethost/android/app/src/main/jniLibs/arm64-v8a
cp pockethost/native/out/arm64-v8a/*.so pockethost/android/app/src/main/jniLibs/arm64-v8a/
# 2. app
cd pockethost/android && ./gradlew assembleDebug
```

CI does both steps on every push and caches the engines per version.

## Roadmap

- **n8n inside the app.** This needs an embedded Node.js runtime built for Android, plus native modules. It is the next milestone. Until then, n8n anywhere can use the phone as its AI backend (see above).
- Optimised CPU builds (ARMv8.2 dotprod/i8mm) with runtime detection, for 2–3× faster inference on newer phones.
- LAN mode, so devices on the same Wi-Fi can connect without the tunnel.

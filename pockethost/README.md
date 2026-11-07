# ⚡ PocketHost

**Turn any Android phone into a public AI + automation server.**
Ollama models, n8n workflows and a secure HTTPS URL, all controlled from one app and running on your own phone.

```
                   Internet (HTTPS)
                         │
              Cloudflare Tunnel (no port forwarding,
              works on mobile data / CGNAT)
                         │
┌────────────────────────┼──────────── Android phone ─┐
│  Termux                ▼                            │
│   ┌──────────────────────────────┐                  │
│   │ PocketHost gateway  :8080    │  API-key auth    │
│   │  /ollama/*  → Ollama :11434  │  (OpenAI API)    │
│   │  /webhook/* → n8n    :5678   │  public          │
│   │  /*         → n8n editor     │  key + cookie    │
│   └──────────────────────────────┘                  │
│                                                     │
│  PocketHost app ── RUN_COMMAND ──► pockethost CLI   │
│   (start/stop, models, chat, endpoints, settings)   │
└─────────────────────────────────────────────────────┘
```

## Features

| | |
|---|---|
| **One-tap server** | Starts Ollama, n8n, the gateway and the tunnel. The live status and URL stay in a notification. |
| **Guided setup** | A 4-step wizard. The installer is bundled inside the APK, so nothing has to be fetched by hand. |
| **Model manager** | A curated catalogue that checks your phone's RAM, shows download progress, and lets you pull any model from ollama.com. |
| **On-device chat** | Test any model and see tokens/sec. Nothing leaves the phone. |
| **OpenAI-compatible API** | `https://<url>/ollama/v1` with a Bearer key. Works with LangChain, Open WebUI, Cursor, Continue and the OpenAI SDKs. |
| **n8n with public webhooks** | The editor is protected by the key. Webhook and form URLs are public and point at the correct domain automatically. |
| **Your own domain** | Paste a Cloudflare tunnel token to get `https://ai.yourdomain.com`, or use a free quick tunnel. |
| **Survives reboots** | Optional auto-start through Termux:Boot, plus a battery and phantom-process checklist. |
| **Device health** | Shows battery, temperature (with a throttling warning), RAM and storage. |
| **Security by default** | Services listen only on 127.0.0.1, the API key is checked in constant time, and the key can be rotated in one tap. |

## Install

1. Download `PocketHost.apk` from the latest **GitHub Actions → PocketHost Android** run (artifact `PocketHost-apk`).
2. Install **Termux** from [F-Droid](https://f-droid.org/packages/com.termux/). The Play Store build is outdated. Open it once.
3. Open PocketHost and follow the wizard: grant permission → paste one line in Termux → **Install & start**.

The install takes 5–20 minutes, mostly because of n8n. Keep the phone charging.

## Which models fit?

| Phone RAM | Good choices |
|---|---|
| 4 GB | `gemma3:1b`, `qwen2.5:0.5b`, `qwen2.5:1.5b` |
| 6–8 GB | `llama3.2:3b`, `qwen2.5:3b`, `qwen3:1.7b` |
| 12 GB+ | `gemma3:4b`, `qwen3:4b`, `phi4-mini`, 7B q4 (slow) |

Inference runs on the CPU. Expect roughly 5–20 tokens/sec for 1–3B models on a recent phone.

## CLI (inside Termux)

```bash
pockethost start | stop | restart [service]
pockethost status [--json]
pockethost url | key | rotate-key
pockethost pull gemma3:1b | rm <model> | models
pockethost logs n8n -f
pockethost config set TUNNEL token
pockethost config set CF_TUNNEL_TOKEN <token>
pockethost config set PUBLIC_URL https://ai.example.com
pockethost boot on
pockethost doctor
```

## Use it

```bash
curl https://<your-url>/ollama/v1/chat/completions \
  -H "Authorization: Bearer <API key>" -H "Content-Type: application/json" \
  -d '{"model":"gemma3:1b","messages":[{"role":"user","content":"Hello!"}]}'
```

- **n8n editor:** open `https://<your-url>/?key=<API key>` once. A cookie keeps you signed in for 30 days.
- **n8n webhooks:** `https://<your-url>/webhook/<path>`. These are public.
- **Inside n8n:** use the Ollama credential with base URL `http://127.0.0.1:11434`.

## Keeping it alive 24/7

- Set Termux battery usage to **Unrestricted**.
- On Android 12+, run this once from a PC:
  `adb shell "settings put global settings_enable_monitor_phantom_procs false"`
- Keep the phone plugged in and somewhere cool.

## Project layout

```
pockethost/
├── termux/            # runs on the phone (bundled into the APK as assets)
│   ├── install.sh     # installs packages, n8n and the CLI
│   ├── pockethost.sh  # the `pockethost` CLI / process manager
│   └── gateway.js     # zero-dependency auth + reverse proxy
└── android/           # Kotlin + Jetpack Compose app
```

Build locally with `cd pockethost/android && ./gradlew assembleDebug`. This needs the Android SDK, or you can open the folder in Android Studio.

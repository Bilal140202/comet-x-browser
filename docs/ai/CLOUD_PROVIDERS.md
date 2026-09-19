# Cloud AI providers (BYOK) + Discord agent monitor — v2.2.0

Comet-X is local-first (llama.cpp, then in-browser Transformers.js), but the
agent runs best on a cloud model. Every cloud provider in Comet-X is
**bring-your-own-key (BYOK)**: paste your personal API key in
**Settings → AI Provider**, press **Test & Enable**, done. Keys are encrypted
with the Android Keystore (AES-256/GCM) and are sent ONLY to the provider's
official endpoint — never to us, never logged, never shipped inside the APK.

## The provider presets (all verified live)

| Provider | id | Base URL (hardcoded, verified) | Key shape | Verified in dev |
|----------|----|-------------------------------|-----------|-----------------|
| Groq | `groq` | `https://api.groq.com/openai/v1` | `gsk_…` (console.groq.com/keys) | v1.1.0+ |
| OpenRouter | `openrouter` | `https://openrouter.ai/api/v1` | `sk-or-v1-…` (openrouter.ai/keys) | v2.2.0 — `GET /auth/key` 200 + real chat completion 200 (`inclusionai/ling-3.0-flash-vl:free`) |
| NVIDIA NIM | `nvidia` | `https://integrate.api.nvidia.com/v1` | `nvapi-…` (build.nvidia.com → API keys) | v2.2.0 — `GET /v1/models` 200 + real chat completion 200 (`openai/gpt-oss-20b`) |
| Hugging Face | `huggingface` | `https://router.huggingface.co/v1` | `hf_…` | v1.x |
| Self-run | `custom` | any OpenAI-compatible `/v1` (Ollama, LM Studio, vLLM, …) | optional | v1.1.0+ |

### Why model IDs are not hardcoded

NVIDIA's catalog retires entries over time (verified during development: a
retired id answers `404 Function … not found` while `/models` still lists
fresh ones). Comet-X therefore always **discovers models live** (FETCH
MODELS) and ranks what YOUR key can actually run. The only pinned id is the
development-time verification model (`openai/gpt-oss-20b`), recorded above
so the result is reproducible.

### OpenRouter notes

OpenRouter is metadata-first: pricing marks free models automatically, and
supported parameters (tools / response_format / reasoning) drive capability
negotiation. The shipped headers `HTTP-Referer` / `X-Title` identify the app
to OpenRouter rankings. A free-tier key verified end-to-end in v2.2.0
development (50 free-model requests/day at the time).

## Discord agent monitor (v2.2.0)

Background agent tasks (v1.8.0) are monitored through the notification bar —
which exists only on the device. The Discord mirror repeats task events into
a Discord channel so any other device becomes a monitor:

```
**Comet-X agent — Task started**
Goal: Find the cheapest flight to Delhi
**Comet-X agent — Progress**
Step 5/24
→ clicked e12 "Search"
**Comet-X agent — Waiting for you**
buy — exceeds spend limit
**Comet-X agent — Task completed · 18/24 steps**
```

### Setup (one time)

1. discord.com/developers → **New Application** → **Bot** → copy the **token**.
2. Invite the bot to your server with **Send Messages** permission
   (OAuth2 → URL generator → scope `bot`).
3. In Discord: Settings → Advanced → enable **Developer Mode**, then
   right-click the target channel → **Copy Channel ID**.
4. Comet-X → Settings → **Discord agent monitor** → paste token + channel ID
   → **Send test message** (a real post — the only honest verification) →
   enable the toggle.

### What is mirrored (and what never is)

- Mirrored: task start, step milestones (every 5th step + final step),
  approval/ask-user/challenge gates, and exactly one final event
  (completed / failed / stopped).
- Never mirrored: API keys, page screenshots, clipboard content, passwords,
  form values. Gate messages carry only the agent's own one-line reason.
- A Discord outage or an invalid token is logged and dropped — it can never
  delay, fail or alter a running task (fire-and-forget, 15 s timeout,
  ≥2.5 s throttle, rate-limit friendly).

## The unidentified key — honesty record

One key provided during v2.2.0 development (43-char, unprefixed base64url)
could NOT be attributed after an empirical probe battery of ~50 services
(LLM providers: Groq, Cerebras, Together, SambaNova, Novita, Mistral,
Cohere, Nebius, AI/ML API, NanoGPT, Ollama Cloud, Nous, Requesty, Chutes,
Fireworks, DeepInfra, Hyperbolic, Perplexity, xAI, OpenAI, Moonshot,
SiliconFlow … search: Tavily, Exa, Brave, Serper, Mojeek … speech: Deepgram,
ElevenLabs … social: Discord ✓, Mastodon … plus Notion, Cloudflare, Basing).
Every endpoint reachable from the build environment answered with a
definitive auth failure; Groq/Cerebras/OpenAI/Together were region-blocked
at the CDN level but are excluded by key SHAPE anyway. **Conclusion: not
identifiable from the key alone.** If it is yours: paste it into
**Settings → AI Provider → Self-run** together with the provider's
`/v1` base URL, or tell us the provider name and we will ship a verified
preset exactly like NVIDIA's. No preset was guessed — wrong presets are how
"cloud AI meshups" happen, and that is precisely what this release refuses
to do.

## Security posture (unchanged + extended)

- All provider keys AND the Discord bot token: SecureStore → Android
  Keystore, non-exportable master key, per-entry random IV, ciphertext-only
  on disk.
- No key material is ever written to logs, diagnostics, AI event log, or the
  mirrored Discord messages.
- Robolectric has no AndroidKeyStore: tests override the key accessor instead
  of writing through SecureStore (same pattern since v1.1.0).

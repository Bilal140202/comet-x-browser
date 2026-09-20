# Cloud AI — provider config, live verification & the Cloud AI center

> v2.2.0. The Cloud AI center (`Settings → Open Cloud AI center`) is the app's
> first Jetpack Compose / Material 3 Expressive surface. You paste YOUR OWN
> personal API keys; the app ships pre-configured base URLs and model catalogs
> that were verified against the live services before this release — so cloud
> AI works with zero manual endpoint fiddling.

## 1. What ships where

| Layer | File(s) | Role |
|-------|---------|------|
| Providers | `ai/NvidiaNimProvider.kt`, `ai/Providers.kt` | One OpenAI-compatible transport, per-provider normalization |
| Key hygiene | `ai/KeyFormat.kt` | Shape warnings (hints, not gates) + display masking |
| Chain | `ai/SettingsRepository.kt`, `ai/ProviderSet.kt`, `ai/ModelRouter.kt` | `nvidia` joins additively; user-ordered failover unchanged |
| UI | `ui/cloud/CloudTheme.kt`, `ui/cloud/CloudAiActivity.kt`, `ui/cloud/CloudDiscordCard.kt` | Compose M3: provider cards, masked key fields, live tests, chain reorder, Discord |
| Discord push | `social/DiscordNotifier.kt` | REST-only embed push of finished background tasks |
| Storage | `security/SecureStore.kt` | AES-256/GCM key in the Android Keystore; ciphertext in prefs |

## 2. Verified configuration (evidence log, 2026-09-20)

All probes ran with keys loaded from an env file **outside the repository**;
nothing below includes credential material.

### NVIDIA NIM — verified working
- `GET https://integrate.api.nvidia.com/v1/models` → 200, 82 models (public catalog;
  ids like `openai/gpt-oss-20b`, `google/gemma-3-4b-it`, `meta/llama-3.2-11b-vision-instruct`).
- `POST /v1/chat/completions` with an `nvapi-…` key, model `openai/gpt-oss-20b` → **200** real completion.
- Auth check ordering: a bare (non-`nvapi-`) key returns `401 Authentication failed` —
  key format is validated by the service before model resolution.
- Honest quirk: reasoning families (`gpt-oss`, `nemotron`, Kimi …) spend early tokens
  on `reasoning_content`; with tiny `max_tokens` the final `content` can be `null`.
  The agent's default budget (2000 tokens) is unaffected.

### OpenRouter — verified working
- `GET https://openrouter.ai/api/v1/models` → 200 (public catalog; **25 free models** at test time).
- `POST /api/v1/chat/completions` with an `sk-or-v1-…` key on a `:free` model → **200** real completion.
- App sends `HTTP-Referer: https://github.com/Bilal140202/comet-x-browser` + `X-Title: Comet-X`
  as recommended by OpenRouter.

### Discord — verified working
- `GET /api/v10/users/@me` with a `Bot …` token → **200** bot identity.
- `POST /api/v10/channels/{id}/messages` with one rich embed → **200 delivered**.
- The shipped code uses exactly these two paths (validate + post). No Gateway
  socket, no message reading, no extra permissions.

### Groq / Hugging Face
- Pre-existing providers (v1.1.0 era). Groq's API is region-blocked from the
  build sandbox (Cloudflare 403) so this round could not re-verify it live; the
  config is unchanged from v1.1.0 and remains in the same UI.

### The 43-char mystery key — honestly unresolved
One provided credential (43 chars, base64url-like, no vendor prefix) was probed
against **~48 providers/endpoints** (OpenAI-compatible clouds, aggregators,
search/utility APIs, special auth shapes). Every reachable service explicitly
rejected it (`401/403 invalid`); several hosts were unreachable from the sandbox
region. It is therefore **not wired to any provider**. If you know its vendor:
Settings → Custom / OpenAI-compatible → set the base URL, paste the key, Test.
It will work with any OpenAI-compatible endpoint.

## 3. How the fallback chain treats new providers

```
chain (user-ordered):  groq → openrouter → nvidia → huggingface → custom
                       on-device llama.cpp / in-browser transformers join per v1.6.0/v2.1.0 rules
```

- A provider is IN the live chain only when enabled **and** a key is stored.
- No keys at all → the chain is byte-identical to v2.1.0 (local-first semantics
  unchanged; Functionality Lock §CLD-1).
- Model selection stays AUTO by default: the router fetches the live catalog,
  ranks agent-suitable models and negotiates protocols; MANUAL is an expert
  override per provider.

## 4. Security posture

- Keys are encrypted at rest (Android Keystore AES-256/GCM), excluded from
  `allowBackup`, displayed masked only (`nvapi-A1••••0U1v`), and sent solely to
  the provider base URL they belong to — over HTTPS only.
- Shape warnings are advisory: a provider-side format change can never brick a
  saved key (CLD-4).
- The Discord bot token gets identical treatment; the AI event log records only
  "discord: result pushed for bg-…" — never tokens or payloads.
- No real credential ever entered the repository or APK. Tests use synthetic
  shape-valid keys; the live verification log above was produced out-of-band.

## 5. Known honest limits

- Free/model availability and prices are provider-side facts that change; the
  "Test connection" button is the source of truth for the moment you press it.
- NVIDIA's public catalog omits `context_length`; ranking treats it as unknown
  rather than guessing.
- Discord push covers COMPLETED/FAILED background tasks only (one embed per
  task); foreground-session tasks and CANCELLED/INTERRUPTED runs don't push.
- Region-blocked networks can make Groq (and the unidentified key's vendor, if
  any) unreachable even with a valid key — that is a network fact, not an app bug.

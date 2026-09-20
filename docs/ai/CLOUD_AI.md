# Cloud AI — provider config, live verification & the Cloud AI center

> v2.2.0 shipped the BYOK backend (NVIDIA NIM preset, Discord agent monitor —
> contracts NV-1..3 / DM-1..6, see `docs/ai/CLOUD_PROVIDERS.md`).
> v2.3.0 adds the **Cloud AI center**: the app's first Jetpack Compose /
> Material 3 Expressive surface, on the same encrypted settings keys.
> You paste YOUR OWN personal API keys; base URLs and model catalogs ship
> pre-configured, verified against the live services before release.

## 1. What ships where

| Layer | File(s) | Role |
|-------|---------|------|
| Providers | `ai/Providers.kt` (`NvidiaProvider` etc.) | One OpenAI-compatible transport, per-provider normalization |
| Key hygiene | `ai/KeyFormat.kt` (v2.3.0) | Shape warnings (hints, not gates) + display masking |
| Chain | `ai/SettingsRepository.kt`, `ai/ProviderSet.kt`, `ai/ModelRouter.kt` | `nvidia` joined additively in v2.2.0; verified default model table (v2.3.0) |
| Cloud AI center | `ui/cloud/CloudTheme.kt`, `ui/cloud/CloudAiActivity.kt`, `ui/cloud/CloudDiscordCard.kt` (v2.3.0) | Compose M3: provider cards, masked key fields, live tests, AUTO/MANUAL, chain reorder, Discord |
| Discord push | `background/DiscordNotifier.kt` + `AgentTaskService` hooks (v2.2.0) | REST-only event mirror (start / milestones / gates / result), throttled, deduped |
| Storage | `security/SecureStore.kt` | AES-256/GCM key in the Android Keystore; ciphertext in prefs |

## 2. Verified configuration (evidence log, 2026-09-20)

All probes ran with keys loaded from an env file **outside the repository**;
nothing below includes credential material.

### NVIDIA NIM — verified working
- `GET https://integrate.api.nvidia.com/v1/models` → 200, 82 models (public catalog;
  ids like `openai/gpt-oss-20b`, `google/gemma-3-4b-it`, `meta/llama-3.2-11b-vision-instruct`).
- `POST /v1/chat/completions` with an `nvapi-…` key, model `openai/gpt-oss-20b` → **200** real completion.
- Auth check ordering: a bare (non-`nvapi-`) key returns `401 Authentication failed` —
  the service validates the key before resolving the model.
- Honest quirks: (a) reasoning families (`gpt-oss`, `nemotron`, Kimi …) spend early
  tokens on `reasoning_content` — with tiny `max_tokens` the final `content` can be
  `null`; the agent's default 2000-token budget is unaffected. (b) NVIDIA retires
  catalog ids over time (a retired id answers 404) — discovery is live, never hardcoded.

### OpenRouter — verified working
- `GET https://openrouter.ai/api/v1/models` → 200 (public catalog; **25 free models** at test time).
- `POST /api/v1/chat/completions` with an `sk-or-v1-…` key on a `:free` model → **200** real completion.
- App sends `HTTP-Referer: https://github.com/Bilal140202/comet-x-browser` + `X-Title: Comet-X`
  as recommended by OpenRouter.

### Discord — verified working
- `GET /api/v10/users/@me` with a `Bot …` token → **200** bot identity.
- `POST /api/v10/channels/{id}/messages` → **200 delivered** (both an embed-shaped
  payload and the plain-content shape the monitor ships were exercised).
- The shipped monitor uses exactly the validate + post paths. No Gateway socket,
  no message reading, no extra permissions.

### Groq / Hugging Face
- Pre-existing providers (v1.1.0 era), unchanged. Groq's API is region-blocked
  from the build sandbox (Cloudflare 403) so this round could not re-verify it
  live; the config stands as shipped in v1.1.0.

### The 43-char mystery key — honestly unresolved
One provided credential (43 chars, base64url-like, no vendor prefix) was probed
against **~48 providers/endpoints** (OpenAI-compatible clouds, aggregators,
search/utility APIs, special auth shapes). Every reachable service explicitly
rejected it (`401/403 invalid`); several hosts were unreachable from the sandbox
region. It is therefore **not wired to any provider**. If you know its vendor:
Cloud AI center → Custom / OpenAI-compatible → set the base URL, paste the key,
Test. It will work with any OpenAI-compatible endpoint.

## 3. How the fallback chain treats the providers

```
chain (user-ordered):  groq → openrouter → nvidia → huggingface → custom
                       on-device llama.cpp / in-browser transformers join per v1.6.0/v2.1.0 rules
```

- A provider is IN the live chain only when enabled **and** a key is stored.
- No keys at all → the chain is byte-identical to v2.1.0 (lock NV-3/DM-1).
- Model selection stays AUTO by default (live catalog fetch + ranking +
  protocol negotiation); MANUAL is an expert override per provider. v2.3.0
  pins verified NVIDIA fallback ids (`openai/gpt-oss-20b` for AGENT/REASONING,
  `google/gemma-3-4b-it` for FAST/CHEAP, `meta/llama-3.2-11b-vision-instruct`
  for VISION) used only when no catalog and no override exist (§33 semantics).

## 4. Security posture

- Keys are encrypted at rest (Android Keystore AES-256/GCM), excluded from
  `allowBackup`, displayed masked only (`nvapi-A1••••0U1v`), and sent solely to
  the provider base URL they belong to — over HTTPS only.
- Shape warnings are advisory: a provider-side format change can never brick a
  saved key.
- The Discord bot token gets identical treatment (key `discord_bot_token`);
  payloads and log lines never contain it.
- No real credential ever entered the repository or APK. Tests use synthetic
  shape-valid keys; the live verification log above was produced out-of-band.

## 5. Known honest limits

- Free/model availability and prices are provider-side facts that change; the
  "Test connection" button is the source of truth for the moment you press it.
- NVIDIA's public catalog omits `context_length`; ranking treats it as unknown.
- Region-blocked networks can make Groq (and the unidentified key's vendor, if
  any) unreachable even with a valid key — a network fact, not an app bug.
- The Compose center ships UI + settings only; agent routing semantics are the
  v2.2.0 ones (unchanged on purpose).

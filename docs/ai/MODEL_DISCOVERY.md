# MODEL DISCOVERY

> COMET-X Phase 2 — how the app finds out which models exist and what they can do.

## Why

Provider catalogs change weekly. Hardcoded model IDs break the app the day a
provider retires one (the Phase 1 failure: "Model not available" for a model
the provider still lists, or the inverse). Model discovery makes the catalog a
RUNTIME FACT, not a build-time assumption.

## Pipeline

```
API key entered
      ↓
GET {baseUrl}/models            (OpenAI-compatible list endpoint)
      ↓
Raw JSON (data[] / models[])
      ↓
normalizeCatalog()              (per-provider parser)
      ↓
List<ModelInfo>                 (normalized records)
      ↓
Capability analysis             (metadata first, probes second)
      ↓
Ranking + selection             (ModelRanker)
      ↓
Agent ready — AUTO
```

## ModelInfo record

| field          | meaning                                             |
|----------------|-----------------------------------------------------|
| id             | provider model id, e.g. `llama-3.3-70b-versatile`   |
| provider       | owning provider id (groq/openrouter/huggingface/custom) |
| displayName    | human label (OpenRouter publishes pretty names)     |
| contextLength  | context window in tokens (0 = unknown)              |
| ownedBy        | publisher string from the provider                  |
| capabilities   | set of [CHAT, TOOL_CALLING, JSON_OBJECT, JSON_SCHEMA, VISION, REASONING, STREAMING] |
| free           | true when the model costs nothing (OpenRouter pricing) |
| chatCapable    | false for audio/guard/embedding endpoints           |
| note / lastVerified | provenance + freshness                         |

## Provider specifics

### Groq (`api.groq.com/openai/v1/models`)
- `context_window` captured into contextLength
- Vision families hinted by id (`llama-4*`, `qwen*vl*`, `gemma-3*`) — runtime
  `VISION_UNSUPPORTED` classification still guards against wrong hints
- Reasoning families (`deepseek-r1`, `qwen3`) hinted
- **Excluded from agent candidacy**: ids containing `whisper`, `tts`, `guard`,
  `embed`, `rerank`, `moderation`, `speech`, `playai` — these appear in the
  catalog but cannot drive a browser agent

### OpenRouter (`openrouter.ai/api/v1/models`) — the gold standard
OpenRouter publishes everything needed, so metadata alone decides:
- `pricing.prompt == "0" && pricing.completion == "0"` (or `:free` suffix) → **free**
- `supported_parameters`: `tools`/`tool_choice` → TOOL_CALLING,
  `response_format` → JSON_OBJECT, `structured_outputs` → JSON_SCHEMA,
  `reasoning`/`include_reasoning` → REASONING
- `architecture.input_modalities` contains `image` → VISION
- `architecture.output_modalities` without `text` (image generators) → excluded

**Free-only policy**: in AUTO mode OpenRouter candidate ranking filters to
free models only. Paid models are reachable only via Advanced → MANUAL
override. If OpenRouter ever returns zero free models, the router degrades to
paid candidates with a logged note rather than dead-ending.

### Hugging Face router / Self-run (OpenAI-compatible)
Minimal metadata; every id is assumed chat-capable unless the exclusion list
matches. Capabilities stay conservative (CHAT only) until probes or runtime
evidence refine them.

## Cache (§23)

`ModelCatalog` stores the normalized catalog per provider in SharedPreferences
with:
- a 6-hour TTL
- the API-key fingerprint (hash + length) — pasting a NEW key forces refresh
- in-memory L1 for the running process

Refresh triggers: key change · explicit refresh (Settings → "Forget discovered
models") · MODEL_NOT_FOUND at runtime · TTL expiry on first use. A stale cache
is readable but never authoritative: if fetch fails, the cached list is used
for ranking and the failure is logged.

## "Model exists" ≠ "model works"

The catalog only tells us a model is LISTED. See
[CAPABILITY_NEGOTIATION.md](CAPABILITY_NEGOTIATION.md) for how COMET-X decides
what a model can actually do.

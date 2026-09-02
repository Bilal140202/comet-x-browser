# PROVIDER ARCHITECTURE

> COMET-X Phase 2 — one abstraction, four providers, zero hardcoded model IDs.

## Interface map (§14)

```
LlmProvider (chat transport contract)
 └── OpenAICompatibleProvider   — one wire format, provider-specific parsing
      ├── GroqProvider          — context_window, vision/reasoning id hints
      ├── OpenRouterProvider    — pricing/parameters/modalities metadata (free-only)
      ├── HuggingFaceProvider   — router endpoint, conservative metadata
      └── CustomOpenAIProvider  — self-run servers (Ollama / LM Studio / vLLM …)
```

Supporting collaborators:

| Class                  | Responsibility                                            |
|------------------------|-----------------------------------------------------------|
| `ModelCatalog`         | discovery + normalization + TTL cache + probe/protocol memo |
| `CapabilityProber`     | safe minimal capability probes (chat / json / schema / tool) |
| `ModelRanker`          | agent-suitability scoring + per-purpose selection          |
| `ModelRouter`          | AUTO/MANUAL resolution + negotiation + recovery ladder     |
| `ProviderErrorClassifier` | raw HTTP + message → normalized `ProviderErrorKind`     |
| `ConnectionDiagnostics`| the Test & Enable checklist + compatibility self-test      |
| `ResponseInterpreters` | wire output → AgentDecision (5 protocols)                  |
| `HttpTransport`        | injectable HTTP boundary (real network vs scripted tests)  |

## Where quirks live

Provider-specific behavior is confined to:
- `normalizeOne()` — catalog JSON → `ModelInfo`
- endpoint + auth headers
- nothing else. The negotiation ladder, ranking, interpretation, and recovery
  are provider-independent, so Groq / OpenRouter / HF / self-run behave
  identically from the engine's point of view (§38).

## AUTO vs MANUAL (§12/§13)

- **AUTO (default)**: per provider → catalog → rank (purpose-weighted) → top
  candidate → negotiated protocol. The user never configures a model.
- **MANUAL (Advanced opt-in)**: per-role overrides; runtime MODEL_NOT_FOUND
  still triggers replacement so even advanced setups never dead-end.

## Ranking (§10)

| factor         | points (AGENT purpose)            |
|----------------|-----------------------------------|
| TOOL_CALLING   | +30                               |
| JSON_SCHEMA    | +20                               |
| JSON_OBJECT    | +15                               |
| VISION         | +20 (×3 for VISION purpose)       |
| context ≥100k  | +10 (≥32k +5)                     |
| REASONING      | +10 (×2.5 for REASONING purpose)  |
| free           | +25                               |
| probe latency <900ms | +10                         |
| available      | required — non-chat excluded      |

Per-purpose: AGENT (general loop) · VISION (screenshot description) ·
REASONING (complex recovery) · FAST (cheap/quick calls). If only ONE usable
model exists it is used regardless of missing optional features (§11).

## Failure recovery (§16–§20, one ladder)

```
call fails (ProviderException with kind)
├─ UNSUPPORTED_RESPONSE_FORMAT / TOOL_CALLING → downgrade protocol, same model
├─ MODEL_NOT_FOUND      → invalidate cache → re-rank → replacement → retry
│                        (same model retried at most once — transient 404s)
├─ MODEL_UNAVAILABLE    → next candidate → next provider
├─ RATE_LIMIT           → next candidate → next provider → bounded backoff
│                        (0.5→1→2s, max 3 passes, no runaway retries)
├─ INVALID_API_KEY      → skip provider entirely
├─ CONTEXT_TOO_LARGE    → engine compresses observation, one retry
├─ VISION_UNSUPPORTED   → drop pixels, DOM/a11y path or separate vision model
└─ NETWORK/PROVIDER/UNKNOWN → brief delay, one retry, then next candidate
```

Every automatic switch appends a line to the AI event log (timestamp, provider,
model, reason, outcome — §37). The log never contains keys or prompts.

## No secrets anywhere in the stack

API keys flow only `SecureStore → provider.keyProvider()` at call time. They
never enter ModelInfo, the catalog cache, the event log, diagnostics reports,
or exceptions (messages are provider error strings only).

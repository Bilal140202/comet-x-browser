# In-browser AI — Transformers.js (v2.1.0)

Comet-X can now run **transformer LLMs inside its own browser engine**: the
same model zoo, runtime and tooling as the [Transformers.js]
(https://huggingface.co/docs/transformers.js) ecosystem, executed by the
WebView that already powers the browser — bundled locally, no CDN, no API
key, nothing leaves the device.

## Why this exists

Comet-X already had on-device AI via **llama.cpp** (native ARM code, GGUF
models — fast). The Transformers.js stack is a *second* on-device engine with
a different trade-off profile:

| | llama.cpp (v1.6.0) | Transformers.js (v2.1.0) |
|---|---|---|
| Execution | native ARM (JNI) | WebAssembly inside the browser engine |
| Model format | GGUF | ONNX (int4/q4, the Transformers.js zoo) |
| Speed | faster | slower (WASM), improves as WebView ships faster ORT |
| Memory | app process | WebView renderer (isolated — crash-proof by design) |
| Model choice | curated GGUF catalog | the whole `transformers.js` ecosystem |

Router ranking keeps this honest: **cloud → llama.cpp → Transformers.js** (or
on-device first when "prefer on-device AI" is on, in that same order). The
web engine is the extra safety net and the model-choice option — never a
forced replacement for the faster native path.

## Architecture

```
AgentEngine ──► ModelRouter ──► TransformersWebProvider   (ai/web/)
                                   │  LlmProvider contract
                                   ▼
                             WebLlmRuntime  (app-owned headless WebView)
                                   │  page: https://appassets.androidplatform.net/assets/webllm/
                                   │  + COOP/COEP injected → SharedArrayBuffer → threaded WASM
                                   ▼
                             assets/webllm/runtime.js
                                   │  Transformers.js 4.3.0 (bundled)
                                   │  ONNX Runtime Web 1.31-dev wasm (bundled)
                                   ▼
                             HF hub (weights, ONCE) → Cache Storage → offline forever
```

Key files:

- `ai/web/WebLlmCatalog.kt` — verified model zoo (sizes are hub-reported)
- `ai/web/WebLlmProtocol.kt` — the Kotlin↔JS wire contract (total parser)
- `ai/web/WebLlmRuntime.kt` — WebView host: asset-loader origin, COI headers,
  bridge, crash self-healing, idle release
- `ai/web/TransformersWebProvider.kt` — the `LlmProvider` (guards mirror
  `LocalLlamaProvider`)
- `ai/web/WebLlmManager.kt` — app-scoped singleton (`CometApp.webAI`)
- `assets/webllm/runtime.js` — the in-page engine (keep in lockstep with the
  protocol; contract tests pin both sides)

## The models (verified 2026-09-19)

| Model | File | Size | Guidance |
|---|---|---|---|
| SmolLM2 360M Instruct | `onnx/model_q4.onnx` | 387.9 MB | fastest, weakest at strict JSON |
| **Qwen2.5 0.5B Instruct** (default) | `onnx/model_q4.onnx` | 786.2 MB | best agent-JSON quality at usable speed |
| Qwen2.5 1.5B Instruct | `onnx/model_q4.onnx` | 1 787.6 MB | most capable, slowest, 8 GB+ RAM devices |

All are int4 (q4) exports — the dtype the WebAssembly CPU backend executes
reliably on every evergreen WebView (no WebGPU requirement). Multi-threading
engages automatically when the injected cross-origin isolation headers produce
`SharedArrayBuffer`; otherwise ORT Web falls back to a single thread on its
own. Both paths work.

## Guarantees (Functionality Lock §v2.1.0, TW-1…TW-11)

- **Bundled**: only model weights are ever downloaded; the runtime itself
  loads from APK assets — first boot works with zero connectivity.
- **Contained**: the renderer can crash without taking the app down; tasks
  fail with a clear error and the runtime self-heals on the next use.
- **Total bridge parsing**: malformed bridge payloads can never throw.
- **Cheap readiness**: chain membership never creates a WebView.
- **Text-only honesty**: multimodal input is refused (`MODEL_UNAVAILABLE`) so
  the router moves to a vision-capable provider transparently.
- **Additive**: with no web model selected, the router chain, agent behavior
  and every v2.0.0 contract are byte-identical.

## Honest limits

- WASM inference is slower than native llama.cpp — sometimes a lot on big
  models. Choose the web engine for model choice, not speed.
- The first load of a model downloads its weight file once (up to ~1.8 GB for
  the 1.5B model) into the WebView origin's Cache Storage; clearing the app's
  web data clears the cache and the model re-downloads on next use.
- The 1.5B model needs a high-RAM device; the Settings card warns when the
  device reports less RAM than recommended.
- Vision is not served by this engine (text-only models only).

## Testing

`WebLlmCatalogTest`, `WebLlmProtocolTest`, `TransformersWebProviderTest` (with
a fake engine) and `WebChainTest` (router integration) pin the contracts —
part of the 344-test / 35-suite baseline gate.

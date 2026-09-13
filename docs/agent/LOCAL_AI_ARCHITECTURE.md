# On-Device AI Architecture (v1.6.0 — OPERATION COMET LOCAL)

> Local llama.cpp inference for the Comet-X agent. The agent runs with **no API
> key, no network, nothing leaving the device** when a local model is active.
> Reference implementation: the owner's JARVIS project (same llama.cpp pin,
> same model catalog checksums, same defensive JNI patterns — all verified
> end-to-end on real hardware there before porting here).

## 1. Stack overview

```
┌────────────────────────────────────────────────────────────────────┐
│ AgentEngine ── ModelRouter (chain, protocol ladder, failover)      │
│                    │                                               │
│                    ▼                                               │
│        LocalLlamaProvider  (implements ai.LlmProvider, id="local") │
│          │ chat(messages) → ChatTemplateRenderer → single prompt   │
│          │ NativeLlama (testable seam)                             │
│          ▼                                                         │
│        LlamaBridge (JNI boundary, kept unobfuscated)               │
│          ▼                                                         │
│        libcometx_llama.so ── llama.cpp b4458 (static, CPU-only)    │
└────────────────────────────────────────────────────────────────────┘
LocalModelManager (app-scoped, CometApp): download/resume/verify/import,
load/unload, idle watchdog, perf-core detection, model test.
```

Key point: the local runtime is **not** a side path. It is a first-class
`LlmProvider` inside the existing router chain — the protocol ladder
(JSON_OBJECT → downgrade), the §19 context-compression path, the §37 AI event
log and the failover semantics all work unchanged.

## 2. Native layer (`app/src/main/cpp/`)

- `CMakeLists.txt` — `FetchContent` pins the **official llama.cpp tarball at
  tag b4458 with a SHA-256 hash pin** (`62e698f1…`). No vendored fork, no
  network fetch at runtime; Gradle's native build verifies the archive before
  compiling. `LLAMA_BUILD_*` all OFF; llama linked STATICALLY,
  `cometx_llama` (JNI wrapper) is the only shared object.
- `llama_jni.cpp` — one model + one context at a time (mobile RAM reality).
  - `nativeLoadModel(path, ctx, threads)` — CPU-only (`n_gpu_layers = 0`),
    mmap on, KV cache cleared per completion.
  - `nativeComplete(prompt, maxTokens, temperature, listener, stops)` —
    sampling chain `top_p 0.92 → temp (caller, default 0.25) → dist`;
    64-token prefill chunks so Cancel stays responsive; stop sequences are
    scanned **natively after every token** (matched marker trimmed off the
    result); EOG tokens break the loop.
  - `nativeCountTokens(text)` — lets Kotlin enforce the context budget BEFORE
    decode and raise `ContextTooLargeException` (§19) instead of silently
    truncating the system prompt. Head-trim remains as a last-resort guard.
  - Progress callback is **strictly optional**: a failed `GetMethodID` lookup
    is cleared and generation continues (the JARVIS v1.5.0 minification bug —
    where R8 renamed `onProgress` and every generation was discarded — cannot
    reproduce; see `proguard-rules.pro`).
  - Raw UTF-8 byte arrays cross the boundary (JNI modified-UTF8 never mangles
    model output).

## 3. Kotlin layer (`ai/local/`)

| File | Role |
|---|---|
| `NativeLlama.kt` | Testable seam + `GenProgressListener` (top-level so JVM tests never touch JNI). `RealLlama` degrades to `available=false` when the .so can't load (x86 emulators) — the app then behaves exactly like pre-1.6.0. |
| `LlamaBridge.kt` | The only native-aware class. Kept by ProGuard rules. |
| `LocalModelCatalog.kt` | 7 pinned GGUF entries (0.36B–3B, Q8_0/Q4_K_M) with URL/size/SHA-256/RAM/class/template; `recommendFor(ram, cores)`; `lighterThan` ladder. |
| `ChatTemplateRenderer.kt` | Deterministic CHATML / LLAMA3 / PLAIN rendering (no jinja). Consecutive same-role messages merge; multimodal image parts are dropped (text-only runtime); per-template stop sequences (e.g. `<|im_end|>`) are handed to the native scanner. |
| `LocalLlamaProvider.kt` | `LlmProvider` impl. Two-tier readiness (below). `chat()` = ensure-resident → render → token-count budget check → native complete → honest `ProviderException` kinds for the router. Single-completion `AtomicBoolean` guard (one native context). maxTokens capped at 512 (one decision JSON is small; protects prompt room). |
| `LocalModelManager.kt` | Resumable download (`.part` + `Range`), SHA-256 verify before activation, one-tap activate after download, GGUF import (SAF, magic-checked), idle-unload watchdog, sysfs big.LITTLE perf-core detection, real `Test active model` report (ms/tok/tok-per-sec). |

### Two-tier readiness

- **`isReady()`** (chain membership) is cheap and native-free: a selected model
  file exists on disk + the native runtime is available. A downloaded model is
  therefore ALWAYS a candidate — even in cloud-first mode it silently becomes
  the last-resort fallback when every cloud provider fails.
- **Residency** happens two ways: preloaded at app start when "Prefer on-device
  AI" is ON (`autoReloadIfPreferred`), or loaded ON DEMAND inside `chat()`
  (first fallback call pays a one-time load, RAM stays free otherwise).
- Model load and generation are mutually exclusive (single `ReentrantLock` +
  generating flag): the free-under-decode use-after-free class is structurally
  impossible. `AgentEngine.stop()` additionally calls `router.cancelLocal()`
  because coroutine cancellation cannot interrupt a blocking native decode.

## 4. Chain semantics (additive by design)

```
preferred OFF (default): [ cloud chain… , local? ]   local = last resort
preferred ON           : [ local? , cloud chain… ]   local-first, cloud backup
no local model         : [ cloud chain… ]            byte-identical to 1.5.0
```

`ModelRouter.chain()` only reorders; `candidatesFor` uses the provider-named
`defaultModelId` for non-OpenAI runtimes; local `ModelInfo` claims
`{CHAT, JSON_OBJECT, STREAMING}` — never VISION/TOOL_CALLING — so:
- the agent model "sees" DOM text + refs (no screenshots), and
- `describeScreenshot` keeps looking for a cloud vision model in the chain.

## 5. Model catalog (all SHA-256 pinned from the HuggingFace Hub)

| Model | Quant | Size | RAM | Class | Notes |
|---|---|---|---|---|---|
| SmolLM2-360M | Q8_0 | 386 MB | 0.8 GB | BASIC | fastest, simple goals |
| Qwen2.5-0.5B | Q4_K_M | 491 MB | 1.0 GB | BASIC | reliable JSON at tiny size |
| Llama-3.2-1B | Q4_K_M | 808 MB | 1.7 GB | BASIC | speed/quality sweet spot |
| SmolLM2-1.7B | Q4_K_M | 1.05 GB | 2.2 GB | STANDARD | balanced |
| Qwen2.5-1.5B | Q4_K_M | 1.12 GB | 2.4 GB | STANDARD | long context |
| Llama-3.2-3B | Q4_K_M | 2.02 GB | 3.6 GB | POWER | multi-step reasoning |
| Qwen2.5-3B | Q4_K_M | 2.10 GB | 3.8 GB | POWER | best action-JSON |

Recommendation ceiling: 45% of total RAM. Threads auto-detect physical
performance cores via `/sys/devices/system/cpu/*/cpufreq/cpuinfo_max_freq`
(≥85% of the fastest core) — LITTLE cores slow decode instead of helping.

## 6. Honest constraints

- **arm64-v8a only** (every real phone since ~2015). On other ABIs the section
  hides itself and cloud providers keep working.
- Context runtime cap 4096 (RAM), regardless of trained context. Long pages
  trigger the existing §19 compression path instead of silent truncation.
- On-device models are text-only: no screenshot vision locally (tiny open
  vision models are impractical on phone CPUs); DOM perception + refs still
  give the agent full aiming information, and SoM badges ride along only when
  a cloud vision path is active.
- Token/sec varies wildly by device; the Test-model report shows the real
  number for the user's hardware, never a marketing figure.
- Small models can produce low-quality JSON; the protocol ladder (repair
  round → TAGGED_TEXT → PLAIN_TEXT) and tolerant JSON extraction already
  handle imperfect output, and the router falls back to cloud when local
  output is unusable.

## 7. Functionality Lock compliance

All changes are ADDITIVE. New settings keys (`local_ai_preferred`,
`local_model_id`, `local_context`, `local_threads`, `local_unload_min`) — no
existing key renamed or removed. No existing view touched; the new Settings
section uses the established card/switch/button factories. `chain()` returns
identical results when no local model is downloaded. Regression gate updated
in `docs/ui/FUNCTIONALITY_LOCK.md` §v1.6.0 addendum.

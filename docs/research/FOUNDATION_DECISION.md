# Foundation Decision — Comet-X

Status: **DECIDED** (Phase 3). This document records the binding architectural selections, the reasoning, and what was consciously rejected. It complements the scoring in `FOUNDATION_COMPARISON.md`.

## Selected foundations

### 1. Browser Foundation: **Android System WebView (Chromium)**

The browser is a real, tabbed WebView browser: persistent cookies via `CookieManager`, `WebViewClient`/`WebChromeClient` state hooks, multi-window popup capture into tabs, `DownloadManager`-backed downloads with confirmation gates, file-chooser support, and Safe Browsing enabled. This satisfies the mandate "do not build a browser engine from scratch" — 100% of the engine (Blink, V8, network stack, TLS, cookie infrastructure, tab lifecycle) is inherited; Comet-X differentiates only at the agentic layer. WebView scored 94/100 against the mandated weights, with the agent-integration column being decisive: it is the only embeddable Android engine that lets an in-process agent execute JavaScript in the page and read results synchronously.

### 2. Agent Foundation: **native Kotlin agent engine (ported concepts from smolagents + browser-use)**

- From **smolagents** (Hugging Face): the `ToolCallingAgent` loop (reason → emit structured tool call → observe result → continue), the provider-agnostic model abstraction, human-in-the-loop interruption points, and manager/worker decomposition as an *evaluation pattern* (we adopted a single planner with specialist roles routed to different models — see AGENT_ARCHITECTURE.md §Multi-agent — rather than paying 3× API cost for separate agents, which our testing showed added latency without reliability gain at this task scale).
- From **browser-use**: compact indexed DOM-state serialization (elements get stable per-snapshot refs), hybrid perception with vision as a *policy-gated complement*, the discrete action registry with validation, and recovery-by-replanning after failed actions.
- Python was **not** embedded. Option A (local Python service) and Option C (remote agent runtime) were rejected on latency, deployment complexity, APK size, privacy (authenticated sessions would leave the device), and reliability grounds; Option B (port) was chosen after a 1:1 comparison (see COMPARISON table in FOUNDATION_COMPARISON.md §2).

### 3. Vision Foundation: **cloud VLM via the model router, strictly on-demand**

Screenshots are captured (`PixelCopy` on API 26+, `draw(Canvas)` fallback), downscaled to ≤1280 px JPEG, and sent as base64 data-URI `image_url` messages to a vision-capable model. The `VisionPolicy` gates when this happens: DOM ambiguity (0 interactive elements / canvas-heavy pages), last action failed, suspected verification challenge, explicit agent request (`request_vision`), or user setting "always". This honors the hybrid-perception mandate (§10–11 of the brief) while respecting the cost lesson documented in the 2026 browser-use ecosystem research.

### 4. Automation Foundation: **in-process injected JS runtime (`ActionExecutor`)**

A namespace-isolated runtime (`window.__cx*`, IIFE-wrapped, never touching page globals) is injected per observation pass. It tags interactive elements with stable `data-cx-ref` ids and returns a compact JSON snapshot. Actions (`click`, `type`, `select`, `scroll`, `click_at`, `find_text`, `find_element`, `extract`, …) are dispatched as generated JS through `evaluateJavascript` — real DOM events, real inputs, no synthetic fake progress. Navigation-level actions (back/forward/reload/tabs/downloads) use the WebView API surface. The action path is LLM → `ActionParser` → `ActionValidator` → `SafetyPolicy` → `ActionExecutor` — the model never touches the engine directly (AGENT_ARCHITECTURE.md §Safety boundary).

### 5. Model Abstraction: **unified OpenAI-compatible client + role-based ModelRouter**

`ModelProvider` interface with one wire implementation (`OpenAICompatibleProvider`) and four configured instances (Groq, OpenRouter, Hugging Face router, custom endpoint). `ModelRouter` maps roles — FAST, REASONING, VISION, STRONG, CHEAP — to provider+model pairs; all model IDs and base URLs are user-configurable in Settings (defaults provided; provider catalogs rotate, so nothing is hardcoded as immutable).

## Consciously rejected (failure-policy documentation)

| Rejected | Problem | Evidence | Alternative chosen |
|---|---|---|---|
| Embedding smolagents (Option A) | CPython + Playwright + Chromium cannot be bundled sanely in an APK; Proot/Termux hacks are fragile and insecure | smolagents browser demo requires Selenium/Helium desktop stack | Native Kotlin port of the agent loop |
| GeckoView | No in-process JS evaluation path for automation; +~100 MB APK; upstream repo churn (mozilla-mobile restructure) | Live repo status check, GeckoView API docs | WebView |
| Chromium fork | Multi-GB build infra, hours per build, unmanageable in this timeline | Chromium build docs; project sizes | WebView |
| CDP-driven control | Android WebView exposes DevTools socket for external debugging, not supported in-process automation | Chrome DevTools docs (remote debugging WebViews) | Injected JS runtime (CDP deferred to roadmap) |
| On-device VLM | 0.5–1.5 GB models, seconds of inference, worse UI understanding | Model cards / TFLite benchmarks | Cloud VLM on-demand |
| CodeAgent-style raw code execution | Letting the model execute generated code in the page is a prompt-injection superpower | smolagents security notes; our threat model | Validated JSON action protocol |

## Consequences / constraints accepted

1. The agent operates only on web content inside the app's WebView — it cannot drive native Android apps (that would require a system-level AccessibilityService; documented as a known limitation).
2. `file://` uploads from the agent are impossible by web security design (browsers refuse programmatic file-input population) — file uploads require human takeover, which is the correct security behavior anyway.
3. Vision quality depends on the configured vision model; Groq Llama-4 Scout is the default, but any OpenAI-compatible vision model works.

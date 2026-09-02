# Foundation Comparison — Comet-X

Date: 2026-09-02 (execution research phase)
Method: live web research (Groq/HF router/smolagents/browser-use/GeckoView/WebView-CDP checks) + architectural analysis. Each candidate scored against the mandated weights. Scores are justified in the notes; the final decision is recorded in `FOUNDATION_DECISION.md`.

## Scorecard weights (mandated)

| Category | Weight |
|---|---|
| Android compatibility | 15 |
| Browser capability | 15 |
| Agent integration | 15 |
| Vision integration | 10 |
| DOM / accessibility access | 10 |
| Performance | 10 |
| Security | 10 |
| License | 10 |
| Maintainability | 5 |

## 1. Browser foundation candidates

### 1.1 Android System WebView (Chromium, in-process)

| Category | Score | Notes |
|---|---|---|
| Android compatibility | 15/15 | System component, evergreen; API 26+ covers ~97% devices; zero APK bloat |
| Browser capability | 12/15 | Full Chromium Blink/V8, cookies, IndexedDB, downloads, popups, JS; missing: extensions, some DevTools surface |
| Agent integration | 12/15 | In-process `evaluateJavascript` gives real DOM control; `WebViewClient`/`WebChromeClient` hooks expose nav state; no external automation stack needed |
| Vision integration | 8/10 | `PixelCopy`/`draw(Canvas)` → bitmap is first-class; deterministic viewport screenshots |
| DOM / a11y access | 9/10 | Full DOM via injected JS (roles, ARIA, geometry); a11y tree exists but full `AccessibilityNodeInfo` traversal of WebView is gated on global accessibility services |
| Performance | 9/10 | Renders on system-optimized Chromium; in-process calls are sub-millisecond |
| Security | 9/10 | Chromium sandbox; Safe Browsing; we add: no `addJavascriptInterface`, file access off, cleartext off (localhost exception) |
| License | 10/10 | Apache-2.0 SDK surface; system component |
| Maintainability | 10/10 | Google ships updates; nothing to maintain |
| **Weighted total** | **94** | |

### 1.2 GeckoView (Mozilla)

| Category | Score | Notes |
|---|---|---|
| Android compatibility | 12/15 | Works, but adds ~70–100 MB APK and heavyweight init |
| Browser capability | 13/15 | Full Gecko engine |
| Agent integration | 5/15 | Page scripting requires WebExtension-style APIs; no `evaluateJavascript` equivalent for arbitrary automation; agent loop would fight the framework |
| Vision integration | 7/10 | `CompositorController` capture possible |
| DOM / a11y access | 6/10 | Gecko a11y session APIs are complex and under-documented |
| Performance | 6/10 | Second engine in memory; cold start penalty |
| Security | 8/10 | Good sandbox; smaller reviewer surface than WebView |
| License | 9/10 | MPL-2.0, file-level copyleft, acceptable |
| Maintainability | 4/5 | mozilla-mobile repos restructured into monorepo (2024); churn risk |
| **Weighted total** | **70** | |

**Evidence note:** live research confirms GeckoView remains the engine behind Fenix/Firefox for Android, but the GitHub `mozilla-mobile/firefox-android` repo carries an archive warning banner and development moved into the mozilla-firefox monorepo. Integration cost for an agent-heavy product is high because automation must go through extension messaging rather than in-process JS.

### 1.3 Chromium source fork (Vanadium/Bromite style)

| Category | Score | Notes |
|---|---|---|
| Android compatibility | 6/15 | Requires Chromium build infra (multi-GB toolchain, hours per build) |
| Browser capability | 15/15 | Full |
| Agent integration | 5/15 | Changes live in C++/Java browser tree; slow iteration |
| Vision integration | 6/10 | Possible, deep work |
| DOM / a11y access | 9/10 | Full |
| Performance | 9/10 | Best possible |
| Security | 10/10 | Full control of hardening |
| License | 8/10 | BSD-3 + component licenses; "Chromium" trademark constraints |
| Maintainability | 1/5 | Upstream tracking is a full-time job |
| **Weighted total** | **69** | |

**Feasibility verdict:** cannot be built or iterated inside this sandbox timeline; rejected on feasibility, not merit.

### 1.4 Custom Tabs / external browser

Not a browser you can embed or control — no DOM access, no lifecycle control. **Rejected immediately.**

## 2. Agent foundation candidates

### 2.1 Hugging Face smolagents (Python)

Live research (Sept 2026): smolagents is a minimalist HF library (~1,000 lines of core agent logic) with `ToolCallingAgent` (structured tool JSON) and `CodeAgent` (generates Python code), multi-agent manager/worker orchestration, model abstraction across providers, and an official browser-automation notebook built on Selenium/Helium. Its browser control is desktop-Python (Selenium/Playwright/Playwright+Helium).

| Criterion | Finding |
|---|---|
| Can CPython+deps be embedded in an Android app? | No. Playwright/Selenium require a desktop Chromium + driver process; Termux-style Proot hacks are fragile, 100s of MB, and a security liability |
| Reusable concepts | ToolCallingAgent loop (think→act JSON→observe), model abstraction, multi-agent manager/worker, human-in-the-loop interruption, tool registry |
| Reusable code | None directly (MIT license, but Python) |
| Decision | **Port the concepts into a native Kotlin engine** (see AGENT_ARCHITECTURE.md). CodeAgent-style raw code execution inside the page is rejected for security: we do not let the model run arbitrary JS blobs; it emits a validated JSON action protocol instead |

### 2.2 browser-use (Python)

Live research (Sept 2026): browser-use is the dominant open-source Python browser-agent library (Playwright-based). Its published architecture and the Nov 2025 arXiv paper "Building Browser Agents: Architecture, Security" confirm: agents perceive via **hybrid DOM-state + screenshots**, act through a **discrete action registry**, and recover through re-observation. Community consensus (2026): "with vision enabled, screenshots plus large DOM snapshots get expensive fast" — perception must be policy-gated.

| Criterion | Finding |
|---|---|
| Embeddable on Android? | No (Playwright + Node + Chromium required) |
| Reusable concepts | Numbered/compact DOM-state serialization; hybrid observation (DOM first, vision on demand); action registry with validation; task recovery via re-plan; loop guards |
| Reusable code | None directly (MIT, but Python + Playwright) |
| Decision | **Port the concepts** — our `DomExtractor` emits a compact indexed element snapshot; `VisionPolicy` fires screenshots only on ambiguity/failure/challenge |

### 2.3 Native Kotlin agent engine (in-app)

| Category | Score |
|---|---|
| Android compatibility | 15/15 (in-process, lifecycle-aware) |
| Agent integration | 13/15 (direct access to WebView, executor, UI) |
| Latency | no IPC, no server hop (9/10) |
| Privacy | page content stays on device except compact observations sent to chosen LLM provider (9/10) |
| Reliability | no Python runtime to babysit (9/10) |
| APK size | +0 MB agent runtime (10/10) |
| **Decision** | **Selected.** Port smolagents/browser-use concepts, do not embed runtimes |

### 2.4 Desktop agent servers (Option C, local host / cloud sidecar)

A server-side agent driving a cloud browser (e.g., Playwright in the cloud) was evaluated: it breaks offline capability, leaks the user's authenticated session to a server (cookies would have to be uploaded — a hard privacy failure), and adds cost. **Rejected as primary; may be offered later as an opt-in mode.**

## 3. Automation layer candidates

| Candidate | Verdict | Reason |
|---|---|---|
| `evaluateJavascript` + injected runtime (namespace-isolated, ref-tagged elements) | **Selected** | In-process, no sockets, works on every page, real DOM events (click/type/scroll), sub-ms dispatch |
| Chrome DevTools Protocol (CDP) to WebView | Evaluated, deferred | In-process CDP to Android WebView is not a supported surface; the DevTools socket targets external clients and requires debugging enabled. Revisit as a future enhancement for network interception |
| WebDriver / Appium | Rejected | External-automation protocol; requires driver binaries and server loop — wrong architecture for an in-app agent |
| AccessibilityService-driven control | Partially used | Great for native-app automation; for web content the DOM+ARIA extraction gives the same semantics without requiring the user to enable a global service. UI takeover uses real touch, satisfying "human-like control" |

## 4. Vision candidates

| Candidate | Verdict | Reason |
|---|---|---|
| Cloud VLM, on-demand (Groq Llama-4 Scout/Maverick, OpenRouter vision models, HF router) | **Selected** | High accuracy on buttons/menus/modals/verification screens; ~1–3 s latency; zero APK cost; strictly policy-gated (browser-use lesson: screenshots every step is expensive) |
| On-device VLM (TFLite/ONNX MobileVLM class) | Rejected for v1 | +0.5–1.5 GB models, multi-second inference on mid devices, materially worse on dense UIs. Roadmap item |
| Pure coordinate vision (screenshot-only agent) | Rejected as primary | Fragile on responsive layouts; DOM+ARIA is cheaper and more precise. Vision is complementary, not primary |

## 5. Model abstraction

All four mandated providers (Groq, OpenRouter, Hugging Face, generic OpenAI-compatible) expose OpenAI-style `POST /v1/chat/completions` — verified live:

- Groq: `https://api.groq.com/openai/v1/chat/completions` (Llama-4 Scout/Maverick provide vision).
- OpenRouter: `https://openrouter.ai/api/v1/chat/completions`.
- Hugging Face: Inference Providers ship a **drop-in OpenAI-compatible router** (`https://router.huggingface.co/v1/chat/completions`).
- Generic: any user-supplied base URL.

**Decision:** one hardened `OpenAICompatibleProvider` base with per-provider endpoint/auth/defaults, plus a `ModelRouter` that maps roles (FAST / REASONING / VISION / STRONG / CHEAP) to provider+model. Model IDs are user-configurable because provider catalogs rotate (e.g., Groq's Maverick deprecation notice observed in research).

## 6. Summary matrix

| Role | Selected | Runner-up | Why not runner-up |
|---|---|---|---|
| Browser foundation | Android System WebView | GeckoView | Agent integration 5/15, APK +~100 MB, repo churn |
| Agent engine | Native Kotlin (ported concepts) | smolagents embedding | CPython+Playwright not viable on Android |
| Automation | Injected JS runtime | CDP | In-process CDP unsupported on Android WebView |
| Vision | Cloud VLM on-demand | On-device VLM | Size/latency/accuracy tradeoff |
| Model abstraction | OpenAI-compatible unified client | Per-SDK clients | All four providers are wire-compatible |

Full decision rationale: `FOUNDATION_DECISION.md`. License obligations: `LICENSE_ANALYSIS.md`.

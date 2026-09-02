# System Architecture — Comet-X

## Overview

Comet-X is a mobile-first AI agent browser: a real Android browser (Chromium WebView) with an autonomous agent layered over it. The differentiation is the agentic layer; the browser foundation is inherited, not rebuilt (see `../research/FOUNDATION_DECISION.md`).

```
                    ANDROID APP (com.cometx.browser)
                          |
        +-----------------+------------------+
        |                                    |
   BROWSER UI                           AI UI (AgentPanelController)
   (MainActivity,                          |  goal input, skills chips,
    TabManager,                            |  live log, Run/Pause/
    BrowserController)                     |  TakeControl/Stop/Resume
        |                                    |
        |                              AGENT ENGINE (AgentEngine)
        |                              state machine + coroutine loop
        |                                    |
        |                    +---------------+---------------+
        |                    |               |               |
        |               MODEL ROUTER   VISION POLICY    MEMORY
        |              (ModelRouter)   (VisionPolicy)  (MemoryStore)
        |                    |               |               |
        |        Groq / OpenRouter / HF /     |          session/user/
        |        Custom providers            |          browser memory
        |        (OpenAICompatible)          |
        |                                    |
   BROWSER CONTROL ENGINE  <---------- AGENT SINK (LiveWebViewSink)
        |                                    |
   +----+------+------+------+          evaluateJavascript + JS
   |    |      |      |      |          action runtime
  DOM  A11Y  SCREEN  META    JS
   (DomExtractor: ref-tagged      (ActionExecutor: click/type/
    compact element snapshots)     scroll/select/extract/…)
        |
   ACTION VALIDATOR + SAFETY POLICY  (model proposes → validator
        |                             disposes → human confirms when
   +------+------+------+             consequential)
   |      |      |      |
 CLICK  TYPE  SCROLL  NAVIGATE …
```

## Module map

| Package | Responsibility | Key classes |
|---|---|---|
| `ui` | Browser chrome, tabs, agent panel, settings | `MainActivity`, `TabManager`, `BrowserController`, `AgentPanelController`, `SettingsActivity` |
| `engine` | Agent state machine, prompting, action parsing, browser abstraction | `AgentEngine`, `AgentSink`/`LiveWebViewSink`, `AgentPrompt`, `ActionParser` |
| `perception` | Hybrid observation: DOM, screenshots, metadata, challenge detection, vision gating | `DomExtractor`, `Screenshotter`, `PageObservation`, `VisionPolicy`, `ChallengeDetector` |
| `automation` | Action execution + local test infra | `ActionExecutor`, `LocalTestServer` |
| `security` | The safety spine | `ActionValidator`, `SafetyPolicy`, `PromptInjectionDetector`, `SecureStore` |
| `ai` | Multi-provider model access + config | `LlmProvider`, `OpenAICompatibleProvider` + Groq/OpenRouter/HF/Custom, `ModelRouter`, `SettingsRepository` |
| `memory` | Session / browser / user memory | `MemoryStore` |
| `skills` | Declarative skill registry | `SkillRegistry`, `Skill`, `assets/skills/*.json` |
| `util` | HTTP, JSON, logging | `Http`, `Json`, `Logx` |

## Threading model

- **Main thread**: all WebView operations (creation, navigation, `evaluateJavascript`), UI updates.
- **Engine coroutine** (`SupervisorJob + Dispatchers.Main.immediate`): the agent loop; suspends on LLM calls (`Dispatchers.IO` inside the provider) and human gates (`CompletableDeferred`), resumes on the main dispatcher for WebView access.
- **Gate safety**: `resume()`/`confirm()` may fire before the engine reaches a gate; arrivals are recorded under `gateLock` and consumed order-independently (red-team fix, see `../security/SECURITY_AUDIT.md`).
- **Hard caps**: step budget (4–60), pause timeout (15 min), wait bounds (100 ms–15 s) — the engine cannot hang or run unbounded.

## Data flow of one agent step

1. `sink.observe()` → injects the extractor IIFE, returns a compact `PageObservation` (≤160 elements, refs re-tagged per pass).
2. `PromptInjectionDetector.detect()` scans page text → `injectionSignals` merged into the observation; system prompt gets a warning.
3. `ChallengeDetector.evaluate()` → if a human-verification surface is detected the engine pauses and asks for takeover (never attempts to solve).
4. `VisionPolicy.shouldCapture()` → on trigger, `sink.screenshotBase64()` returns a downscaled JPEG (≤1024 px, q72) for the VLM.
5. `AgentPrompt` builds system+user messages (schema, rules, skill, memory, history, observation marked UNTRUSTED).
6. `ModelRouter.chatWithFallback(REASONING, …)` → provider call; on failure falls back across ready providers.
7. `ActionParser.parse()` (+`salvage`, + one repair round-trip) → strict JSON action.
8. `ActionValidator.validate()` → schema, refs, bounds, URL policy.
9. `SafetyPolicy.assess()` → NONE / CONFIRM (dialog) / BLOCK.
10. `sink.execute()` → real DOM events via generated JS, or WebView navigation APIs.
11. Result + observation delta recorded to history, memory, and the UI log; loop-protection signature updated; repeat → replan nudge.

## Browser session management

- Cookies: persistent `CookieManager` (flushed on pause/finish); third-party cookies default OFF, user-configurable.
- Storage: per-origin localStorage/IndexedDB handled by the engine; "Clear browsing data" wipes cookies + storage + cache.
- Profiles: single persistent profile in v1. True multi-profile isolation requires process-level WebView data directories — documented as a known limitation and roadmap item (`README.md §Limitations`).
- Downloads: `DownloadManager` with confirmation gates for executable types.
- Popups: `onCreateWindow` captured into real tabs.

## Design decisions worth remembering

1. **No `addJavascriptInterface`** — the page never receives a native object; all agent↔page traffic is `evaluateJavascript` with JSON-object returns (no string double-escaping bugs).
2. **`AgentSink` abstraction** — the engine never touches `WebView` directly; headless integration tests drive the *real* loop through a scripted sink.
3. **Refs are per-observation** — `data-cx-ref` attributes are stripped and re-issued on every pass; the validator rejects refs not present in the current observation, which neutralizes stale-ref errors and most TOCTOU misuse.
4. **Observations are compact** — element text is trimmed (80 chars), samples capped (600 chars), element count capped (160); this respects the token budget and keeps step latency low.

# Comet-X 🚀

**A mobile AI agent browser for Android.** Give the browser a task in natural language — the agent observes the page (DOM + accessibility semantics + screenshots + metadata), decides, acts in a real browser, verifies the result, and continues until the task is done or a human is needed.

> Not a chatbot wrapped around a WebView: every action the agent reports is executed against the live page through a validated, policy-gated action pipeline.

## What it actually does

| Capability | How it works |
|---|---|
| Real browser | Chromium WebView, tabs, popups→tabs, persistent cookies, downloads (gated), file chooser, history/back/forward, dark UI |
| Agent loop | observe → understand (injection/challenge scan) → (vision?) → LLM → parse → validate → policy → confirm? → execute → verify |
| Hybrid perception | compact ref-tagged DOM snapshots, page metadata, policy-gated VLM screenshots, ARIA/role semantics |
| Real automation | clicks (full pointer-event sequences), typing (React/Vue-safe native setters), selects, scrolling, find-text, find-element, extraction (text/links/tables), zoom, clipboard |
| Multi-model | Groq, OpenRouter, Hugging Face router, any OpenAI-compatible endpoint — one provider abstraction |
| Model routing | FAST / REASONING / VISION / STRONG / CHEAP roles, user-configurable, cross-provider fallback |
| Human takeover | Pause / Take Control / Resume at any moment; agent re-observes your changes and continues |
| Verification challenges | reCAPTCHA/hCaptcha/Cloudflare/MFA/rate-limit detection → pause → **you** solve it → resume (no circumvention, ever) |
| High-risk gates | purchases, password fields, deletions, sends, agreement clicks, executable downloads → confirmation dialog |
| Prompt-injection defense | 11-rule detector, UNTRUSTED content marking, no native JS bridge, no key access for the agent, URL exfil gates |
| Memory | session task log, browser state, user facts (view / delete / clear / disable) |
| Skills | research, shopping, travel, forms, comparison, extraction, productivity, downloads, general-web — declarative JSON, auto-selected |
| Self-test | built-in loopback test server: normal / dynamic / difficult / injection / phish / challenge / long / tarpit pages |

## Quick start

1. Install the APK (release artifact or `./gradlew assembleDebug`).
2. Open **Settings → AI Providers**, paste a key, press **Save**, then **Test**. Defaults are preconfigured — a key alone works. Enable extra providers to build a fallback chain (ordered with ▲/▼); if one fails, the next serves the request automatically.
3. Browse somewhere, tap **Ask Agent**, describe the task ("find the cheapest hotel in Ahmedabad for Friday").
4. Watch the log; use **Take Control** whenever you want the wheel — logins, CAPTCHAs, payments, judgment calls — then **Resume**.

Build details: [docs/development/BUILD.md](docs/development/BUILD.md).

## Changelog

### v1.1.0
- **Explicit Save per provider** — fields no longer persist invisibly on focus loss; a dirty-state indicator and Save ✓ toast make state obvious
- **Test button per provider** — real two-step connectivity check (model catalog + live `pong` completion) with latency; result badge persisted (✓ Working / ✗ Failed)
- **Model selection rebuilt** — per-role dropdowns: `Default (recommended)` → works with a key alone, live-fetched catalog (`Fetch models`), and `Custom…` manual entry; replaces five raw text boxes
- **User-ordered fallback chain** — replace single "active provider" with enabled-toggle + priority ordering (▲/▼); ModelRouter walks the chain and fails over automatically
- **Self-run URL normalization** — `localhost:11434` → `http://localhost:11434/v1` with a live "will be saved as" hint; saved base URLs are now actually applied at runtime (previously silently ignored — the reported "URL seems off" bug)
- Fixed random build failure in release-keystore generation (`nextInt` off-by-two)
- 86 unit tests passing (incl. new `ChainAndUrlTest`)

### v1.0.0
- Initial release: real WebView browser + autonomous agent loop + hybrid perception + multi-provider routing + human takeover + challenge detection + skills + memory + local red-team test server.


## Architecture in one line

`LLM proposes → ActionParser → ActionValidator → SafetyPolicy → (human confirms) → ActionExecutor` — the model never touches the engine directly.

Full documentation:

- [docs/research/FOUNDATION_COMPARISON.md](docs/research/FOUNDATION_COMPARISON.md) — weighted scorecard of browser/agent/vision/automation foundations
- [docs/research/FOUNDATION_DECISION.md](docs/research/FOUNDATION_DECISION.md) — what was selected and what was rejected (and why)
- [docs/research/LICENSE_ANALYSIS.md](docs/research/LICENSE_ANALYSIS.md)
- [docs/architecture/SYSTEM_ARCHITECTURE.md](docs/architecture/SYSTEM_ARCHITECTURE.md) · [AGENT_ARCHITECTURE.md](docs/architecture/AGENT_ARCHITECTURE.md) · [VISION_ARCHITECTURE.md](docs/architecture/VISION_ARCHITECTURE.md)
- [docs/security/THREAT_MODEL.md](docs/security/THREAT_MODEL.md) · [SECURITY_AUDIT.md](docs/security/SECURITY_AUDIT.md) · [RED_TEAM_REPORT.md](docs/security/RED_TEAM_REPORT.md)
- [docs/testing/TEST_REPORT.md](docs/testing/TEST_REPORT.md)

## Privacy

- API keys: encrypted with the Android Keystore (AES-256/GCM), never bundled, never logged.
- Page content: compact observations (and policy-gated screenshots) go **only** to the provider you configure. Memory stays on-device.
- No analytics, no tracking, no telemetry, no baked-in credentials.
- Cleartext HTTP is blocked system-wide except the loopback self-test server.

## Security model (summary)

Web content is **untrusted data**, never instructions. The agent's only output channel is a validated JSON action protocol. Consequential actions require you. Verification challenges pause the agent for you to solve. See [THREAT_MODEL.md](docs/security/THREAT_MODEL.md) for the full model and [RED_TEAM_REPORT.md](docs/security/RED_TEAM_REPORT.md) for the adversarial pass that hardened this build.

## Known limitations (v1, honest)

1. No on-device model — LLM calls need a provider key and connectivity.
2. The agent drives **web content only**; native Android app automation would require a system AccessibilityService (roadmap).
3. Single persistent browser profile (multi-profile isolation is process-level work; roadmap).
4. Vision quality depends on the configured VLM; screenshots are sent to that provider when the vision policy fires.
5. File uploads from the agent are impossible by web-security design (browsers refuse programmatic file-input population) — use Take Control for uploads.
6. Agent-side `open_tab`/`switch_tab`/`download` are validated but only partially wired in v1 (the browser layer supports all of these for human actions).

## Attribution

Design concepts adapted (no code copied) from Hugging Face **smolagents** (tool-calling agent loop, model abstraction, HITL) and **browser-use** (DOM-state serialization, hybrid DOM+vision perception, action registry, recovery), both MIT. Engine: Android System WebView (Chromium). See [LICENSE_ANALYSIS.md](docs/research/LICENSE_ANALYSIS.md).

## License

MIT — see [LICENSE](LICENSE).

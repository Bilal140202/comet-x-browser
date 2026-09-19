# Comet-X 🚀

**A mobile AI agent browser for Android.** Give the browser a task in natural language — the agent observes the page (DOM + accessibility semantics + screenshots + metadata), decides, acts in a real browser, verifies the result, and continues until the task is done or a human is needed.

> Not a chatbot wrapped around a WebView: every action the agent reports is executed against the live page through a validated, policy-gated action pipeline.

**v2.0.0 — the full-browser release.** Comet-X now matches a daily-driver privacy browser feature-for-feature (native ad/tracker blocking, cosmetic filtering, YouTube ad suppression, bookmarks, history, downloads, incognito, find-in-page, reader view, translate, desktop mode, print, session restore, Material You) while keeping the agent core untouched. Built to go head-to-head with Perplexity's Comet — on your phone, with your model, blocking ads along the way.

## The agent (what makes it Comet-X)

| Capability | How it works |
|---|---|
| Agent loop | observe → understand (injection/challenge scan) → (vision?) → LLM → parse → validate → policy → confirm? → execute → verify |
| Hybrid perception | compact ref-tagged DOM snapshots, page metadata, policy-gated VLM screenshots, ARIA/role semantics, Set-of-Marks overlays |
| Real automation | clicks (full pointer-event sequences), typing (React/Vue-safe native setters), selects, scrolling, extraction (text/links/tables), tab verbs (open/switch/close) |
| Multi-model | Groq, OpenRouter, Hugging Face router, any OpenAI-compatible endpoint — one provider abstraction, live model discovery, capability negotiation, AUTO model selection |
| On-device AI | llama.cpp runtime built in (arm64): download a GGUF model once (SHA-256 verified, 4-way parallel background download) and the agent runs **fully offline** — no API key, nothing leaves the phone |
| In-browser transformer AI (v2.1.0) | the Transformers.js model zoo running inside Comet-X's own browser engine: bundled Transformers.js 4.3.0 + ONNX Runtime Web (WASM), int4 models (SmolLM2-360M / Qwen2.5-0.5B / Qwen2.5-1.5B) fetched once from Hugging Face into the browser cache, then offline — chain-ranked after llama.cpp, crash-contained, text-only |
| Background agent mode | hand the task to an isolated headless engine and watch the **notification bar**: step counter, progress, last action; Approve/Deny high-risk actions and answer agent questions straight from the shade; network drops park the task and auto-resume; a reboot marks the task INTERRUPTED — no crash-looping, ever |
| Human takeover | Pause / Take Control / Resume at any moment; agent re-observes your changes and continues |
| Verification challenges | reCAPTCHA/hCaptcha/Cloudflare/MFA/rate-limit detection → pause → **you** solve it → resume (no circumvention, ever) |
| High-risk gates | purchases, password fields, deletions, sends, agreement clicks, executable downloads → confirmation dialog |
| Prompt-injection defense | 11-rule detector, UNTRUSTED content marking, no native JS bridge, no key access for the agent, URL exfil gates |
| Memory & skills | session task log, user facts, declarative skill library with /grill-me interview recorder + player |

## The browser (v2.0.0 full layer)

| Capability | How it works |
|---|---|
| Native ad & tracker blocking | Network-level filtering in `shouldInterceptRequest` using the bundled StevenBlack unified hosts list (~80k domains, MIT) plus ~70 curated URL-pattern rules (ad exchanges, analytics pixels, popup networks). Every blocked request is counted per page, per session and all-time, with live transparency dialogs and a per-site allowlist |
| Cosmetic filtering | ~1,200 sanitized element-hiding selectors (validated EasyList generic-hide subset, CC-BY-SA-3.0) injected at document start, so ad containers never paint; a MutationObserver keeps hiding dynamically inserted ads |
| YouTube ad suppression | Prune-before-load: ad structures are deep-pruned from player responses before the player parses them (uBO-scriptlet technique), skip buttons are clicked on sight, and a strictly-scoped fallback fast-forwards confirmed in-stream ads while restoring your exact playback rate. On by default, with a Settings toggle |
| HTTPS-first | Main-frame `http://` navigations upgrade to `https://` automatically (local hosts skipped); certificate failures raise an explicit dialog — never a silent downgrade |
| Privacy headers & cookies | Do-Not-Track + Global-Privacy-Control headers on every main-frame load; third-party cookies blocked by default; one-tap data clearing |
| Incognito tabs | Skip history, bookmarks, previews and session restore; marked in the tab grid; SSL errors auto-cancel |
| Bookmarks, history, downloads | SQLite-backed bookmarks and history screens (tap to open, long-press to manage), plus a Downloads screen over the system DownloadManager |
| Find in page | Inline Material bar: debounced `findAllAsync`, prev/next, live n/total counter |
| Reader view | Clean, theme-aware article rendering powered by Mozilla Readability (Apache-2.0) with byline and reading time; the original page is restored exactly on toggle-off and nothing is re-fetched |
| Translate page | One-tap full-page translation through Google's `translate.goog` proxy in the same tab — no API key — with a View-original way back |
| Desktop site, per tab | Desktop UA derived from the device's own WebView engine version (never a stale hardcoded one); one-tap toggle and reload |
| Print / Save as PDF | The Android printing framework renders the page; the destination picker offers Save as PDF |
| Add to home screen | Pin any site to your launcher with its favicon |
| Real browser plumbing | Tabs with a Material grid switcher (live page previews), popups→tabs, downloads with cookie forwarding, file upload, camera/microphone + geolocation prompts, share targets, full-screen video, custom search engines (Google/DDG/Brave/Startpage/Bing/Wikipedia + your own `%s` engines) |
| Comet Start page | Gradient wordmark, agent call-to-action, search pill honoring your engine, dynamic shortcut tiles (most-visited blended with defaults, or your own list), live blocking-stats card |
| Session restore | Non-incognito tabs and their selection survive process death and app relaunches |
| Material You (v2 UI) | Material 3 Expressive token system, dynamic wallpaper-based color on Android 12+ (toggleable), system/light/dark app themes, algorithmic web darkening option, web text size 50–200 %, force-enable zoom, pull-to-refresh with correct scroll handling |
| Filter list updates | The hosts blocklist and cosmetic rules refresh themselves about once a week (or on demand) with validated, atomically swapped downloads that apply without a restart |

## Quick start

1. Install the APK (release artifact or `./gradlew assembleDebug`).
2. Open **Settings → AI Provider**, paste an API key, press **Test & Enable**. That's it: Comet-X discovers the provider's live model catalog, checks what each model supports (JSON / tools / vision), picks the best agent-compatible model automatically (**AUTO**) and is ready. Prefer zero keys? **Settings → On-device AI** downloads a GGUF model (background, resumable, SHA-256 verified) and the agent runs offline. Want the Transformers.js model zoo instead? **Settings → In-browser AI (Transformers.js)** — pick a model, the browser engine runs it in WebAssembly.
3. Browse somewhere, tap **Ask Agent**, describe the task ("find the cheapest hotel in Ahmedabad for Friday") — or tap **🛰 Run in background** and watch the notification bar while the isolated engine does the clicking for you.
4. Blocking is on from your first page load. **Menu → Blocked on this page** shows the running counts; exempt sites from the same dialog or from Settings.

Build details: [docs/development/BUILD.md](docs/development/BUILD.md).

## Honesty & scope

- Blocking is domain/path based — it is not a full filter-list DSL (no EasyList syntax). Host-like substrings inside unrelated domains can rarely be caught by the conservative URL-pattern rules; the domain list itself matches on real domain boundaries.
- In-stream video ads (including YouTube's) are served from the same endpoints as the video itself, so network-level removal is impossible on WebView — the client-side suppression layer (prune-before-load + auto-skip + scoped fallback) is the same technique maintained scriptlet blockers use, and it is an arms race.
- Incognito shares the WebView cookie jar with normal tabs; history, bookmarks, previews and session persistence are skipped.
- DNT/GPC headers apply to main-frame requests; WebView does not expose per-subresource header injection.
- Vision is not served by the in-browser transformer engine (text-only models); multimodal steps transparently route to a vision-capable provider.
- The agent never circulates CAPTCHAs and never touches your keys; high-risk actions always stop for a human.

## Security posture (unchanged since v1.0)

No native JS bridge objects, no key access for page content, file/content access disabled, mixed content never, popups require a user gesture, non-http(s) handoffs require an explicit human decision, executable downloads are gated, prompt-injection scanning on every observation, agent downloads and external launches are policy-checked. The background engine runs in an isolated headless WebView that is structurally unreachable from the user's tab strip, and nothing of ours runs after a reboot (START_NOT_STICKY, no boot receiver — no "App keep closing").

## Licenses & attribution

- Comet-X code: same owner as Zerium; browser-layer sources adapted from Zerium (GPL-3.0).
- Bundled hosts list: [StevenBlack/hosts](https://github.com/StevenBlack/hosts), MIT.
- Cosmetic selectors: validated EasyList generic-hide subset, CC-BY-SA-3.0 (attribution in the asset header).
- `assets/readability.js`: Mozilla Readability v0.6.0, Apache-2.0 (header in the asset).
- Android, androidx, Material Components: Apache 2.0 / their respective licenses.

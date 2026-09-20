# FUNCTIONALITY LOCK — Phase 1

> OPERATION COMET AURORA · contracts extracted from source at c9afaf4 (v1.3.0).
> Rule: before AND after the redesign, every contract below must hold.
> Automated gate: `./gradlew testDebugUnitTest` (172 tests, incl. `AppSmokeTest`)
> + release build must succeed.

---

## A. VIEW-ID CONTRACT (layout ↔ Kotlin)

Any redesigned layout MUST keep these IDs with compatible types.
Type rule: a declared type may be swapped only for a **subclass** used by `findViewById<T>`.

### MainActivity.kt
| ID | Used as | Contract |
|---|---|---|
| `webContainer` | `ViewGroup` (FrameLayout) | WebView attach/detach point for TabManager |
| `urlBar` | `EditText` | URL entry, IME action GO → `loadUserUrl`, text synced from page load unless focused |
| `progress` | `ProgressBar` | page progress, VISIBLE 1–99, GONE otherwise |
| `btnBack` / `btnForward` / `btnReload` | `Button` | webview goBack/goForward/reload |
| `btnTabs` | `Button` | opens tab switcher |
| `btnMenu` | `Button` | opens menu (anchor = this view) |
| `challengeBanner` | **`LinearLayout`** (cast) | visibility toggled GONE/VISIBLE |
| `challengeText` | `TextView` | challenge detail text |
| `btnChallengeTake` | `Button` | engine.takeControl + banner hide + toast |
| `btnChallengeResume` | `Button` | banner hide + engine.resume(null) |

### AgentPanelController.kt
| ID | Used as | Contract |
|---|---|---|
| `agentPanel` | **`LinearLayout`** | visibility VISIBLE↔GONE (expand/collapse) |
| `askBar` | **`LinearLayout`** | visibility GONE↔VISIBLE inverse of panel; click = expand |
| `askBarText` | `TextView` | "Ask Agent" / REC status / step-count routing |
| `statusDot` | `View` | background color per engine state |
| `statusText` | `TextView` | state label incl. message |
| `stepText` | `TextView` | step counter text (cleared on state change) |
| `goalInput` | `EditText` | task input; `/grill-me` prefix routing |
| `skillChips` | **`LinearLayout`** | declarative skill chips (children added/removed programmatically) |
| `userSkillChips` | **`LinearLayout`** | user skill chips |
| `userSkillsScroll` | **`HorizontalScrollView`** | VISIBLE iff user has skills |
| `userSkillsCaption` | `TextView` | caption, VISIBLE iff user has skills |
| `btnRun` | `Button` | runFromInput (goal validation, skill match) |
| `btnRecord` | `Button` | record start/stop flow; label swaps "🎙 Record" ↔ "■ Stop & Save" |
| `btnGrillMe` | `Button` | /grill-me interview |
| `btnTakeControl` | `Button` | engine.takeControl; VISIBLE iff RUNNING |
| `btnResume` | `Button` | engine.resume(answer or null); VISIBLE iff paused |
| `btnStop` | `Button` | interview cancel OR engine.stop; VISIBLE iff running/paused/interview |
| `answerRow` | **`LinearLayout`** | VISIBLE during ask-user/interview question |
| `answerInput` | `EditText` | answer text; hint = question (≤60 chars) |
| `logList` | **`ListView`** | transcript, stackFromBottom, 200-line cap |
| `btnOpenAgent` | `Button` | expand() |
| `btnPanelClose` | `Button` | collapse() |
| `btnAnswerSend` | `Button` | routes answer to interview or engine.resume |

### item_tab.xml — DEAD LAYOUT (verified AURORA-VERIFY-1)
Nothing inflates `R.layout.item_tab`; the real tab switcher is `MainActivity.showTabDialog()`
(AlertDialog + `android.R.layout.simple_list_item_1`, tap = switch, long-press = close,
"New tab" positive button, title `"Tabs (${items.size})"`). The file may be redesigned or
repurposed freely as long as it keeps compiling; the *behavioral* contract is the dialog flow.

### item_log.xml
`logLine` (TextView) — bound via `ArrayAdapter(activity, R.layout.item_log, R.id.logLine, …)`.
**ID is passed as a resource int; the view tree must keep it.**

## B. COLOR CONTRACT (Kotlin references — verified)

Kotlin `getColor` set: `text_primary, text_secondary, accent_bright, background, danger,
success, warning` (AgentPanelController/MainActivity) + `BrowserController` uses `background`
(WebView background) — BrowserController is IN the migration scope for its 2 AlertDialogs.
XML-referenced: `surface, surface_high (bg_panel_input — also programmatic via SettingsActivity
getDrawable), chip_bg, divider, accent, accent_dim, text_primary, text_secondary, background,
warning, danger, success`.
→ All remain defined in BOTH light and dark configs; values map to the new token system
(see alias map in COMET_MATERIAL_EXPRESSIVE.md).

## B2. STRING CONTRACT
`app_name` (manifest), `settings_title` (manifest; runtime title is hardcoded
"Comet-X Settings"), `url_hint` (layout), `ask_agent` (layout + AgentPanelController
`getString(R.string.ask_agent)`). None may be removed; text may be reworded keeping
routing semantics (menu items matched by literal CharSequence equality in `showMenu`).

## C. FEATURE CONTRACTS

### F-01 Browser navigation
- Entry: btnBack/btnForward/btnReload/urlBar; hardware back = webview-back → panel-collapse → exit.
- Do not break: `loadUserUrl` scheme/search normalization, URL sync from `onPageMeta`, progress bar visibility.
- Test: AppSmokeTest.urlBarLoads + manual smoke (open example.com).

### F-02 Tabs
- newTab/close/switch/closeCurrent/destroyAll; retained state; `onNewIntent` opens VIEW-intent URLs as tabs; scheme gate (http/https only, else toast).
- Test: AppSmokeTest + TabManager behavior unchanged (no code edits planned).

### F-03 Menu actions
- New tab / Close current tab / Clear browsing data (confirm dialog; cookies+storage+cache, NOT agent memory) / Agent self-test (loopback :8081) / Settings.

### F-04 Agent run loop UI
- Run button validates goal; skill match (selected chip > regex match); engine state → statusDot/statusText mapping (IDLE/RUNNING/AWAITING_CONFIRM/AWAITING_USER/COMPLETED/FAILED/CANCELLED) and refreshButtons visibility matrix; step text; transcript log (`✓ ✗ →` lines preserved semantically).
- Confirm-gate dialog (Allow/Deny; cancel = deny; isFinishing auto-deny).
- Ask-user: answerRow reveal, hint=question, focus, send routes to engine.resume.
- Challenge banner reveal on `onChallengeDetected` + panel expand.

### F-05 Skill Recorder
- Record intro dialog → start → REC routing in askBarText with live step count → Stop & Save → name/description dialog → review (summary + editable JSON) → save/discard; invalid-JSON fallback saves original capture.
- Do not break: recorder bridge wiring, `recorder.onNavigation` feed, isFinishing guards.

### F-06 Your skills
- Chips row (visible iff non-empty); tap/long-press = menu: Run now (pre-flight checks: engine not running, not recording, confirm dialog) / Details / Edit JSON / Export (clipboard) / Delete (confirm).

### F-07 /grill-me
- Start (button or `/grill-me` prefix in goal); question → answerRow; status "🎙 Interview"; draft review dialog (Save/Revise/Discard; revise loop with feedback dialog); finishReview on all exits.

### F-08 Skill player dialogs
- Sensitive value ask (password-transformed, never stored); confirm replay step (Allow/Deny); progress lines in transcript.

### F-09 Settings — AI Provider
- Per provider (groq/openrouter/huggingface/custom): saved-key status, tag line, Enabled checkbox (fallback chain, persists immediately), key field (password transform, hint states), custom base-URL field + normalizer live hint, AUTO label, Test & Enable (SAVES first, runs diagnostics, result dialog, rebuild UI), dirty dot + "Save & Test" label swap, Advanced disclosure (mode spinner AUTO/MANUAL, per-role spinners with Custom… dialog, cache forget).

### F-10 Settings — the rest
- AI diagnostics (compatibility self-test with provider chooser; AI event log view/clear); Agent behavior (max steps 4–60 + explainer, confirm high-risk, memory enabled, skill AI fallback, vision mode AUTO/ALWAYS/OFF); Browser (homepage autosave-on-focus-loss, third-party cookies); Memory (summary, view, clear with confirm).

### F-11 Persistence keys
`SettingsRepository` keys and their call sites are locked — UI may not rename/remove any `settings.*` accessor usage.

### F-12 App-level
- Deep links (http/https VIEW → tab), theme applied app-wide (`Theme.CometX`), `allowBackup=false`, INTERNET+NETWORK_STATE only permissions, network security config, RTL support flag.
- `android:windowSoftInputMode="adjustResize"` on MainActivity is load-bearing
  (goalInput/answerInput focus flows). Keep it.
- Back-press chain: panel visible → collapse; webview canGoBack → goBack; else exit.

## C2. MIGRATION SCOPE NOTE (verified)
Dialog inventory = 24 `android.app.AlertDialog` sites: MainActivity ×2 (clear-data,
tab switcher), AgentPanelController ×12, SettingsActivity ×10, **BrowserController ×2
(external-app handoff, risky-download confirm)**. All migrate to MaterialAlertDialogBuilder.
PopupMenu (menu) stays framework; its 5 item-title literals are behavior-matched.

## D. REGRESSION MATRIX (post-overhaul gate)

| # | Scenario | Verify |
|---|---|---|
| 1 | App cold start | webview + askBar visible, panel gone |
| 2 | Open URL / search term | loads, urlbar syncs |
| 3 | Back / forward / reload | work |
| 4 | Tab: new / switch / close (btn + long-press) | works, count in sheet title |
| 5 | Menu → all 5 items | work |
| 6 | Agent run against local self-test pages | state chip + steps + transcript render |
| 7 | Confirm gate Allow/Deny/cancel | correct resume behavior |
| 8 | Ask-user flow | row shows/hides, answer routed |
| 9 | Record → review → save → chip appears → run | full loop |
| 10 | /grill-me → answer → draft → revise → save | full loop |
| 11 | Settings: every section renders; Test & Enable; advanced disclose | works |
| 12 | Challenge banner | shows/hides, take/resume |
| 13 | Rotation/config change | no crash (configChanges handles it) |
| 14 | Light mode + dark mode | both render, contrast holds |
| 15 | `testDebugUnitTest` | 172+ tests, 0 failures |
| 16 | `assembleRelease` | BUILD SUCCESSFUL |

---

# § v1.5.0 ADDENDUM — OPERATION COMET RELIABILITY (Set-of-Marks + Run Stats)

Additive contracts only; everything above remains binding.

## A. New view IDs

| ID | Type | Location | Contract |
|---|---|---|---|
| `statsText` | `TextView` | `activity_main.xml` (between control row and `answerRow`) | Hidden (`gone`) by default; shown once per run by `onRunStats`; hidden on RUNNING; text = "N / M steps · ~X tok · Ys · outcome". Must remain a `TextView` (no cast issues, but keep the type). |

## B. New behavioral contracts (do not break)

| # | Contract |
|---|---|
| SOM-1 | `som_overlay` pref (default **true**) gates `AgentSink.screenshotAnnotatedBase64`; OFF ⇒ plain `screenshotBase64()` path, no `MARKS:` legend, no prompt rule 12 — byte-identical v1.4.0 perception |
| SOM-2 | Badge number N ≡ element ref `eN` (`SomLayout`); badges drawn from the SAME `PageObservation` instance the model receives, never from a re-observation |
| SOM-3 | Annotation happens on the downscaled bitmap in Kotlin; page DOM is never modified beyond the existing `data-cx-ref` tagging |
| SOM-4 | `MARKS:` legend only on messages whose `imageBase64Jpeg` is non-null (§20-gated); vision-describe prompt mentions badge numbers |
| STAT-1 | `Listener.onRunStats(RunResult)` has a default no-op impl — external listener implementations must keep compiling |
| STAT-2 | Run stats emitted EXACTLY once per run (terminal done/fail/cancel; `statsEmitted` guard); `stop()` after completion must not re-emit |
| STAT-3 | Token figures are estimates (chars/4) and are displayed with a "~" prefix |

## C. Regression gate update

Baseline is now **185 tests / 20 suites** (was 172/18). `assembleRelease` must
produce versionCode 6 / versionName 1.5.0. Signing keystore: `app/keystore/`
(gitignored) — cert SHA-256 `970e0a30…` shared with v1.4.0; persistent backup
at `~/keystore-backup/cometx-keystore-v1.5.0/`.

---

# § v1.6.0 ADDENDUM — OPERATION COMET LOCAL (on-device llama.cpp AI)

Additive contracts only; everything above remains binding. Full design:
`docs/agent/LOCAL_AI_ARCHITECTURE.md`.

## A. New behavioral contracts (do not break)

| # | Contract |
|---|---|
| LOC-1 | The on-device provider (`id="local"`) participates in the NORMAL router chain; when no model is downloaded (or the native runtime is unavailable) `ModelRouter.chain()` output is byte-identical to v1.5.0 |
| LOC-2 | Chain position: `local_ai_preferred=false` (default) → local appended LAST (last-resort fallback when every cloud provider failed); `true` → local PREPENDED (local-first). No other reorder |
| LOC-3 | Cheap readiness: `LocalLlamaProvider.isReady()` never calls JNI and never blocks — selected model file exists + native runtime available. On-demand load happens inside `chat()` |
| LOC-4 | Context budget: prompts are token-counted BEFORE decode; overflow raises `ContextTooLargeException` (§19 compression) — never silent head-truncation of the system prompt. Native head-trim exists only as a last-resort guard |
| LOC-5 | Model load and generation are mutually exclusive (manager `ReentrantLock` + provider `AtomicBoolean`); idle watchdog never frees while generating; `AgentEngine.stop()` calls `router.cancelLocal()` to halt the native decode |
| LOC-6 | Downloads verify SHA-256 against the pinned catalog hash before activation; mismatched files are deleted, never loaded. Imported GGUFs are magic-checked, never checksum-pinned |
| LOC-7 | On-device `ModelInfo` claims only `{CHAT, JSON_OBJECT, STREAMING}` — never VISION/TOOL_CALLING/JSON_SCHEMA; `describeScreenshot` keeps using cloud vision members |
| LOC-8 | JNI boundary (`ai.local.LlamaBridge` + GenProgressListener) stays unobfuscated; native `onProgress(IIII[B)V` lookup failure must never discard a generation (JARVIS v1.5.0 lesson) |
| LOC-9 | llama.cpp is pinned (tag b4458 + tarball SHA-256) in `app/src/main/cpp/CMakeLists.txt`; ABI = arm64-v8a only; x86/unsupported ABIs degrade to "on-device AI unavailable" with zero app impact |
| LOC-10 | All new settings keys are additive: `local_ai_preferred` (false), `local_model_id`, `local_context` (4096, runtime-clamped ≤4096), `local_threads` (0=auto), `local_unload_min` (10, 0=never) |

## B. Regression gate update

Baseline is now **218 tests / 24 suites** (was 185/20; +4 suites:
ChatTemplateRendererTest, LocalModelCatalogTest, LocalProviderTest,
LocalChainTest). `assembleRelease` must produce versionCode 7 /
versionName 1.6.0 and include `lib/cometx_llama.so` (arm64). Signing keystore:
`app/keystore/` (gitignored) — cert SHA-256 `970e0a30…` continuity from
v1.4.0/v1.5.0; persistent backup at `~/keystore-backup/`.

---

# § v1.6.1 ADDENDUM — DAILY-DRIVER FIXES (tab switching + omnibox)

Bug-fix release from user field reports; additive only, no contract above is
weakened. Nothing in SOM-1..4 / STAT-1..3 / LOC-1..10 changed.

## A. Fixed behaviors (now binding)

| # | Contract |
|---|---|
| FIX-1 | Tab sheet rows switch tabs via ROW-level click listeners (`row.setOnClickListener`) — ListView item-click machinery inside bottom sheets is no longer relied on |
| FIX-2 | `TabManager.attach()` pauses every other tab's WebView, resumes the attached one, and forces a recompose (INVISIBLE → posted VISIBLE) — a re-attached WebView must never keep a stale/frozen surface |
| FIX-3 | Closing a tab LEFT of the current one decrements the index first — the user stays on the page they were viewing |
| FIX-4 | Omnibox is Chrome-like: focus selects the whole text (type-to-replace); defocus restores the live page URL; GO closes the keyboard and releases focus so live URL updates resume (fixes "search feels stuck") |
| FIX-5 | After ANY tab open/switch/close (sheet, menu, agent tab-verbs, external links) the omnibox mirrors the CURRENT tab (`syncOmniboxToCurrentTab`) |
| FIX-6 | Omnibox "URL or search?" resolution lives in `util/UserInput.resolve()` (pure JVM); rules unchanged from v1.6.0 inline behavior |
| FIX-7 | Back while the omnibox is focused leaves the editor (URL restored, keyboard closed) instead of leaving the app |

## B. Regression gate update

Baseline is now **235 tests / 25 suites** (was 218/24; +1 suite:
TabAndOmniboxTest). `assembleRelease` must produce versionCode 8 /
versionName 1.6.1. Cert SHA-256 `970e0a30…` continuity maintained.

---

# § v1.7.0 ADDENDUM — BACKGROUND MODEL DOWNLOADS (fast + unattended)

Additive contracts only; everything above remains binding (LOC-6 SHA-256
verification before activation is preserved unchanged).

## A. New behavioral contracts (do not break)

| # | Contract |
|---|---|
| DL-1 | Downloads run in WorkManager (`ModelDownloadWorker`, unique name `cometx-model-<id>`) as a foreground service (`dataSync` type) — they continue while the app is closed, survive process death AND reboots (persistent queue) |
| DL-2 | Network constraint `CONNECTED` + linear 10s backoff: network fluctuations NEVER surface a failure — work is silently stopped and re-dispatched; UI shows "waiting for network…" with progress retained (`DownloadState.WaitingNetwork`) |
| DL-3 | Transfer is parallel-chunked (`ChunkPlanner.plan`, default 4 connections, ≥16 MB per chunk) writing into a preallocated `.part` at absolute offsets — no reassembly copy; servers without Range support degrade to the v1.6.0 single-stream path |
| DL-4 | Per-chunk progress persists in `<file>.part.meta` sidecar (flush every ~16 MB); resume is chunk-granular after crash/process death; cancel deletes `.part` + sidecar |
| DL-5 | Pause cancels the unique work but keeps parts (Resume button); resume re-enqueues with KEEP policy (no duplicate workers); a paused model never shows "waiting for network" (worker checks `pausedIds`) |
| DL-6 | The FGS notification channel is IMPORTANCE_LOW, silent, no vibration, ongoing, throttled to one update / 3 s — background downloading must not disturb the user or other apps |
| DL-7 | `POST_NOTIFICATIONS` is requested opportunistically at Download tap (API 33+); a denial never blocks or fails a download |
| DL-8 | On app start `LocalModelManager.reconcileQueuedWork()` mirrors WorkManager's queue into the states map so the Settings UI reflects unattended background downloads after process death/reboot |
| DL-9 | `ChunkPlanner` stays pure Kotlin (no Android imports) — it is part of the JVM test gate |

## B. Regression gate update

Baseline is now **244 tests / 26 suites** (was 235/25; +1 suite:
ChunkPlannerTest). `assembleRelease` must produce versionCode 9 /
versionName 1.7.0. Cert SHA-256 `970e0a30…` continuity maintained.

---

# § v1.8.0 ADDENDUM — BACKGROUND AGENT MODE (isolated headless task engine)

Additive contracts only; everything above remains binding. The foreground
agent (panel → visible tab via `LiveWebViewSink`) is untouched: with no
background run started, behavior is byte-identical to v1.7.0. The background
engine reuses the SAME AgentEngine, ActionValidator, SafetyPolicy, StepBudget
and ActionExecutor pipeline — only the driver (sink) and the host differ.

## A. New behavioral contracts (do not break)

| # | Contract |
|---|---|
| BG-1 | "Run in background" starts `AgentTaskService` (FGS, `dataSync` type) which owns an ISOLATED headless WebView (`HeadlessWebViewFactory`) — never attached to any window, never part of the user's tab strip. User's tabs, omnibox, panels and TabManager state are structurally unreachable from the background engine |
| BG-2 | One background task at a time; the store (`BackgroundAgentStore`, app-scoped) persists EVERY mutation atomically (tmp+rename) to `filesDir/agent/background_agent_state.json` BEFORE listeners fire |
| BG-3 | Monitoring lives in the notification bar: silent IMPORTANCE_LOW channel `cometx_agent`, one ongoing notification mirroring the record — step counter + progress bar + last action while RUNNING; tap → opens the agent panel monitor card |
| BG-4 | Human gates surface in the shade: high-risk confirms show Approve/Deny buttons; `ask_user` shows a direct Reply (RemoteInput) field; challenges show "tap to take control". Human-gate timeout is extended to 45 min in background runs (`AgentEngine.gateTimeoutMs`, additive hook; UI paths keep the 15 min default) |
| BG-5 | Network fluctuations NEVER surface a failure: failed model steps and unreadable pages are offered to `AgentEngine.networkWaitGate` (additive, null in all UI paths). The service's gate (`NetworkWaitPolicy` + `NetworkWaiter`) parks the step — budget refunded — while offline (up to 5 min) and the shade shows "Waiting for network — the task continues automatically". Non-network errors fail exactly as before |
| BG-6 | The panel shows a live monitor card fed by the store listener; terminal transitions are mirrored into the agent log exactly once per state change |
| BG-7 | NO boot receiver and NO auto-resume: after a reboot nothing of ours runs (nothing can crash-loop — no "App keep closing" on restart). START_NOT_STICKY; a null-intent or goal-less start goes foreground once (ANR-safe) then stops silently. At the next app start `reconcileInterrupted()` marks an orphaned active record INTERRUPTED — honestly labeled, never auto-restarted |
| BG-8 | Crash containment: every service entry point (onStartCommand, engine callbacks, network callback, onDestroy, notification builds) is wrapped — failures degrade the TASK's notification, never the process. A headless renderer crash (`onRenderProcessGone`) fails the task gracefully instead of killing the app |
| BG-9 | The headless WebView carries the same security posture as the browser (JS on, file/content access off, mixed content never, no geolocation, no JS bridge); non-http(s) schemes are silently refused (no user to approve intent://), executable downloads (.exe/.apk/…) are REFUSED unattended; non-executable downloads go to the system DownloadManager with its own progress UI |
| BG-10 | Wake lock (`PARTIAL_WAKE_LOCK`, 5-min budget renewed per step) keeps the task alive in deep sleep; released on every pause/terminal state and in onDestroy |
| BG-11 | Provider construction lives in `ai/ProviderSet` — the single source for the router chain used by BOTH the activity engine and the background service (keys still read live from settings; base URLs re-applied on resume as before) |
| BG-12 | `NetworkWaitPolicy` stays pure JVM (no Android imports) — it is part of the JVM test gate |

## B. Regression gate update

Baseline is now **265 tests / 27 suites** (was 244/26; +1 suite:
BackgroundAgentTest). `assembleRelease` must produce versionCode 10 /
versionName 1.8.0. Cert SHA-256 `970e0a30…` continuity maintained.

---

# § v2.0.0 ADDENDUM — OPERATION COMET GRAND SLAM (full browser layer + Material You + new brand)

Source: the user's other browser project **Zerium** (ansaribilal14/zerium-browser, GPL-3.0,
same owner) — its complete browser-feature layer was ported into Comet-X additively and
re-branded. Everything above remains binding. With no browsing done, the agent surface
(panel, background mode, skills, on-device AI) is byte-identical to v1.8.0.

## A. Port fixes (defects found in the source project and corrected here)

| # | Fix |
|---|---|
| PF-1 | `CosmeticFilter.buildScript` emitted `hide(n2])` — a JS syntax error that failed the WHOLE script at parse time (fallback branch dead). Port emits `hide(n2[m])`; `BrowserLogicTest` asserts the corrected form |
| PF-2 | `StartPageLogic.autoTiles` marked a host as used BEFORE the default-tile check, so visiting github.com/HN suppressed BOTH the history tile and the default tile. Port checks `defaultHosts` first |
| PF-3 | The omnibox search template must retain its `%s` placeholder — the port's first draft dropped it and the test gate caught it (`DEFAULT_TEMPLATE` now carries `%s`; UserInput tests assert the full query URL) |

## B. New view IDs (additive; §A originals untouched — urlBar stays EditText, webContainer
stays FrameLayout, all panel/banner IDs unchanged)

| ID | Layout | Used as | Contract |
|---|---|---|---|
| `securityIcon` | activity_main | ImageView | TLS state icon (lock/globe/home); tap = security info dialog |
| `findBar` / `findInput` / `findCount` / `btnFindPrev` / `btnFindNext` / `btnFindClose` | activity_main | LinearLayout / EditText / TextView / Button ×3 | inline find-in-page; debounced 250 ms `findAllAsync`; counter "n/total" only when counting finishes |
| `swipe` | activity_main | BrowserSwipeLayout (SwipeRefreshLayout) | wraps `webContainer`; enabled iff page loaded AND pull-to-refresh setting on; `canChildScrollUp()` forwarded to the visible WebView (Zerium's scroll-hijack fix) |
| `tabSwitcher` / `tabsGrid` / `tabCount` / `btnNewTab` / `btnNewIncognito` / `btnCloseAllTabs` / `btnSwitcherClose` | activity_main | LinearLayout overlay / RecyclerView / TextView / Button ×4 | full-screen Material tab switcher (GridLayoutManager ×2); tab count "Tabs (n)"; root is now a FrameLayout wrapping the original vertical LinearLayout |
| `tabCard` / `tabPreview` / `tabTitle` / `tabUrl` / `tabIncognito` / `btnCloseTab` | item_tab_grid | MaterialCardView / ImageView / TextView ×3 / Button | tap = switch, ✕ = close (v1.6.1 row-level click contract carried over); active card carries accent stroke; RGB_565 preview ≤320 px, never captured for incognito |
| `toolbar` / `list` / `empty` | activity_list | MaterialToolbar / ListView / TextView | shared shell for Bookmarks / History / Downloads |

`sheet_tabs.xml` and `item_tab.xml` remain in the repo (dead layouts are allowed to keep
compiling per §A); the live switcher is the `tabSwitcher` overlay via
`MainActivity.showTabDialog()` (method name preserved from F-02).

## C. New behavioral contracts (do not break)

| # | Contract |
|---|---|
| BLK-1 | Network blocking runs in `shouldInterceptRequest`: blocked requests return an empty 404 BEFORE reaching the network. Engine = `browse.AdBlocker` (StevenBlack hosts + conservative substring URL rules + per-site allowlist); app-scoped in `CometApp`, loaded off the main thread, swapped atomically on reload |
| BLK-2 | Per-page / per-session / all-time counters: `Tab.blockedOnPage` (reset onPageStarted), `AdBlocker.sessionBlocked`, `SettingsRepository.totalBlocked` (committed once per finished page). All three are visible (security dialog, Blocked-on-this-page dialog, start-page stat card) |
| BLK-3 | Cosmetic filtering injects `browse.CosmeticFilter.buildScript` at document start on all http(s) origins (WebViewCompat) with a page-finish fallback; selectors are sanitized (`SAFE_SELECTOR`), batched (60/batch) with per-selector retry; hiding is rAF-debounced + MutationObserver-armed |
| BLK-4 | YouTube suppression (`assets/yt-block.js`) injects at document start for *.youtube.com / youtube-nocookie / music.youtube.com, with a page-finish fallback keyed on `YouTubeFilter.matches(host)`; both master switches (blockAds, youtubeSuppress) gate it |
| BLK-5 | "Blocked on this page" dialog offers **Allow this site** → host appended to the newline allowlist, `rebuildAllowlist` applies immediately (duplicates detected) |
| BLK-6 | Filter lists auto-refresh about weekly (`FilterUpdater.dueForAutoUpdate`) or via the Settings row; downloads validate (min size + content marker) and swap ATOMICALLY — a failed download can never degrade blocking; updates invalidate the cosmetic cache and reload the blocklist without a restart; never runs under Robolectric |
| BLK-7 | The background agent's headless WebView applies the same network blocking (`HeadlessWebViewFactory.networkBlock` wired in CometApp; null = legacy no-op) |
| PRIV-1 | Main-frame loads route through `MainActivity.loadInTab`: DNT + Sec-GPC headers when `privacyHeaders()` is on (all UI tabs AND restored/agent-opened tabs — loading is deferred to the caller by design) |
| PRIV-2 | HTTPS-first: `browse.HttpsFirst.upgraded` rewrites main-frame http→https; localhost/.local/.lan/.internal/.home, 10/8, 127/8, 192.168/16, 172.16–31/12 and IPv6 literals are skipped; SSL failures still raise the explicit dialog |
| PRIV-3 | SSL errors: explicit Proceed/Cancel dialog (incognito tabs ALWAYS cancel, no dialog) |
| PRIV-4 | Geolocation and camera/microphone web permissions are prompt-gated (Zerium pattern); camera/mic runtime requests flow through `onWebPermissionRequest` → system permission dialog → `pendingWebPermission.grant/deny`; everything else is denied |
| PRIV-5 | Incognito tabs: no history writes, no bookmarks, no previews, no favicon capture, no saved-session entries; marked with a badge in the tab grid |
| PRIV-6 | First-party cookies switch (`cookiesEnabled`, default on) + pre-existing third-party switch; downloads forward session cookies + UA |
| FEAT-1 | Bookmarks: SQLite (`browse.BookmarksStore`), menu add/remove with toasts, BookmarksActivity (tap = open in new tab via activity result, long-press = delete) |
| FEAT-2 | History: SQLite (`browse.HistoryStore`), written on page finish for non-incognito http pages only; HistoryActivity (tap = open, long-press = delete, toolbar Clear-all with confirm) |
| FEAT-3 | Downloads screen: system DownloadManager query; tap = open (mime-matched), long-press = remove |
| FEAT-4 | Find in page: inline bar (see §B), IME action = next, dismissed by back / tab switch / tab close, matches cleared on hide |
| FEAT-5 | Desktop site per tab: runtime-derived UA (device's own Chromium major in `major.0.0.0` form, X11 platform token), toggle swaps UA + reloads; stock mobile UA preserved per tab |
| FEAT-6 | Reader view: Mozilla Readability (v0.6.0, Apache-2.0, bundled asset) via `evaluateJavascript`; DOM snapshot restored on toggle-off; honest "does not look like an article" for short pages; per-tab `readerActive` state reset on navigation |
| FEAT-7 | Translate page: Google translate.goog proxy in the same tab (`browse.TranslateSupport`, pure and unit-tested); menu flips to View original; pre-translation URL tracked per tab with best-effort reconstruction |
| FEAT-8 | Print / Save as PDF via `createPrintDocumentAdapter` (loaded pages only) |
| FEAT-9 | Add to home screen: pinned shortcut (API 26+) with page favicon or launcher icon; graceful toast on unsupported launchers |
| FEAT-10 | Share: ACTION_SEND chooser with the page URL |
| FEAT-11 | Start page: `browse.StartPage` renders the Comet-X branded NTP (gradient wordmark, agent CTA, search pill honoring the selected engine, 8 dynamic tiles = custom or most-visited-blend, live blocked-stats card); sentinel `about:home` / `cometx://home` routes there from the omnibox; cold start opens it unless a custom homepage is set; pull-to-refresh disabled on it |
| FEAT-12 | Session restore: non-incognito tab URLs ("||"-joined, max 10) + index persisted on every pause; data:/about:blank normalize to `about:home`; restored tabs load through `loadInTab` |
| FEAT-13 | Custom search engines: name + `%s` URL (validated, max 20) stored as JSON; engine picker covers built-ins (Google first — preserves v1.x default) + customs; the start-page pill uses the same engine |
| UI-1 | Material You dynamic color: applied per-activity on API 31+ when `materialYou()` is on (read pre-inflation from prefs); fallback = the shipped Comet violet token system |
| UI-2 | App theme System/Light/Dark via `AppCompatDelegate.setDefaultNightMode` — applied in `CometApp.onCreate` (first frame) and immediately from Settings |
| UI-3 | Full-screen video: `onShowCustomView` moves the view into an addContentView FrameLayout, hides topBar/askBar/progress, keeps screen on; back exits; state via `onFullscreenChanged` |
| UI-4 | Renderer crash (`onRenderProcessGone`) destroys the WebView, closes that tab with a toast — never kills the process |
| UI-5 | Web text zoom 50–200 % applied live to ALL tabs on resume; force-zoom rewrites the viewport meta at document start; zoom controls enabled (no on-screen buttons) |
| UI-6 | Pull-to-refresh: `BrowserSwipeLayout` forwards `canChildScrollUp()` to the visible WebView per gesture (scroll-hijack fix); disabled on start pages and when the setting is off |
| BRAND-1 | New adaptive launcher icon (deep-space radial background + fixed star field; glowing comet head + tapered three-band trail; sparkle), `ic_launcher_round`, and a themed `monochrome` layer; minSdk 26 → no legacy PNGs needed |
| BRAND-2 | Start-page wordmark/branding is generated HTML only — no bitmap brand assets |
| BRAND-3 | License/attribution carried: Zerium code GPL-3.0 (same owner), StevenBlack hosts MIT, cosmetic subset EasyList CC-BY-SA-3.0, readability.js Apache-2.0 (headers in the assets) |

## D. Menu contract update (F-03 extension)

`showMenu` now matches by **item ID** (was literal CharSequence equality — F-03 semantics
extended, all five v1.x actions preserved: New tab / Close-current via tab grid / Clear
browsing data / Agent self-test / Settings). New items: New incognito, Add/Remove bookmark,
Bookmarks, History, Downloads, Find in page, Desktop site (checkable), Reader view,
Translate/View original, Print, Add to home screen, Share, Blocked on this page.

## E. Regression gate update

Baseline is now **305 tests / 31 suites** (was 265/27; +4 suites: SearchEnginesTest,
AdBlockerTest, BrowserLogicTest, StoresTest). TabAndOmniboxTest's two sheet tests were
re-targeted to the tab GRID (same semantics: card tap switches + omnibox syncs, ✕ closes)
and the defocus-restore expectation is the v2.0.0 start-page sentinel `about:home`.
`assembleRelease` must produce versionCode 11 / versionName 2.0.0. Cert SHA-256
`970e0a30…` continuity maintained.

---

# § v2.1.0 ADDENDUM — OPERATION TRANSFORMERS (in-browser Transformers.js LLM)

> Full architecture, wire contract and honest limits: `docs/ai/WEB_LLM.md`.
> The deliverable: the SAME model zoo as the Transformers.js ecosystem, executed
> by the browser engine the app already ships — no CDN dependency, no native
> code added, no behavior change until the user selects a web model.

## A. New behavioral contracts (do not break)

| ID | Contract |
|----|----------|
| TW-1 | The runtime ships FULLY BUNDLED in `assets/webllm/` (transformers.min.js 4.3.0 + ort-wasm-simd-threaded.wasm/.mjs 1.31.0-dev pinned + runtime.js + index.html). No CDN may ever be required at load time; model weights are the only network fetches (Hugging Face hub, once, into Cache Storage) |
| TW-2 | The runtime page is served ONLY from `https://appassets.androidplatform.net/assets/webllm/` via WebViewAssetLoader inside the app-owned headless WebView (`ai/web/WebLlmRuntime`). COOP/COEP headers are injected on THAT origin only — never on user-facing pages. JS on, file/content access off |
| TW-3 | The Kotlin↔JS wire contract is pinned by `ai/web/WebLlmProtocol.kt` ↔ `assets/webllm/runtime.js` (events boot/log/status/ready/stream/done/error; commands load/generate/interrupt). Parsing is TOTAL: malformed or hostile bridge payloads parse to null and are swallowed — they can never throw into the provider |
| TW-4 | `TransformersWebProvider` (id `webtransformers` = `SettingsRepository.WEB_PROVIDER_ID`, lockstep tested) joins the router chain like the v1.6.0 local provider: additively, ranked AFTER native llama.cpp (native ARM beats WASM), LAST when cloud exists, FIRST when "prefer on-device AI" is on. With no web model selected the chain is byte-identical to v2.0.0 |
| TW-5 | Readiness is two-tiered and CHEAP: `isReady()` = a catalog model selected AND runtime not broken — it never creates a WebView, allocates, or touches the network. `chat()` starts the runtime on demand; a resident pipeline fast-path skips re-load |
| TW-6 | Text-only honesty: multimodal messages are REFUSED (`MODEL_UNAVAILABLE`) so the router transparently moves to a vision-capable provider — same contract as the local provider. Prompt budget: >60 000 chars throws `ContextTooLargeException` (engine §19 compression path engages) |
| TW-7 | Single completion at a time (provider AtomicBoolean + page-level busy flag, both tested); output cap clamped 64–512 tokens; empty output → `PROVIDER_ERROR`, never an empty string to the engine |
| TW-8 | Catalog honesty (`WebLlmCatalog`): three verified repos (SmolLM2-360M-Instruct 387.9 MB, Qwen2.5-0.5B-Instruct 786.2 MB, Qwen2.5-1.5B-Instruct 1787.6 MB — onnx/model_q4.onnx sizes, hub-reported 2026-09-19), int4/q4 only (the dtype the WASM CPU backend executes reliably), RAM guidance shown per model, 1.5B flagged "8 GB+ device" |
| TW-9 | Renderer loss (`onRenderProcessGone`) is consumed in place: pending boot/load/generation fail with a clear error, the provider leaves the chain (`healthy=false`) and the runtime self-heals on the next `ensureStarted()` — the app must never crash (v1.8.0 posture) |
| TW-10 | Settings section "In-browser AI (Transformers.js)" mirrors the local-AI section: model cards with hub-reported sizes, one-tap select, honest status line (download % from real progress callbacks, "cached & ready", real error text), "Test selected model" round-trip, "Free runtime memory" releases the renderer (weights stay cached) |
| TW-11 | Idle hygiene: after a completed generation the runtime schedules a 10-minute idle release of the renderer; any new operation cancels the schedule. Weights persist in Cache Storage across releases and process restarts |

## B. Regression gate update

Baseline is now **344 tests / 35 suites** (was 305/31; +4 suites: WebLlmCatalogTest,
WebLlmProtocolTest, TransformersWebProviderTest, WebChainTest).
`assembleRelease` must produce versionCode 12 / versionName 2.1.0. Cert SHA-256
`970e0a30…` continuity maintained. All v2.0.0 contracts (§ PF/BLK/PRIV/FEAT/UI/BRAND)
remain binding; the local-AI chain semantics of LocalChainTest are unchanged and now
extended by WebChainTest.

# § v2.2.0 ADDENDUM — BYOK CLOUD VERIFICATION + DISCORD AGENT MONITOR

> Task: user supplied four API keys (NVIDIA NIM, OpenRouter, Discord bot,
> one unidentified) and asked: identify them, test them for real, then build
> the placement for users to paste their own. Every preset shipped here was
> verified with a REAL round-trip before release (see docs/ai/CLOUD_PROVIDERS.md).

## A. New behavioral contracts (do not break)

| ID | Contract |
|----|----------|
| NV-1 | `NvidiaProvider` (id `nvidia`, `SettingsRepository.ALL_PROVIDERS` position 3) targets the live-verified endpoint `https://integrate.api.nvidia.com/v1` (GET /models + POST /chat/completions verified 200 with a real nvapi-… key). Key shape `nvapi-…` is surfaced in the settings hint (build.nvidia.com → API keys) |
| NV-2 | NVIDIA catalog discovery is LIVE: the provider never hardcodes model ids beyond tests, because NVIDIA retires catalog entries (verified: a retired id answers 404 "Function … not found", which must surface as `ProviderException(httpCode=404)` — the chain then fails over normally). Reasoning-family ids (deepseek-r1 / gpt-oss / qwen3) gain the REASONING capability hint |
| NV-3 | Chain adoption is fully additive: old stored `chain_order` values are auto-extended with `nvidia` (existing chainOrder() append rule); `providerEnabled("nvidia")` defaults to false, so v2.1.0 chains are byte-identical until the user tests & enables the provider |
| DM-1 | The Discord agent monitor is OFF by default and unconfigured: with `discord_enabled=false`, an empty token, or an empty channel id, `DiscordNotifier` performs NO network call and every v2.1.0 path (service, notifications, engine) runs byte-identically |
| DM-2 | The bot token is a SECRET: stored ONLY through SecureStore (Keystore AES-256/GCM, same vault as provider keys, key `discord_bot_token`); the channel id lives in plain prefs (not a secret). Tokens are never logged, never embedded in messages, never sent anywhere except `https://discord.com/api/v10` |
| DM-3 | Mirror messages are plain markdown `content` (no embeds), built by pure `DiscordNotifier.buildBody` (unit-tested), capped at 1800 chars (Discord's limit is 2000), endpoint pinned to `POST /channels/{channelId}/messages` with `Authorization: Bot <token>` |
| DM-4 | Mirror events: task STARTED, milestone STEP progress (every 5th step and the final step — never per-step spam), gates (awaiting confirm / ask_user / challenge) and exactly ONE final event (completed / failed / stopped) deduplicated by `finalMirrored` so `stopTask` and the engine callback can never double-post |
| DM-5 | Fire-and-forget honesty: a Discord outage, rate limit or invalid token is logged and dropped — it can never delay, fail or alter a task, a notification, or the service. Posts run on Dispatchers.IO with a 15 s timeout and a ≥2.5 s throttle (forced events bypass the throttle, never the config gate) |
| DM-6 | Settings section "Discord agent monitor" provides bot token (password field) + channel ID + enable toggle + "Send test message" which performs a REAL post and renders the honest outcome per HTTP status (401 invalid token / 403 missing permission / 404 unknown channel / 429 rate limit) |

## B. Regression gate update

Baseline is now **360 tests / 37 suites** (was 344/35; +2 suites: NvidiaProviderTest 7,
DiscordNotifierTest 9 — final counts recorded at release build time).
`assembleRelease` must produce versionCode 13 / versionName 2.2.0. Cert SHA-256
`970e0a30…` continuity maintained. All v2.0.0/v2.1.0 contracts (§ PF/BLK/PRIV/FEAT/UI/BRAND/TW)
remain binding; LocalChainTest / WebChainTest chain semantics are unchanged
(nvidia joins the cloud group only when the user enables and keys it).

---

# § v2.3.0 ADDENDUM — CLOUD AI CENTER (Jetpack Compose / Material 3 Expressive)

> The Compose face of the v2.2.0 BYOK backend. Architecture, evidence log and
> honest limits: `docs/ai/CLOUD_AI.md`. Nothing here changes agent routing —
> the Cloud AI center is UI + the same encrypted settings keys.

## A. New behavioral contracts (do not break)

| ID | Contract |
|----|----------|
| CLD-1 | The Cloud AI center (`ui.cloud.CloudAiActivity`) is the app's ONLY Jetpack Compose surface, registered non-exported with `configChanges="uiMode"`, reached via one Tonal button in Settings ("Open Cloud AI center (Material 3)"). The legacy XML provider blocks and the Discord monitor section remain the default path and keep their exact behavior — the Compose center is a second UI on the SAME settings keys, never a fork |
| CLD-2 | The Compose theme honors the existing `material_you` setting: dynamic color on Android 12+ when enabled; brand fallback mirrors values(-night)/colors.xml (comet violet #6D28D9 / #A78BFA on deep-space #FCFBFF / #0E1116). Compose artifacts are pinned: BOM 2024.06.00, compiler ext 1.5.14 (Kotlin 1.9.24) |
| CLD-3 | Key entry routes through `SettingsRepository.setApiKey` → SecureStore — identical storage to the legacy UI. Keys are rendered masked only (`KeyFormat.mask`: ≤8-char prefix + •••• + last 4) with an explicit reveal toggle; the full key is never rendered, logged, or embedded in any payload |
| CLD-4 | `KeyFormat.warning` is a HINT layer: shape mismatches warn inline (supporting text) but NEVER block saving — a provider-side key-format change can never brick a working configuration. It stays pure JVM (no Android imports) and is part of the JVM test gate |
| CLD-5 | "Test connection" = live `/models` catalog fetch + tiny completion (`ping`), executed on Dispatchers.IO through the same `OpenAICompatibleProvider` stack the agent uses, result persisted through the pre-existing `lasttest_` record and displayed with honest provider errors |
| CLD-6 | Chain reordering writes through the pre-existing `moveInChain`/`chainOrder` API (#N position badge, up/down). Model mode AUTO/MANUAL and the MANUAL AGENT-role override use the §12/§22 keys; fetched-catalog suggestion chips never auto-select anything |
| CLD-7 | The Compose Discord card is a second face of DM-1..DM-6: same `discord_enabled` / `discord_channel_id` / `discord_bot_token` keys, same `DiscordNotifier.configFrom/post/buildBody` backend, honest per-status outcomes (200/401/403/404/429). It adds only a token VALIDATION call (`GET /users/@me`) that the legacy UI lacks; no message reading, no Gateway, ever |
| CLD-8 | ModelRouter `defaultModelFor("nvidia")` pins LIVE-VERIFIED ids only (agent/reasoning `openai/gpt-oss-20b`, fast/cheap `google/gemma-3-4b-it`, vision `meta/llama-3.2-11b-vision-instruct`, catalog 2026-09-20) used solely as §33 last-resort fallbacks — live catalog discovery remains the primary path (NV-2 unchanged) |
| CLD-9 | No real credential may ever enter the repo, tests, or APK. Tests use synthetic shape-valid keys; live verification happens out-of-band with keys in env files outside the repository (evidence log in `docs/ai/CLOUD_AI.md`) |

## B. Regression gate update

Baseline is now **372 tests / 38 suites** (v2.2.0 baseline + CloudAiV22Test 16 —
chain additivity, verified NVIDIA defaults/URL/error mapping, key formats/masking,
Discord config readiness). `assembleRelease` must produce versionCode 14 /
versionName 2.3.0. Cert SHA-256 `970e0a30…` continuity maintained. All v2.2.0
contracts (§ NV-1..3, DM-1..6) and earlier remain binding.

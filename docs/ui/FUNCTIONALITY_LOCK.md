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

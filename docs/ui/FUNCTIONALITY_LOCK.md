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

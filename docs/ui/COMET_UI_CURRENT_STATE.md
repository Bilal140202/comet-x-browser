# COMET_UI_CURRENT_STATE — Phase 0 Repository Forensics

> OPERATION COMET AURORA · audited 2026-09-05 · commit c9afaf4 (v1.3.0)
> Scope: the **native Android app** (`com.cometx.browser`, Kotlin, `cometx/`).
> The Next.js files in the outer workspace are an abandoned preview stub and are
> explicitly OUT of scope — the shipped product is the APK.

---

## 1. WHAT EXISTS (architecture map)

| Layer | Fact | Evidence |
|---|---|---|
| Framework | Kotlin 1.9.x, Android View system, **no Compose** | `app/build.gradle.kts` |
| UI toolkit | **Framework widgets only** — zero AndroidX, zero Material Components | only dep: `kotlinx-coroutines-android` (+ test-only junit/robolectric) |
| Theme | `android:Theme.Material.NoActionBar` (framework dark theme, hard-coded) | `res/values/themes.xml` |
| Activities | `MainActivity : Activity` (browser + agent), `SettingsActivity : Activity` (fully programmatic UI, no layout file) | `ui/MainActivity.kt`, `ui/SettingsActivity.kt` |
| Navigation | Single-task, two activities; `PopupMenu` menu; tab switcher = `AlertDialog` + plain `ListView` | `MainActivity.showMenu/showTabDialog` |
| Browser engine | `BrowserController` (WebView pool), `TabManager` (real multi-tab, retained state) | `ui/` |
| Agent UI | `AgentPanelController` (717 lines) — goal input, chips, log ListView, control buttons, dialogs | `ui/AgentPanelController.kt` |
| Persistence | `SettingsRepository` (SharedPreferences), `SecureStore` (Keystore), `MemoryStore`, `UserSkillStore` | `ai/`, `memory/`, `skills/` |
| Theming tokens | 14 hard-coded colors, **dark-only**, no `values-night` | `res/values/colors.xml` |
| Icons | Unicode glyphs in `Button.text` (‹ › ⟳ ▣ ⋮ ✦ ▾ ✕ 🎙 ⏺) — no vector icons at all | `activity_main.xml` |
| Drawables | 5 flat shape XMLs (url bar, chip, panel, button, input) | `res/drawable/` |
| Layouts | `activity_main.xml` (391 lines, LinearLayout stack), `item_tab.xml`, `item_log.xml` (9-line TextView) | `res/layout/` |
| Fonts | System default (Roboto), no font resources, no type scale | — |
| Motion | None (pure visibility toggles) | — |
| Launcher | Default-style adaptive icon, violet bg | `mipmap-anydpi-v26` |
| Tests | 172 unit tests / 18 suites; **`AppSmokeTest` launches both activities under Robolectric and asserts view IDs + panel toggle** | `app/src/test/` |

## 2. WHAT WORKS (must survive untouched)

- Real multi-tab browsing with retained WebView state (`TabManager`).
- Full agent loop UI wiring: 26 view IDs consumed by `MainActivity`/`AgentPanelController` (see FUNCTIONALITY_LOCK).
- Challenge takeover banner, confirm-gate dialogs, ask-user inline answer row.
- Skill Recorder (record → stop → review → save), Your-Skills chips (run/details/edit JSON/export/delete), `/grill-me` interview loop with draft review-edit-iterate.
- Settings: provider blocks with Test & Enable (save + diagnostics + dirty indicator), Advanced disclosure (AUTO/MANUAL, per-role overrides, cache reset), Agent Compatibility self-test, AI event log, agent behavior, browser, memory — every key persisted via `SettingsRepository`.
- Deep links (http/https VIEW intent), `onNewIntent` → new tab, back-gesture semantics (panel → web → exit).
- All 172 tests, including the Robolectric smoke test that inflates both activities.

## 3. WHAT MUST NOT BREAK (hard contracts)

1. Every `R.id.*` referenced from Kotlin (complete list in FUNCTIONALITY_LOCK.md) must exist with a **type-compatible** replacement (`MaterialButton extends Button`, `TextInputEditText extends EditText`, `MaterialCheckBox/SwitchMaterial extends CompoundButton` — but NOT `CheckBox`).
2. `challengeBanner`, `askBar`, `agentPanel`, `answerRow`, `skillChips`, `userSkillChips` must remain `LinearLayout` (cast in code).
3. `userSkillsScroll` must remain `HorizontalScrollView`; `logList` must remain `ListView` (ArrayAdapter + `R.id.logLine` binding).
4. `item_log.xml` must expose `@+id/logLine` TextView; `item_tab.xml` must expose `tabTitle`, `tabUrl`, `btnCloseTab`.
5. All `R.color.*` referenced from Kotlin must continue to resolve: `text_primary, text_secondary, accent_bright, background, danger, success, warning, divider` (+ XML refs: `surface, chip_bg, accent, accent_dim`).
6. Legacy color names are aliased to the new semantic tokens (old code keeps compiling, new tokens take over).
7. Behavior contracts: panel expand/collapse toggles askBar↔agentPanel; REC text routing through `askBarText`; status dot color mapping per engine state; dirty-dot + "Save & Test" label swap in settings; `onBackPressed` chain.
8. `AppSmokeTest` must stay green (it *is* the UI regression gate).

## 4. WHAT CAN BE REFACTORED (low risk)

- XML widget declarations (`Button` → `MaterialButton`, styling) — type-compatible.
- `Activity` → `AppCompatActivity` (API superset; same lifecycle overrides used).
- `android.app.AlertDialog` → `MaterialAlertDialogBuilder` (same builder API).
- Drawables, themes, colors (with alias layer), strings (additive only).
- Programmatic styling inside `SettingsActivity`/`AgentPanelController` widget factories (colors/padding/bg only — logic untouched).

## 5. WHAT SHOULD BE REDESIGNED (the overhaul itself)

| Area | Today | Target |
|---|---|---|
| Design language | Framework `Theme.Material`, flat, dark-only | **COMET MATERIAL EXPRESSIVE** — M3 tokens, tonal surfaces, light+dark |
| Top bar | Text-glyph buttons, 54dp, cramped | 56dp toolbar, real vector icons, 48dp targets, pill URL bar |
| Tab switcher | Bland system dialog list | M3 bottom sheet, letter-avatar cards, active indicator, close buttons |
| Agent panel | Flat rectangle glued to bottom, no hierarchy, raw log | Rounded pseudo-sheet (28dp top radius, drag handle), status chip w/ pulse, structured step rows (✓ ● ✗), grouped controls |
| Settings | One long gray column of default widgets | Sectioned cards, tonal CTAs, switches, consistent spacing |
| Dialogs | Default framework | Material dialogs (all 15+ dialog sites) |
| Icons | Unicode soup, mixed strokes | One coherent Material vector family |
| States | dot + text only | Per-state visual treatment (idle/working/waiting/done/failed + REC/interview) |
| Motion | None | COMET MOTION SYSTEM (micro/short/medium), pulse on live state, slide+fade for panel/banners |
| Accessibility | No contentDescription on icon-glyph buttons, contrast issues (warning banner) | Labels, 48dp targets, contrast-safe tokens, reduced-motion support |
| Light mode | none | Full light theme (system-following, `DayNight`) |

## 6. WHAT IS HIGH RISK (handle with evidence)

- **Type changes on findViewById targets** — mitigated via subclass rule (§3.1) + `AppSmokeTest`.
- **`adjustResize` + fixed 340dp agent panel**: replacing the panel with a true draggable `BottomSheet` would fight `ListView` scrolling and IME insets → *rejected this pass*; pseudo-sheet (rounded top + handle + slide motion) delivers the sheet look without the interaction risk.
- **Edge-to-edge/IME refactors**: deferred — WebView + agent panel inset coordination is high-risk for zero visual gain this pass; status-bar tinting via `WindowInsetsControllerCompat` only.
- **Robolectric + Material3**: needs verification on first build (material 1.12 + appcompat under Robolectric 4.12/SDK 34 — supported, but verified by running `AppSmokeTest` immediately after the foundation commit).
- **Theme attrs referenced by new Material widgets** (chip/menu styles) — scope every override in `styles.xml`; never restyle a shared widget globally.

## 7. EVALUATED EXTERNAL EFFECT LIBRARIES (DECISION: all REJECTED for the native app)

| Effect | Verdict | Reason |
|---|---|---|
| liquid-glass-js | **REJECT** | JavaScript/WebGL DOM library — the UI is native Android Views, no React/WebView UI layer exists to host it. Native analogue: `RenderEffect` blur (API 31+) selectively, guarded. |
| shadergradient (React) | **REJECT** | React R3F component; no React runtime in the app. A WebGL surface behind a WebView browser also burns battery — violates the battery budget. |
| paper-design/liquid-logo | **REJECT** | Web/React brand animation; native analogue = animated vector drawable on splash-free brand moments (kept for launcher + ask-bar sparkle only). |
| react-three-fiber | **REJECT** | Same architectural mismatch; adds a JS engine for a decorative object. Rejected under the one-moment rule anyway. |

> These were evaluated per the brief; the prompt itself requires compatibility with the
> *actual* architecture. The actual architecture is native Kotlin/Views. Documented so
> the decision is auditable — not lazily skipped.

## 8. DEPENDENCY DISCIPLINE — the ONE new dependency pair

| Dependency | Version | Why | License | Cost | Alternatives | Decision |
|---|---|---|---|---|---|---|
| `com.google.android.material:material` | 1.12.0 | M3 token system, `MaterialButton`, `BottomSheetDialog`, `MaterialAlertDialogBuilder`, shape/motion primitives — required to deliver "Material 3 Expressive" honestly | Apache-2.0 | ≈ +1.9 MB APK (unminified) | hand-rolled tokens (rejected: cannot deliver M3 sheets/ripples/expressive shapes without re-implementing the library) | **USE** |
| `androidx.appcompat:appcompat` | (transitive of material, pinned by it) | `AppCompatActivity` required by MaterialComponents themes | Apache-2.0 | ≈ +1.1 MB | stay framework-only (rejected: no M3 themes) | **USE (required by material)** |

No other runtime dependencies. No fonts downloaded if the sandbox lacks network (system Roboto with tuned tracking is the fallback and is itself the Material-native typeface).

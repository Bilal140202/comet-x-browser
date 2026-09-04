# COMET MATERIAL EXPRESSIVE — Design System v1.0

> OPERATION COMET AURORA · Phase 5 deliverable.
> Foundation: Material 3 (material 1.12.0, View system) + Comet's own identity.
> Brand principle: **CALM POWER** — "I can do complicated things, but I don't need
> to make the interface complicated."
> Council-reviewed (AURORA-DESIGN-1); contrast values checked (AA: body ≥4.5:1, UI ≥3:1).

---

## 1. IDENTITY

Warm · soft · minimal · premium · slightly futuristic · intelligent · quiet.
Violet "comet" accent on deep-space neutrals (existing identity, preserved verbatim in
dark mode for continuity). **One** floating/elevated element per screen (the ask-bar pill).
No glass blur in v1 (RenderEffect reserved for API 31+ later pass; rejected external
JS effect libraries — see COMET_UI_CURRENT_STATE.md §7).

## 2. COLOR TOKENS

Dark column preserves the shipped palette verbatim where continuity matters.

| Token | Light | Dark | Notes |
|---|---|---|---|
| background | `#FCFBFF` | `#0E1116` ✓verbatim | window |
| surface | `#F7F4FB` | `#161B22` ✓ | top bar, sheets |
| surfaceLow | `#F1EDF8` | `#161B22` | agent panel keeps today's tone |
| surfaceContainer | `#F0ECF7` | `#1D242E` ✓ | cards, inputs (legacy `surface_high`) |
| surfaceContainerHigh | `#EAE5F4` | `#242B36` ✓ | askbar pill, url bar (legacy `chip_bg`) |
| surfaceContainerHighest | `#E2DCF0` | `#2C3441` | pressed/selected |
| onSurface | `#1B1A22` | `#E6EDF3` ✓ | |
| onSurfaceVariant | `#494657` | `#8B949E` ✓ | |
| outline | `#7A7589` | `#5B6570` | focus/enabled borders |
| outlineVariant | `#C9C3D8` | `#2A313C` ✓ | hairlines (legacy `divider`) |
| primary | `#6D28D9` | `#A78BFA` ✓(=accent_bright) | 7.1:1 / 6.1:1 on partner surfaces |
| onPrimary | `#FFFFFF` | `#23124D` | |
| primaryContainer | `#EADDFF` | `#4C3A75` ✓(=accent_dim) | selected chips |
| onPrimaryContainer | `#2A0B66` | `#EADDFF` | |
| secondary | `#625F71` | `#A9B3C1` | |
| onSecondary / Container | `#FFFFFF`/`#E4E0F1`/`#1E1B2E` | `#1A2230`/`#2E3644`/`#DCE3EC` | tonal buttons |
| tertiary/info | `#0B57D0` | `#58A6FF` | info states |
| error/danger | `#B3261E` | `#F85149` ✓ | |
| onError | `#FFFFFF` | `#490F0C` | |
| errorContainer / on | `#FBDBD8` / `#410E0B` | `#3D1512` / `#FFD9D6` | **btnStop** |
| success | `#1A7F37` | `#3FB950` ✓ | light: 4.7:1 (tight — never lighten) |
| successContainer / on | `#D7F2DC`/`#0B4D16` | `#0F3D18`/`#9CE8AF` | |
| warning | `#8A5A00` | `#D29922` ✓ | #9A6700 forbidden (4.47:1 fail) |
| onWarning | `#FFFFFF` | `#1F1600` | banner keeps dark-on-amber BOTH modes (7.1:1) |
| warningContainer / on | `#FCE8B0`/`#4A3400` | `#3B2E04`/`#F5D87A` | |
| accent (fixed brand) | `#6D28D9` | `#8B5CF6` ✓verbatim | progress tint, sparkle — never used as text bg |

**Legacy alias map** (all defined in `values/` AND `values-night/`): `background→background,
surface→surface, surface_high→surfaceContainer, chip_bg→surfaceContainerHigh,
divider→outlineVariant, text_primary→onSurface, text_secondary→onSurfaceVariant,
accent→accent(fixed), accent_bright→primary, accent_dim→primaryContainer, danger→error,
success→success, warning→warning`.

## 3. TYPOGRAPHY (Roboto — council verdict: Inter rejected, would 2× APK)

| Style | sp/line | Weight | Tracking | Usage |
|---|---|---|---|---|
| displaySmall | 32/38 | 700 | −0.01em | reserved |
| headlineSmall | 22/28 | 400 | −0.01em | sheet/dialog titles, settings headers |
| titleLarge | 20/26 | 500 | −0.01em | panel header |
| titleMedium | 16/24 | 500 | 0 | tab rows, list titles |
| titleSmall | 14/20 | 500 | 0 | secondary labels |
| bodyLarge | 16/24 | 400 | 0 | urlBar, goalInput |
| bodyMedium | 14/20 | 400 | 0 | statusText, askBarText |
| bodySmall | 12/16 | 400 | 0 | stepText, tabUrl (bumped from 11) |
| labelLarge | 14/19 | 500 | +0.01em | button text |
| labelMedium | 12/16 | 500 | +0.02em | skill chips, badges |
| labelSmall | 11/14 | 500 | +0.04em | uppercase overlines ("YOUR SKILLS", from 10sp) |

Line height: `lineSpacingExtra` (API 26-safe; `android:lineHeight` only in values-v28+ if used).

## 4. SHAPE · SPACING · ELEVATION

**Shape**: extraSmall 8 · small 12 (inputs/goal/answer) · medium 16 (chips = half of 32dp) ·
large 20 (cards/provider blocks/log surface) · extraLarge 28 (agent sheet top corners) ·
**full pill** (buttons radius=22 on 44dp height, url bar 22, banner buttons) · drag handle 2.

**Spacing** (4dp grid): gutter 16 · top bar 56dp · url bar 44dp · askbar pill 52dp
(12dp side+bottom margins) · panel header 56dp, panel padding 16 · chips 32dp h ·
buttons 40–44dp, targets ≥48dp · tab rows 64dp · log rows 36dp · section gap 24.

**Elevation budget**: top bar 0 (1dp outlineVariant hairline) · url bar 0 (1dp border) ·
banner 0 (tonal) · **askbar 2dp (the one floating element)** · agent panel 8dp ·
sheets/dialogs M3 defaults.

## 5. MOTION SYSTEM

| Event | Duration | Easing (0.2,0,0,1 unless noted) | Properties |
|---|---|---|---|
| Micro (ripple, chip crossfade, focus border) | 100ms | standard | color/alpha |
| Panel expand | 280ms | emphasized | translateY h→0 + alpha 0→1 |
| Panel collapse | instant GONE + ask-bar 200ms fade-in | accelerate | state-first (regression gate); the ask-bar entrance carries the transition |
| Banner in / out | 200ms / 150ms | emphasized / accelerate | translateY −24→0 + alpha / alpha→0 + y→−16 |
| answerRow reveal | 200ms | emphasized | alpha + translateY 8→0 |
| Status pulse (RUNNING / REC only) | 1200ms ∞ | keyframes 1→0.45→1 | dot alpha |
| Sheet/dialog/scrim | M3 defaults | — | library |
| ListView inserts | **none** | deliberate | transcript mode gives liveness |

**Reduced motion**: one-shot anims = ViewPropertyAnimator (auto-scaled; scale-0 → instant).
Infinite pulse NEVER starts when `Settings.Global.ANIMATOR_DURATION_SCALE == 0`; a
ContentObserver on that setting starts/stops it live (infinite animator at 0ms = per-frame
CPU spin — battery bug). `animateLayoutChanges=false` everywhere.

## 6. COMPONENTS

- **Top bar**: 56dp surface bg + hairline; 48dp icon buttons (24dp vector, inset 0), contentDescription always; pill url bar 44dp surfaceContainerHigh + 1dp outlineVariant border; 2dp primary progress line under bar.
- **Challenge banner**: warningContainer bg, onWarningContainer text/icon, pill action buttons; replaces today's raw `@color/warning` bg (light-mode contrast bug fixed).
- **Ask-bar pill**: floating pill, 2dp elevation, sparkle `auto_awesome` in accent, labelMedium; REC state = danger dot + text (routing preserved).
- **Agent panel (pseudo-sheet)**: 28dp top radius, surfaceLow, 8dp elevation, decorative 24×4dp handle (no gestures), header row = state chip (12dp dot + statusText + stepText), goal field small-shape, chips restyled via `bg_chip` state-list (selected = primaryContainer/onPrimaryContainer), control row = filled(Run) / tonal(Record, Take Control, Resume) / outlined(/grill-me) / tonal-error(Stop), answer row, log surface large-shape with 36dp step rows.
- **Log step rows**: `item_log` keeps `logLine`; adapter subclass colors by leading glyph (✓ success, ✗ danger, ● primary, → onSurfaceVariant, ⚠ warning, ⏺ danger, ▶ success, 🎙 accent_bright, ✍ primary) + honors the stored isError pair (previously discarded).
- **Tab switcher**: Material `BottomSheetDialog`, `item_tab` repurposed as the row (letter avatar 40dp primaryContainer, title/url, close icon-button 48dp; row tap = switch, active = primaryContainer highlight; long-press keeps close).
- **Settings**: sectioned cards (20dp, surfaceContainer, 1dp outlineVariant), filled Test & Enable, tonal secondary buttons, text Advanced, SwitchMaterial for boolean prefs (constructed programmatically — no findViewById cast risk), provider cards on `bg_panel_input` (retokenized).
- **Dialogs**: MaterialAlertDialogBuilder at all 24 sites (incl. BrowserController ×2).
- **Buttons**: full-pill, `textAllCaps=false`, explicit heights, `inset*=0` for icon buttons. **No `android:background` on any MaterialButton** (silently discarded) — styles only.

## 7. STATE MAP (agent + app)

| State | Dot | StatusText | Extra |
|---|---|---|---|
| IDLE | onSurfaceVariant | "✦ What should I do?" | — |
| RUNNING | primary + **pulse** | "✦ Agent working — …" | Run/Record/Grill hidden (matrix preserved) |
| AWAITING_CONFIRM / AWAITING_USER | warning | ⚠/⏸ labels | Resume visible |
| COMPLETED | success | ✓ Completed | — |
| FAILED | danger | ✗ Failed — … | — |
| CANCELLED | onSurfaceVariant | ■ Stopped | neutral, NOT error |
| REC (overlay) | — | askBarText danger routing | text recolor only — the ask bar carries no dot |
| Interview | primary (no pulse) | "🎙 Interview — answer below" | separate writer → same helper |

Helper owns styling for BOTH writer sites (engine map + interview listener). Panel bg is
never tinted per state (noise + repaint cost).

## 8. ICON LANGUAGE

One family: hand-written Material-Symbols VectorDrawables, 24dp, `currentColor`, 2dp-weight
equivalent fills. Set: `arrow_back, arrow_forward, refresh, tab, more_vert, auto_awesome,
keyboard_arrow_down, send, fiber_manual_record, stop, cancel, check_circle, warning,
pause_circle, close, add, psychology, fact_check, shield, visibility, public, cookie,
history, download, expand_more, expand_less, settings, delete`. Dropped: `mic` (the 🎙 was
a false affordance for skill recording), `play` (text tonal buttons carry it).

## 9. ACCESSIBILITY

48dp targets · contentDescription on every icon-only control · contrast table §2 (AA) ·
bodySmall floor 12sp (11sp eliminated) · touch feedback via ripples · reduced-motion §5 ·
DayNight light+dark · RTL flag preserved · focus order = visual order (single column).

## 10. PERFORMANCE & BATTERY BUDGET

No continuous animation except the gated pulse · no blur · no WebGL · vector-only assets ·
APK target ≤ 4 MB (from 1.24 MB; delta = material+appcompat, documented) · startup: theme +
2 layouts, no font fetch, no extra I/O · pulse cancels on state exit and on scale-0 ·
ListView transcripts capped at 200 lines (existing behavior kept).

## 11. ONE-MOMENT RULE compliance

Per screen: browser = askbar pill (the single floating moment) · agent panel = sheet +
state chip (no other ornament) · tab sheet = M3 scrim+slide · settings = flat cards ·
zero shader/3D/glass effects anywhere. Often zero is better — splash-less app keeps it so.

## 12. WHAT IS DELIBERATELY NOT DONE (with reasons)

| Temptation | Verdict |
|---|---|
| True draggable BottomSheet for agent panel | Rejected — ListView scroll + IME (adjustResize) conflicts; pseudo-sheet delivers the geometry/motion |
| Inter/custom fonts | Rejected — +900KB (~2× APK) for letterform-only gain; Roboto is Material-native |
| Glass/blur surfaces | Deferred — RenderEffect API 31+ only, real cost on ordinary hardware; revisit after data |
| ChipView / MaterialSwitch XML swap in layout | Rejected — `getChildAt as TextView` cast + findViewById contracts |
| Edge-to-edge + insets rework | Deferred — WebView+IME coordination risk, no visual payoff this pass |
| Animated item transitions in ListView | Rejected — no ItemAnimator; hand-rolled = logic-lock violation |
| Panel bg tinted per agent state | Rejected — repaint cost + noise; chip carries state |

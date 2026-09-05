# Set-of-Marks (SoM) Architecture — v1.5.0

## What it is

Set-of-Marks visual grounding: when the agent captures a screenshot for the
vision path, every visible interactive element is annotated with a numbered
badge drawn directly on the bitmap. The model can then visually confirm which
element it is about to act on — **badge N marks element ref `eN`**.

Design goal: raise click/type aim on real pages without changing any browser
behavior. With the toggle off (Settings → Agent behavior), the pipeline is
byte-identical to v1.4.0.

## The one design decision that matters

**The mark space IS the ref space.** `DomExtractor.JS_EXTRACT` already tags
every enumerated element with `data-cx-ref="eN"` and returns its viewport
rect. SoM adds no second ID space, no new validator keys, no protocol schema
changes:

- `CLICK {ref: "e14"}` — exactly the pre-existing action contract
- badge "14" on the screenshot ↔ `e14` in the OBSERVATION element list

## Invariant safety

| Constraint | How SoM respects it |
|---|---|
| DOM extraction is read-only (tagging adds an attribute only) | Badges are drawn in Kotlin on the captured bitmap — the page is never touched |
| No `addJavascriptInterface` | No new in-page JS at all |
| Legend/picture can never drift | Badges derive from the SAME `PageObservation` instance the model receives (§19 compression included: compressed obs → badges only on the 40 surviving elements) |
| Refs die between pages | Badges are per-observation; prompt rule 3 already forces re-observe |

## Pipeline

```
AgentEngine (VISION block, policy-gated as before)
  └─ settings.somOverlay() ON
      └─ sink.screenshotAnnotatedBase64(obsFinal)        ← new AgentSink method (default → plain)
          └─ LiveWebViewSink:
              Screenshotter.capture(web)                  (PixelCopy, 8s cap)
              Screenshotter.scaleForUpload()              (≤1024w / 1400h)
              SomLayout.layout(elements, viewport, bitmap) (pure Kotlin, JUnit-tested)
              Screenshotter.annotate(scaled, layout)      (Canvas: outline + numbered badge)
              encodeJpeg → SomShot(base64, marks)
  └─ marks > 0 → "🔖 N marks drawn" log + AgentPrompt.marksLegend(marks)
      legend rides ONLY on the message that carries the pixels (§20-gated)
  └─ Som off / annotate failed / marks == 0 → plain screenshot, no legend
```

Badges are drawn AFTER downscale so numbers stay crisp under JPEG compression
(badge radius scales with bitmap width, clamped 8–22 px; violet-600 fill,
white text ≈ 4.6:1 contrast on any page).

## Text-only agent models

§20 behavior is unchanged: pixels only go to models that can see them. When
the agent model is text-only, the annotated screenshot is described by the
Role.VISION model, whose prompt now mentions badge numbers ("badge N marks
the element with ref eN") — numbered badges materially improve the
description's element anchoring. The MARKS legend is withheld from the agent
message whenever pixels are absent.

## Run stats (simple telemetry)

`AgentEngine.RunResult` — steps used / step budget / est. tokens / duration /
screenshots / outcome — is emitted exactly once per run at its terminal state
via `Listener.onRunStats` (default no-op; existing listeners compile
unchanged). The panel renders it as the result card:

`8 / 24 steps · ~3.1k tok · 42s · completed`

Token counts are ESTIMATES (chars/4 over prompt + response text) — providers
do not surface wire `usage` today; the "~" on the card says so honestly.
Steps consumed by refunded human gates never appear (StepBudget refund
semantics are unchanged).

## Failure / fallback ladder

| Condition | Behavior |
|---|---|
| `som_overlay` off | plain `screenshotBase64()` — v1.4.0 path, zero drift |
| capture fails | `"screenshot unavailable"` (unchanged) |
| annotation throws | plain JPEG fallback, marks 0 (LiveWebViewSink catch) |
| zero drawable marks (empty/degenerate elements) | annotated base64 reused, no second capture, no legend |
| model rejects images | VisionUnsupportedException → text-only run (unchanged) |

## Test map

- `SomLayoutTest` (7, pure JVM): ref↔number mapping, scaling, clamping,
  culling, degenerate skips, radius floor under downscale
- `SomAgentLoopTest` (6, Robolectric): annotated path taken when on / plain
  when off / rule 12 presence / legend-pixel coupling through stepMessage /
  exactly-once run stats with honest counters / failed-run outcome /
  zero-marks degradation
- Full suite: 185 tests, 20 suites, 0 failures (baseline 172 preserved)

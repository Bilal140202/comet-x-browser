# Vision Architecture — Comet-X

## Principle

Vision is a **complementary perception channel**, not the primary one. DOM+ARIA is cheaper, faster, more precise, and privacy-lighter; screenshots cost tokens, latency, and page-image exposure to the model provider. The 2026 browser-use ecosystem research is explicit that per-step screenshots "get expensive fast" — Comet-X encodes that lesson as an explicit policy layer (`VisionPolicy`) instead of leaving the decision to the model.

## Capture pipeline

```
WebView (live viewport)
   ↓ PixelCopy (API 26+, hardware-accelerated safe)  /  draw(Canvas) fallback
Bitmap (viewport-sized, ARGB_8888)
   ↓ downscale to ≤1024 px wide (≤1400 px tall), JPEG q72
Base64 JPEG (no data: prefix)
   ↓ ChatMessage(imageBase64Jpeg = …) → OpenAI-compatible multimodal content part
   {"type":"image_url","image_url":{"url":"data:image/jpeg;base64,…"}}
   ↓ VISION role model (Groq Llama-4 Scout default; any OpenAI-compatible VLM)
structured description → merged into observation JSON as vision.summary
```

## VisionPolicy triggers (AUTO mode)

| Trigger | Rationale |
|---|---|
| Agent requested vision (`request_vision` / `screenshot`) | model knows it cannot resolve something from DOM alone |
| Last action failed | visual context often explains *why* (overlay, cookie wall, empty state) |
| Challenge suspected (ChallengeDetector ≠ none) | verification surfaces are visual by nature |
| Zero interactive elements extracted | page is probably canvas/image-based or not loaded |
| <3 elements AND <60 chars of text | near-empty DOM — visually confirm what the page shows |
| Canvas-heavy heuristic (<120 chars text, <8 elements) | maps, games, photo editors |
| First step with <5 elements | cheap one-time visual grounding |

ALWAYS mode sends every step (user opt-in, cost warning in Settings). OFF mode sends only explicit `request_vision` — DOM-only agent.

## What the vision channel is asked to do

The vision description merges into the observation; the planner uses it to:

- recognize buttons/menus/modals/popups/consent banners/login pages/verification screens that the DOM snapshot underrepresents;
- resolve *where* to act when several similar controls exist ("the search box at upper-right");
- classify challenge interfaces (reCAPTCHA-style widgets, hCaptcha-style checkboxes, Cloudflare interstitials, MFA screens) — which triggers **pause + human takeover**, never automated solving (no circumvention is implemented, by design and by policy).

## Coordinate path (vision → action)

1. VLM describes layout with approximate positions (or the model plans from the merged description).
2. Model emits `click_at {x, y}` in CSS viewport pixels.
3. `ActionValidator` bounds-checks against the observed viewport.
4. `ActionExecutor.jsClickAt` resolves `document.elementFromPoint(x, y)` and snaps to the nearest interactive ancestor (`a, button, input, select, textarea, [role=…], label`) before dispatching the full pointer+mouse sequence.
5. The executed target's text is returned in the result so the planner can verify *what* was actually hit.

## Privacy posture

- Screenshots are transmitted only to the provider configured by the user (same channel as DOM observations), only when the policy fires, and only the browser viewport (never other apps or the system UI).
- No screenshots are persisted: the bitmap and base64 are transient within one step.
- The default vision model runs on the user's chosen provider; switching providers in Settings switches the vision channel with it.
- This is disclosed in README (Privacy section) — sending page imagery to a cloud model is inherent to cloud-VLM vision and the user controls it via VisionMode.

## Measured behavior (test harness)

- Screenshot capture latency: sub-100 ms on the test pages (PixelCopy path).
- Vision only fires on policy triggers in the integration tests (`AgentLoopIntegrationTest` asserts `sink.screenshotBase64()` call counts via `screenshotCalls`).
- Observation without vision keeps step payload ~1–3 KB; with vision, +~40–120 KB base64 depending on page density — the reason the gate exists.

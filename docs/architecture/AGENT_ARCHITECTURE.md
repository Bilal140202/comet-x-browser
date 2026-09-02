# Agent Architecture — Comet-X

## The loop (brief §12, implemented verbatim)

```
USER GOAL
   ↓ (AgentEngine.run)
PLAN      ← system prompt: goal + rules + action schema + skill + memory
OBSERVE   ← DomExtractor (DOM snapshot) + page metadata + tabs
UNDERSTAND← PromptInjectionDetector + ChallengeDetector
VISION?   ← VisionPolicy gate (auto / always / off / on-request)
SELECT    ← LLM (role-routed) → one JSON action
PARSE     ← ActionParser (fences, salvage, repair round-trip)
VALIDATE  ← ActionValidator (schema, refs, bounds, URL policy)
POLICY    ← SafetyPolicy (NONE / CONFIRM / BLOCK)
CONFIRM?  ← human dialog (consequential actions)
EXECUTE   ← ActionExecutor / WebView navigation APIs
OBSERVE RESULT ← next iteration's observation; result recorded in history
VERIFY    ← model decides: done (verifiable) / continue / replan
  YES → DONE (summary)
  NO  → RECOVER (error feedback, replan nudge, challenge pause, ask_user)
```

## State machine

```
IDLE ──run()──► RUNNING ──done──► COMPLETED
                  │  ▲                RUNNING ──fail──► FAILED
   ┌──────────────┘  └── resume() ────┐   RUNNING ──stop──► CANCELLED
   │ takeControl() / challenge / ask  │
   ▼                                  │
AWAITING_USER ◄──challenge detection──┘
AWAITING_CONFIRM ◄──SafetyPolicy CONFIRM──┘
```

All gates are **order-independent**: `resume()`/`confirm()` may arrive before the engine reaches the gate; arrivals are recorded under a lock and consumed at the gate. Every gate has a 15-minute hard timeout after which the engine fails cleanly instead of hanging.

## Action protocol

The model returns exactly one JSON object per step. Full schema is embedded in `AgentPrompt.ACTION_SCHEMA` and mirrored in `ActionValidator.KNOWN_ACTIONS`: `navigate, back, forward, reload, click, click_at, type, press_key, scroll, select, wait, find_text, find_element, extract, screenshot, request_vision, open_tab, close_tab, switch_tab, download, copy, paste, zoom, remember, done, fail, ask_user`.

Terminal/meta actions (`done`, `fail`, `ask_user`, `screenshot`, `request_vision`, `remember`) short-circuit before execution; everything else passes validator → policy → executor.

## Perception fusion (brief §10–11)

The agent never relies on a single source:

1. **DOM (primary)** — interactive elements with roles/ARIA/labels/geometry; works offline, cheap, precise refs.
2. **Accessibility semantics** — derived from ARIA roles/labels inside the DOM snapshot (semantic DOM). OS-level `AccessibilityNodeInfo` capture was evaluated and deferred: it requires a user-enabled global service and duplicates the semantics already available via DOM+ARIA for web content (documented decision, `../research/FOUNDATION_COMPARISON.md` §3).
3. **Vision (complementary)** — policy-gated screenshots for canvas-heavy/ambiguous pages, failures, challenges, or explicit `request_vision`.
4. **Browser metadata** — URL, title, viewport, scroll position, tab list, loading state.

Vision coordinates flow through `click_at(x,y)` with `document.elementFromPoint` snapping to the nearest interactive ancestor — the vision system *suggests*, the validator *disposes*.

## Recovery model

- **Action failed** → `lastActionFailed=true` → next observation includes the failure → vision likely triggers → model retries differently.
- **Invalid action** → validator rejection reason is fed back in history ("rejected: …") — the model corrects itself.
- **Unparseable output** → one automatic repair round-trip; repeated failure → fail with reason.
- **Repetition** → identical action signatures (3 of last 4) inject a replan nudge; the step budget bounds the worst case.
- **Challenge** → pause + human takeover + resume with re-observation.
- **Ambiguity** → `ask_user` carries the question to the UI; the answer is injected into the next prompt as `USER RESPONSE:`.

## Multi-agent evaluation (brief §36)

smolagents-style manager/worker (RESEARCH/BROWSER/VISION/VERIFIER agents) was prototyped conceptually and **rejected for v1**: at task scale, splitting roles multiplies API calls and latency (3 model round-trips per step instead of 1) without measurable reliability gain, because our *role routing* already gives each step the right model (fast interpreter vs strong planner vs vision). The design keeps the door open: `ModelRouter.Role` + per-role system prompts are the seam where worker agents can be introduced without touching the loop. Verdict: one strong planner + routed roles > several weak agents (recorded in `../research/FOUNDATION_DECISION.md`).

## Skills

Declarative JSON (assets/skills/*.json): goal framing, strategy steps, verification criteria, failure handling, and hard constraints (e.g., shopping: "NEVER complete a purchase"). Skills are injected into the system prompt and auto-selected by keyword scoring, or pinned via chips in the agent panel. The registry ships 9 skills: research, shopping, travel, forms, comparison, extraction, productivity, downloads, general-web.

## Memory (brief §25)

| Tier | Storage | Lifetime | Controls |
|---|---|---|---|
| Session | `MemoryStore.Session` (in-process) | one agent run | implicit |
| Browser | `browser_state.json` (last page) | until cleared | Settings → Clear |
| User | `user_memory.json` (explicit facts) | until cleared | remember action (confirm-gated), View/Delete/Clear, global disable |

Memory writes by the agent are **confirm-gated** and re-injected into later prompts labeled **UNTRUSTED data** — a page cannot plant persistent instructions through a one-time injection (red-team finding F3, fixed).

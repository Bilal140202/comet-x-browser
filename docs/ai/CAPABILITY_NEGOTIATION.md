# CAPABILITY NEGOTIATION

> COMET-X Phase 2 — how the app decides what a model can do, and what happens
> when it can't.

## Three evidence sources (never just one)

1. **Metadata** — the provider's own catalog (`supported_parameters`,
   `input_modalities`, `context_window`, `pricing`). Free, instant, sometimes
   missing or wrong.
2. **Safe probes** — tiny fixed requests (maxTokens ≤ 16) executed by
   `CapabilityProber` when the user presses *Test & Enable* or runs the Agent
   Compatibility self-test. Only DEFINITIVE results are cached (a 500 or a
   429 must never cache "unsupported").
3. **Runtime error interpretation** — every in-flight failure is classified by
   `ProviderErrorClassifier`; an `UNSUPPORTED_RESPONSE_FORMAT` 400 triggers an
   immediate silent protocol downgrade (see FALLBACK_PROTOCOLS.md).

## Capability vocabulary

`CHAT · TOOL_CALLING · JSON_OBJECT · JSON_SCHEMA · VISION · REASONING · STREAMING`

Stored per `ModelInfo`, refined by probes, extended at runtime. Capabilities
are **never removed by a guess** — only by a definitive probe or error.

## The output-protocol ladder (§5)

The agent needs ONE action per turn. Models differ wildly in what they can
honor, so the required format is NEGOTIATED, not assumed:

```
BEST   JSON_SCHEMA    (strict structured outputs — OpenRouter/Groq subset)
  ↓    JSON_OBJECT    (json mode — broad compatibility)
  ↓    TOOL_CALLING   (single forced browser_action tool call)
  ↓    TAGGED_TEXT    (<agent> ACTION=… KEY=VALUE block protocol)
FLOOR  PLAIN_TEXT     (tolerant line parser — still refuses free prose)
```

Selection: `AgentProtocol.bestFor(capabilities)`; per-model known-good
protocols are remembered in the catalog so later steps skip rungs that already
failed. Downgrades are SILENT to the user (an event line in the AI log only).

## ModelResponseInterpreter (§7)

`MODEL OUTPUT` is fully separated from `AGENT ACTION`:

| Interpreter              | Consumes                                              |
|--------------------------|-------------------------------------------------------|
| `JsonInterpreter`        | JSON Schema / JSON mode output (incl. fences, prose-wrapped) |
| `ToolCallInterpreter`    | `choices[0].message.tool_calls[0].function.arguments` |
| `TaggedTextInterpreter`  | `<agent> ACTION=CLICK / REF=e7 / NOTE=… </agent>` blocks or bare `KEY=VALUE` lines |
| `PlainTextInterpreter`   | last resort — structured lines only, NEVER free prose |

Every interpreter yields the same canonical **AgentDecision**
(`action, target, value, reason, confidence, extras, done`) which maps onto
the action JSON consumed by ActionValidator → SafetyPolicy → ActionExecutor.
The agent engine never knows which protocol produced a decision.

## Safety of the text floor (§9)

The tagged parser:
- tolerates whitespace, capitalization, quotes, `:`/`=` separators, prose
  around the block
- REJECTS unknown verbs (normalized against the canonical action list)
- REJECTS output without an `action` line — ambiguous or free-form model text
  can never be executed as a browser command
- everything still passes the ActionValidator (refs, geometry, URL checks)

## Context overflow (§19)

`CONTEXT_TOO_LARGE` is classified from the provider error; the engine
compresses the observation (elements trimmed to 40, page text to 4000 chars,
history halved) and re-issues the step once. The user never sees a raw
context-length API error.

## Vision (§20)

- The agent model never receives pixels it cannot read (checked BEFORE the call).
- If the agent model is text-only: a separate vision-capable model anywhere in
  the chain describes the screenshot as text (`describeScreenshot`).
- If no vision model exists: DOM/accessibility perception drives the agent.
- The agent NEVER fails merely because vision is unavailable.

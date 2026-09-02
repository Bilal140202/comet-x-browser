# FALLBACK PROTOCOLS

> COMET-X Phase 2 — the exact wire formats the agent can speak, in priority
> order, and how the app moves between them.

## The ladder

| rank | protocol     | wire form                                                    | request extra        |
|------|--------------|--------------------------------------------------------------|----------------------|
| 0    | JSON_SCHEMA  | `{"action": "...", ...}` matching `AgentProtocolContract.decisionSchema()` | `response_format: json_schema` |
| 1    | JSON_OBJECT  | any single JSON object with an `action` key                   | `response_format: json_object` |
| 2    | TOOL_CALLING | forced `browser_action` tool call, arguments = decision       | `tools` + `tool_choice` |
| 3    | TAGGED_TEXT  | `<agent> ACTION=CLICK / REF=e7 / NOTE=… </agent>` (or bare `KEY=VALUE` lines) | prompt contract only |
| 4    | PLAIN_TEXT   | tolerant `KEY=VALUE` line parser (same contract, looser)      | prompt contract only |

The ladder is the Phase 2 answer to "This model does not support JSON":
**that message is now a downgrade instruction, not an error.**

## Downgrade triggers

1. **Probe evidence** (Test & Enable) — capability removed before the first
   agent step; known-good protocol memo updated.
2. **Runtime 400** classified `UNSUPPORTED_RESPONSE_FORMAT` /
   `UNSUPPORTED_TOOL_CALLING` — instant downgrade, same model, same step:
   ```
   Groq: chat-model-1 rejected JSON mode — switching to Tool calling
   Groq: chat-model-1 rejected Tool calling — switching to Tagged text
   ```
3. **Unparseable output at HTTP 200** — one protocol-aware repair round
   ("respond with ONLY the JSON object" / "respond with ONLY the <agent>
   block"); if still unparseable → downgrade; at the floor → next candidate.
4. All transitions are recorded in the AI event log and remembered per model
   (`knownGoodProtocol`) so the cost is paid once, not every step.

## TAGGED_TEXT contract (the safety floor)

```
<agent>
ACTION=CLICK
REF=e7
NOTE=opening the search box
</agent>
```
- accepted separators: `=` or `:`, any capitalization, quoted or bare values,
  optional surrounding prose, optional fences
- allowed keys mirror the JSON field map (ref, text, url, x, y, key,
  direction, amount, ms, option, what, description, index, level, key_name,
  fact, question, summary, reason, note)
- unknown verbs and action-less output are REJECTED (null → repair/downgrade),
  never guessed
- output still flows through ActionValidator → SafetyPolicy before execution

## Model → AgentDecision normalization (§8)

All of these produce the SAME AgentDecision:

```
JSON model:      {"action":"click","target":"search_button"}
Tool model:      tool_call browser_action {action:"click", ref:"e7"}
Tagged model:    ACTION=CLICK / TARGET=search_button
```

→ `AgentDecision(action=CLICK, target=search_button)` → ActionValidator.

## Recovery scenarios (ladder interactions)

| scenario                              | behavior                                            |
|---------------------------------------|-----------------------------------------------------|
| model rejects json_object             | → tool calling → tagged text (same model)           |
| model rejects every structured format | tagged text from the start, silently                |
| model 404s mid-task                   | catalog refresh → best replacement → retry          |
| model 429s                            | next candidate → next provider → bounded backoff    |
| observation exceeds context           | compress observation (§19), one retry               |
| agent model lacks vision              | separate vision model describes screenshot, else DOM |
| model outputs prose                   | repair round → downgrade → never executes prose     |
| all providers exhausted               | normalized error surfaced to the agent panel        |

## Regression coverage

`ProviderFailoverTest` (R1–R5) + `TextProtocolTest` + red-team fixtures in
`app/src/test/` — see [../testing/AI_COMPATIBILITY_TESTS.md](../testing/AI_COMPATIBILITY_TESTS.md).

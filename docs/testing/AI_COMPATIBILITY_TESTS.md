# AI COMPATIBILITY TESTS

> COMET-X Phase 2 — the permanent regression matrix for provider/model
> compatibility. All tests run headless (Robolectric + scripted
> `HttpTransport`); no network, no real keys, real production router code.

## Suites

| suite                       | focus                                                      |
|-----------------------------|------------------------------------------------------------|
| `ProviderFailoverTest`      | §28–§32 regression matrix + red-team catalogs (§39)        |
| `TextProtocolTest`          | 5 interpreters, decision mapping, protocol ladder, red-team output |
| `CapabilityNegotiationTest` | catalog normalization (Groq + OpenRouter fixtures), error taxonomy |
| `AgentLoopIntegrationTest`  | full engine loop with the negotiated pipeline              |
| `ChainAndUrlTest` / `ProviderParsingTest` | v1.1.0 contract retained (wire parsing, URLs) |

## The five permanent regression tests

**R1 (§28) — key-only AUTO flow.** Valid key, no manual model: discovery
returns a live-catalog fixture (incl. `whisper-large-v3`, `llama-guard-4-12b`
decoys), the router selects the agent-compatible model, the step parses, and
non-chat endpoints are excluded. *Must stay passing forever.*

**R2 (§29) — model rejects the preferred JSON format.** First call carries
`response_format: json_object`, provider answers
`400 "This model does not support JSON format"`, the router silently
downgrades (json → tool → tagged), the step completes, and the known-good
protocol is remembered. NO FAILURE surfaces to the user.

**R3 (§30) — configured model becomes unavailable.** `404 "model
decommissioned"` → catalog invalidated → re-ranked → replacement selected →
retry → task continues; the auto-switch is visible in turn events.

**R4 (§31) — chat model without vision.** Resolved agent model has no VISION
capability; `describeScreenshot` returns null (no vision model in chain) and
the agent proceeds on DOM/accessibility perception. Pixels never reach a
non-vision model.

**R5 (§32) — no structured response format at all.** The model ignores JSON
instructions and answers in tagged text; the ladder lands on TAGGED_TEXT and
TYPE (and by extension CLICK/SCROLL/NAVIGATE) executes.

## Red-team matrix (§39)

- malformed /models bodies: `not json`, `{}`, `{"data":[]}`, `{"data":[null,{},"x"]}`
- duplicate model entries → deduplicated
- catalog with only audio/guard models → no candidates selected (never crashes)
- truncated JSON, partially valid JSON, single-quoted JSON, brace-in-string payloads
- tool-call envelopes with corrupt `arguments`
- unknown verbs, action-less output, prose-only answers → never executed
- model disappearing mid-task → surfaced in events + retried safely
- 400/401/402/404/429/500 all map to the normalized error taxonomy
  (`CapabilityNegotiationTest`)

## Running

```bash
./gradlew testReleaseUnitTest            # full suite
./gradlew testReleaseUnitTest --tests "com.cometx.browser.ProviderFailoverTest"
```

Phase 2 result: **133 tests, 0 failures** (11 suites).

## Live-catalog verification

The OpenRouter parser is additionally verified against the provider's real
public `/api/v1/models` endpoint (no key required): free-model pricing
detection, `supported_parameters` mapping, and modality-based vision hints are
checked against the live catalog at release time (see BUILD report
"REAL MODELS TESTED").

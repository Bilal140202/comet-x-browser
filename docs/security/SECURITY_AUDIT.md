# Security Audit — Comet-X

Audit performed at release time (Phase 13/17) by the Project Director + an independent red-team agent pass. Every claim below is backed by a file/line reference or an executed test.

## 1. Code security controls (verified)

| Control | Implementation | Verification |
|---|---|---|
| No native JS bridge | zero `addJavascriptInterface` call sites | repo-wide grep: 0 hits (only a comment stating the policy) |
| WebView hardening | `allowFileAccess=false`, `allowContentAccess=false`, `allowFileAccessFromFileURLs=false`, `allowUniversalAccessFromFileURLs=false`, `mixedContentMode=NEVER_ALLOW`, geolocation off | `BrowserController.createWebView()` |
| Cleartext policy | network_security_config: cleartext denied globally, allowed only for `127.0.0.1`/`localhost` (test server) | `res/xml/network_security_config.xml` |
| API key storage | AES-256/GCM, Keystore master key, random per-entry IV, plaintext never on disk | `SecureStore` |
| Keys never logged | `Logx` call-site audit (12 sites) — none handle key material; provider errors truncate server response, never the request | `Providers.summarizeError`, audit log below |
| TLS | platform-default `HttpURLConnection`; no trust-all or hostname-verifier overrides | `util/Http.kt` |
| Action protocol enforcement | unknown actions/fields rejected; http(s)-only URL policy incl. credential-shaped query params; refs must exist in current observation; coordinate/key/wait/zoom bounds | `ActionValidator` — 15 unit tests |
| Consequential-action gates | password/checkout/delete/send/agreement/download-executable → human dialog; deny default on timeout | `SafetyPolicy` — 8 unit tests + engine integration tests |
| Prompt injection defense | 11-rule detector on every observation; findings surface in UI log + harden system prompt; page text wrapped `[UNTRUSTED PAGE CONTENT]` | `PromptInjectionDetector` — 9 unit tests |
| Injection→memory persistence closed | `remember` confirm-gated; memory re-injected labeled UNTRUSTED | `SafetyPolicy.assess`, `AgentPrompt.system` (red-team F3 fix) |
| Paste gate parity | paste subject to the same password/checkout gates as typing | `SafetyPolicy` (red-team F4 fix) |
| URL policy on all url-bearing actions | `navigate`/`open_tab`/`download` share `checkUrl()` | `ActionValidator` (red-team F5 fix) |
| Third-party cookies | user setting actually applied per-WebView (default off) | `BrowserController` (red-team F6 fix) |
| Loopback server hygiene | binds 127.0.0.1 only; fixed 404 body (no path reflection); opt-in via menu; stopped in `onDestroy` | `LocalTestServer` (red-team F7 fix) |
| Intent scheme gating | only http/https open in-app tabs | `MainActivity.openInNewTab` (red-team F8 fix) |
| Gate race elimination | resume/confirm recorded under lock, consumed order-independently; 15-min timeout on all human gates | `AgentEngine` gate rewrite (red-team F10-adjacent + integration-test regression catch) |
| Loop/cost safety | step budget 4–60; repetition replan nudge; wait bounds; engine cancellable at every await | `AgentEngine`, `SettingsRepository` |
| Component exposure | only `MainActivity` exported (launcher + http/https VIEW); `allowBackup=false`; `SettingsActivity` exported=false | `AndroidManifest.xml` |

## 2. Red-team pass (independent agent)

Scope: 28 main-source Kotlin files + manifest + network config + build script, adversarial review across 10 attack vectors.

Outcome: **10/10 vectors PASS** after remediation. Findings raised and their dispositions:

| ID | Severity | Finding | Disposition |
|---|---|---|---|
| F1 | (reported CRITICAL) | "missing skills package → cannot compile" | **False positive** — `skills/SkillRegistry.kt` exists; build + 78 tests green. Noted for report honesty. |
| F3 | MEDIUM | remember→memory→authoritative-prompt persistence loop | **Fixed** (confirm gate + UNTRUSTED labeling) |
| F4 | MEDIUM | paste bypasses typing gates | **Fixed** (type‖paste gate parity) |
| F5 | MEDIUM | open_tab/download skip navigate URL checks | **Fixed** (shared `checkUrl`) |
| F6 | LOW | third-party-cookie setting dead code | **Fixed** (applied per-WebView) |
| F7 | LOW | loopback 404 reflects path | **Fixed** (fixed body) |
| F8 | LOW | intent URL unvalidated | **Fixed** (scheme gate) |
| F10 | LOW | human gates could hang forever | **Fixed** (15-min timeouts + race-free gate redesign) |
| F9 | LOW | ref TOCTOU re-homing | Mitigated by per-observation re-tagging + ref-existence validation; element-tag check deferred to roadmap (documented) |

Regression: full suite re-run after fixes — **78/78 pass**.

## 3. Secret scanning (pre-push gate)

Executed before `git push` (results recorded in `TEST_REPORT.md` §delivery):

- Regex sweep of the entire tree + git history for: GitHub PATs (`ghp_`, `gho_`, `github_pat_`), Telegram bot tokens (`\d{8,10}:[A-Za-z0-9_-]{35}`), AWS keys, Google API keys, OpenAI `sk-` keys, generic `password=`, the literal bot/chat identifiers from the engagement, private key blocks, keystore binaries.
- Result: **0 findings in committed content.** `.gitignore` excludes `local.properties`, keystores, `.env*`; the release keystore is generated locally at build time and never committed.
- Delivery credentials were used **only** as session environment variables in the build sandbox; they appear in no committed file.

## 4. Dependency audit

All runtime dependencies are `org.jetbrains.kotlinx:kotlinx-coroutines-android` (Apache-2.0) plus the Android platform SDK. No third-party network/UI/serialization libraries → no transitive CVE surface beyond the platform. Test-scope dependencies (junit, robolectric, androidx.test, org.json, coroutines-test) do not ship in the APK.

## 5. Known limitations (security-relevant)

1. Heuristic detectors are not a guarantee (see THREAT_MODEL §Residual risks).
2. Single persistent browser profile in v1 (no per-profile isolation).
3. `open_tab`/`close_tab`/`switch_tab`/`download` are validated but only partially wired to the browser layer in v1 (executor reports them as not-executable-here) — defense in depth kept for future enablement.
4. Ref-TOCTOU element-tag re-verification (red-team F9) deferred; impact bounded by validation + human gates.

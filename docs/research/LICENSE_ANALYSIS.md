# License Analysis — Comet-X

Scope: every foundation, dependency, API, and model family used by Comet-X, checked against the mandated criteria (license, commercial use, redistribution, copyleft, attribution, trademarks, bundled dependencies, model licenses, dataset licenses, API restrictions). Reviewed during Phase 2.

## 1. Code dependencies (what ships inside the APK)

| Component | Version | License | Commercial use | Redistribution | Copyleft | Notes |
|---|---|---|---|---|---|---|
| Android SDK / platform-34 / build-tools 34 | 34 | Apache-2.0 (SDK) | Yes | Yes (SDK terms) | None | WebView itself is a system component, not bundled |
| Android Gradle Plugin | 8.5.2 | Apache-2.0 | Yes | Yes | None | Build-time only |
| Gradle | 8.7 | Apache-2.0 | Yes | Yes (wrapper jar redistributable) | None | Wrapper committed for reproducibility |
| Kotlin stdlib + coroutines | 1.9.24 / 1.8.1 | Apache-2.0 | Yes | Yes | None | Bundled in dex |
| org.json | platform | Apache-2.0 (bundled in Android) | Yes | — | None | Part of Android platform API |

**No GPL/AGPL code ships in the APK. No copyleft obligation attaches to Comet-X source.** Comet-X itself is distributed under MIT (see `LICENSE`).

## 2. Foundations evaluated but NOT copied from

| Project | License | How used | Obligation status |
|---|---|---|---|
| Hugging Face smolagents | MIT | **Concepts only** (agent loop, tool-calling pattern, HITL). No Python code, no line-by-line port, no text extraction | MIT permits derivative concepts without obligation; attribution note retained here: we thank the smolagents team; their docs informed the loop design |
| browser-use | MIT | **Concepts only** (DOM-state serialization, hybrid perception, action registry, recovery) | Same as above |
| GeckoView | MPL-2.0 | Evaluated, rejected, not used | N/A |
| Chromium (WebView engine) | BSD-3-Clause + per-component | Used via the Android WebView *API* (system component); no Chromium code redistributed by us | No obligations for API consumers; "Chromium"/"Chrome" trademarks not used in product UI |

## 3. APIs (runtime, user-configured keys)

| API | Terms | Key handling | Restrictions honored |
|---|---|---|---|
| Groq | Commercial API ToS; per-model licenses | User key, Keystore-encrypted, never bundled/logged | Rate limits; Llama models under Meta Llama Community License (served by Groq; attribution requirements are Groq's serving obligation; we do not redistribute weights) |
| OpenRouter | Commercial API ToS; per-model licenses vary | Same | Per-model upstream licenses are the user's responsibility when selecting models; UI notes this |
| Hugging Face Inference Providers (router.huggingface.co) | HF Hub ToS | Same (token used as Bearer) | Router only serves models whose licenses permit inference; no weights redistributed |
| Generic OpenAI-compatible | User's own endpoint terms | Same | — |

**Model licenses:** no model weights are bundled in the APK, so no model license obligations attach to the app itself. Default model IDs (e.g., Groq `llama-3.3-70b-versatile`, `meta-llama/llama-4-scout-17b-16e-instruct`) are referenced as hosted-inference endpoints only. All model IDs are user-overridable because provider catalogs rotate.

**Dataset licenses:** no datasets are shipped or required. All test pages under `app/src/main/assets/testpages/` are original content written for this project.

## 4. Trademarks

- "Comet-X" is the project's own name; no search-trademark conflict assumed for this exercise.
- We do not use "Android", "Chrome", "Chromium", "Firefox", "Hugging Face", "Groq", or provider logos in the product UI except as *nominative fair use* in Settings (provider names the user configures against).
- Telegram delivery uses the Bot API per its ToS (bots may send documents up to 50 MB — our APK is a few MB).

## 5. Legal red lines honored by implementation

1. **No CAPTCHA circumvention**: challenge detection exists to *pause and hand control to the human*. No token theft, no solver services, no anti-bot defeat logic. (Spec §14 explicitly forbids it; THREAT_MODEL.md treats circumvention as out of scope.)
2. **No proprietary reverse engineering**: nothing was decompiled or extracted from closed-source browsers.
3. **No credential leakage**: GitHub PAT / Telegram tokens are session-only environment variables in the build sandbox; the app contains no baked-in secrets (verified by secret scan before push; see SECURITY_AUDIT.md).
4. **Redistribution rights**: MIT-licensed original code + Apache-2.0 deps → free to redistribute the APK with attribution preserved in source headers.

## 6. Decision log

| Decision | Rationale |
|---|---|
| Ship under MIT | Maximally permissive, compatible with all Apache-2.0 deps |
| Port agent concepts, don't fork Python | License-permissive either way, but porting avoids bundling a Python runtime (size/attack surface) |
| No "smolagents" / "browser-use" code vendored | Keeps provenance clean: 100% original Kotlin, concepts attributed |
| Model IDs configurable, not hardcoded | Provider catalogs rotate; avoids implying bundled weights |

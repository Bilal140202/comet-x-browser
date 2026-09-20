package com.cometx.browser.ai

import android.content.Context
import com.cometx.browser.CometApp

/**
 * ProviderSet (v1.8.0) — single construction site for the router's provider
 * map. v1.1.0–v1.7.0 built this map inline in MainActivity; the background
 * agent service (v1.8.0) needs the SAME chain, and two hand-copied maps
 * would silently drift (a provider added to one and not the other would make
 * background runs behave differently from foreground runs).
 *
 * Behavior is byte-identical to the v1.7.0 inline construction:
 *   - same four cloud providers + the app-scoped local llama.cpp provider
 *   - keys are read LIVE from settings at call time (never cached here)
 *   - base URLs are applied separately via [applyBaseUrls]
 */
object ProviderSet {

    fun build(settings: SettingsRepository): Map<String, LlmProvider> = mapOf(
        "groq" to GroqProvider(keyProvider = { settings.apiKey("groq") }),
        "openrouter" to OpenRouterProvider(keyProvider = { settings.apiKey("openrouter") }),
        // v2.2.0: NVIDIA NIM joins additively — without a key isReady()==false so
        // it never enters the live chain (byte-identical to v2.1.0 when unused).
        "nvidia" to NvidiaNimProvider(keyProvider = { settings.apiKey("nvidia") }),
        "huggingface" to HuggingFaceProvider(keyProvider = { settings.apiKey("huggingface") }),
        "custom" to CustomOpenAIProvider(
            keyProvider = { settings.apiKey("custom") },
            readyCheck = { !settings.apiKey("custom").isNullOrBlank() || !settings.baseUrl("custom").isNullOrBlank() }
        ),
        // v1.6.0: on-device llama.cpp provider (app-scoped singleton so the
        // loaded model survives Settings round-trips and activity recreation)
        SettingsRepository.LOCAL_PROVIDER_ID to CometApp.app.localAI.provider,
        // v2.1.0: in-browser Transformers.js provider (app-scoped singleton,
        // fully lazy — joins the chain only after a web model is selected)
        SettingsRepository.WEB_PROVIDER_ID to CometApp.app.webAI.provider
    )

    /** Re-applies user-saved base URLs (expert review P1-7). */
    fun applyBaseUrls(settings: SettingsRepository, providers: Map<String, LlmProvider>) {
        for ((pid, prov) in providers) {
            (prov as? OpenAICompatibleProvider)?.let { oai ->
                settings.baseUrl(pid)?.let { oai.setBaseUrl(UrlNormalizer.normalize(it)) }
            }
        }
    }
}

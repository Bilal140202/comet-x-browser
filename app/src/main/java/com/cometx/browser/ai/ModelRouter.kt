package com.cometx.browser.ai

import com.cometx.browser.util.Logx

/**
 * ModelRouter — maps agent roles to (provider, model) pairs.
 * Roles follow brief §17: FAST, REASONING, VISION, STRONG, CHEAP.
 * All model ids are user-configurable; defaults are best-known-good and are
 * deliberately NOT treated as immutable (provider catalogs rotate).
 */
class ModelRouter(
    private val settings: SettingsRepository,
    private val providers: Map<String, LlmProvider>
) {

    enum class Role { FAST, REASONING, VISION, STRONG, CHEAP }

    data class Target(val provider: LlmProvider, val model: String)

    /** The live fallback chain: enabled + configured providers in user-defined order.
     *  Providers are resolved by their own id (authoritative), so out-of-registry
     *  providers (tests, embedded runtimes) still work when none configured. */
    fun chain(): List<LlmProvider> {
        val byId = providers.values.associateBy { it.id }
        val configured = settings.liveChain().mapNotNull { byId[it] }.filter { it.isReady() }
        if (configured.isNotEmpty()) return configured
        return providers.values.filter { it.isReady() && it.id !in SettingsRepository.ALL_PROVIDERS }
    }

    fun resolve(role: Role): Target? {
        for (p in chain()) {
            if (!p.isReady()) continue
            val model = settings.modelFor(p.id, role) ?: defaultModelFor(p.id, role)
            return Target(p, model)
        }
        return null
    }

    /**
     * Run a chat across the user-ordered fallback chain: primary first, then the
     * remaining enabled providers in priority order until one succeeds.
     */
    suspend fun chatWithFallback(role: Role, messages: List<ChatMessage>, temperature: Double = 0.2): String {
        val candidates = chain()
        if (candidates.isEmpty()) throw ProviderException("No model provider is configured. Open Settings → AI Providers, paste a key, press Save.")
        var last: Exception? = null
        for (p in candidates) {
            val model = settings.modelFor(p.id, role) ?: defaultModelFor(p.id, role)
            try {
                return p.chat(messages, model, temperature)
            } catch (e: Exception) {
                Logx.w("provider ${p.id} failed: ${e.message}")
                last = e
                // 401/403 (bad key) and 429 (rate limit) both fall through to the next provider.
            }
        }
        throw last ?: ProviderException("All providers failed")
    }

    companion object {
        /** Best-known-good default per (provider, role). Public so Settings can show it in dropdowns. */
        fun defaultModelFor(providerId: String, role: Role): String {
            val table = when (providerId) {
                "groq" -> mapOf(
                    Role.FAST to "llama-3.1-8b-instant",
                    Role.CHEAP to "llama-3.1-8b-instant",
                    Role.REASONING to "llama-3.3-70b-versatile",
                    Role.STRONG to "llama-3.3-70b-versatile",
                    Role.VISION to "meta-llama/llama-4-scout-17b-16e-instruct"
                )
                "openrouter" -> mapOf(
                    Role.FAST to "openai/gpt-4o-mini",
                    Role.CHEAP to "openai/gpt-4o-mini",
                    Role.REASONING to "openai/gpt-4o-mini",
                    Role.STRONG to "anthropic/claude-3.5-sonnet",
                    Role.VISION to "openai/gpt-4o-mini"
                )
                "huggingface" -> mapOf(
                    Role.FAST to "meta-llama/Llama-3.1-8B-Instruct",
                    Role.CHEAP to "meta-llama/Llama-3.1-8B-Instruct",
                    Role.REASONING to "meta-llama/Llama-3.3-70B-Instruct",
                    Role.STRONG to "meta-llama/Llama-3.3-70B-Instruct",
                    Role.VISION to "Qwen/Qwen2.5-VL-7B-Instruct"
                )
                else -> mapOf(
                    Role.FAST to "gpt-4o-mini",
                    Role.CHEAP to "gpt-4o-mini",
                    Role.REASONING to "gpt-4o",
                    Role.STRONG to "gpt-4o",
                    Role.VISION to "gpt-4o-mini"
                )
            }
            return table[role] ?: table[Role.REASONING]!!
        }
    }
}

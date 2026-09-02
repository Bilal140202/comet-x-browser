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

    fun resolve(role: Role): Target? {
        val activeId = settings.activeProviderId()
        val provider = providers[activeId] ?: providers.values.firstOrNull { it.isReady() } ?: return null
        if (!provider.isReady()) return null
        val model = settings.modelFor(provider.id, role) ?: defaultModelFor(provider.id, role)
        return Target(provider, model)
    }

    /**
     * Convenience: run a chat with automatic fallback — if the primary target
     * fails, try other ready providers before giving up.
     */
    suspend fun chatWithFallback(role: Role, messages: List<ChatMessage>, temperature: Double = 0.2): String {
        val primary = resolve(role) ?: throw ProviderException("No model provider is configured. Open Settings → AI Providers.")
        val candidates = mutableListOf(primary)
        for ((_, p) in providers) {
            if (p.id == primary.provider.id || !p.isReady()) continue
            candidates.add(Target(p, settings.modelFor(p.id, role) ?: defaultModelFor(p.id, role)))
        }
        var last: Exception? = null
        for (t in candidates) {
            try {
                return t.provider.chat(messages, t.model, temperature)
            } catch (e: Exception) {
                Logx.w("provider ${t.provider.id} failed: ${e.message}")
                last = e
                // 401/403 (bad key) — fallback to other providers makes sense.
                // 429 (rate limit) — fallback also makes sense.
            }
        }
        throw last ?: ProviderException("All providers failed")
    }

    private fun defaultModelFor(providerId: String, role: Role): String {
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

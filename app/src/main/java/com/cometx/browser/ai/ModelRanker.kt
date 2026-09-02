package com.cometx.browser.ai

/**
 * ModelRanker (Phase 2 §10/§11): scores every discovered model for agent
 * suitability and selects per-role winners. NO model id is ever hardcoded as a
 * runtime dependency — the best model today may not be the best next month.
 *
 * Scoring (agent suitability, §10):
 *   tool calling +30 · json schema +20 · json object +15 · vision +20
 *   large context +10 · reasoning +10 · fast latency +10 · available = required
 */
object ModelRanker {

    enum class Purpose { AGENT, VISION, REASONING, FAST }

    data class Scored(
        val info: ModelInfo,
        val score: Int,
        val protocol: AgentProtocol
    )

    fun score(info: ModelInfo, purpose: Purpose, latencyMs: Long? = null): Int {
        if (!info.chatCapable || !info.supports(Capability.CHAT)) return Int.MIN_VALUE
        var s = 0
        if (info.supports(Capability.TOOL_CALLING)) s += 30
        if (info.supports(Capability.JSON_SCHEMA)) s += 20
        if (info.supports(Capability.JSON_OBJECT)) s += 15
        if (info.supports(Capability.REASONING)) s += if (purpose == Purpose.REASONING) 25 else 10
        if (info.supports(Capability.VISION)) s += if (purpose == Purpose.VISION) 60 else 20
        when {
            info.contextLength >= 100_000 -> s += 10
            info.contextLength >= 32_000 -> s += 5
        }
        if (info.free) s += 25                       // prefer free capacity (OpenRouter §38)
        latencyMs?.let { if (it < 900) s += 10 else if (it > 4000) s -= 5 }
        if (purpose == Purpose.FAST && info.contextLength in 1..200_000 && !info.supports(Capability.REASONING)) s += 5
        return s
    }

    fun protocolFor(info: ModelInfo, knownGood: AgentProtocol?): AgentProtocol =
        knownGood ?: AgentProtocol.bestFor(info.capabilities)

    /**
     * Rank candidates. [freeOnly] enforces the OpenRouter free-only policy —
     * paid models are filtered out entirely; if that leaves nothing the caller
     * may retry with freeOnly=false rather than dead-ending.
     */
    fun rank(
        models: List<ModelInfo>,
        purpose: Purpose,
        latency: Map<String, Long> = emptyMap(),
        freeOnly: Boolean = false
    ): List<Scored> {
        val pool = models.asSequence()
            .filter { it.chatCapable }
            .filter { !freeOnly || it.free }
            .map { info ->
                val s = score(info, purpose, latency[info.id])
                Scored(info, s, AgentProtocol.bestFor(info.capabilities))
            }
            .filter { it.score > Int.MIN_VALUE / 2 }
            .sortedByDescending { it.score }
            .toList()
        return pool
    }

    /** Best candidate or null when the provider has nothing usable. */
    fun best(
        models: List<ModelInfo>,
        purpose: Purpose,
        latency: Map<String, Long> = emptyMap(),
        freeOnly: Boolean = false
    ): Scored? = rank(models, purpose, latency, freeOnly).firstOrNull()

    /** §11: if only one model is available, use it — never reject over optional features. */
    fun bestOrOnly(models: List<ModelInfo>, purpose: Purpose): Scored? {
        val usable = models.filter { it.chatCapable }
        if (usable.isEmpty()) return null
        if (usable.size == 1) {
            val only = usable[0]
            return Scored(only, score(only, purpose), AgentProtocol.bestFor(only.capabilities))
        }
        return best(models, purpose)
    }
}

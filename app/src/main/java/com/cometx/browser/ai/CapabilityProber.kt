package com.cometx.browser.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * CapabilityProber (Phase 2 §4/§24): safe, minimal, cached probes for
 * capabilities that provider metadata cannot confirm.
 *
 * Probe discipline:
 *  - tiny fixed prompts, maxTokens ≤ 16 — negligible token cost
 *  - only DEFINITIVE results are cached (a 500 must never cache "unsupported")
 *  - results persisted in ModelCatalog (24h TTL)
 */
class CapabilityProber(
    private val provider: OpenAICompatibleProvider,
    private val catalog: ModelCatalog
) {

    /** Probe plain chat; returns latency ms, or null on definitive failure. */
    suspend fun probeChat(modelId: String): Long? {
        try {
            val t0 = System.currentTimeMillis()
            provider.chat(
                listOf(ChatMessage(role = "user", text = "Reply with the single word: pong")),
                modelId, temperature = 0.0, maxTokens = 8
            )
            return System.currentTimeMillis() - t0
        } catch (e: ProviderException) {
            catalog.rememberProbedCapability(provider.id, modelId, Capability.CHAT, false)
            return null
        } catch (_: Exception) {
            return null   // transient: inconclusive, not cached
        }
    }

    /** Probe JSON object mode. */
    suspend fun probeJsonObject(modelId: String): Boolean =
        probeResponseFormat(modelId, ResponseFormat.JsonObject, Capability.JSON_OBJECT)

    /** Probe strict JSON-schema structured outputs. */
    suspend fun probeJsonSchema(modelId: String): Boolean {
        val schema = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject().put("ok", JSONObject().put("type", "boolean")))
            .put("required", JSONArray().put("ok"))
        return probeResponseFormat(modelId, ResponseFormat.JsonSchema("probe", schema), Capability.JSON_SCHEMA)
    }

    /** Probe tool calling with a harmless no-op tool (§24). */
    suspend fun probeToolCalling(modelId: String): Boolean {
        catalog.probedCapability(provider.id, modelId, Capability.TOOL_CALLING)?.let { return it }
        try {
            provider.chatWithTools(
                listOf(ChatMessage(role = "user", text = "Call the tool once with action \"done\" and summary \"probe\".")),
                modelId, temperature = 0.0, maxTokens = 64
            )
            catalog.rememberProbedCapability(provider.id, modelId, Capability.TOOL_CALLING, true)
            return true
        } catch (e: ProviderException) {
            return when (e.kind) {
                ProviderErrorKind.UNSUPPORTED_TOOL_CALLING,
                ProviderErrorKind.UNSUPPORTED_RESPONSE_FORMAT -> {
                    catalog.rememberProbedCapability(provider.id, modelId, Capability.TOOL_CALLING, false)
                    false
                }
                else -> false   // inconclusive (rate limit etc.) — do NOT cache
            }
        } catch (_: Exception) {
            return false
        }
    }

    private suspend fun probeResponseFormat(modelId: String, format: ResponseFormat, cap: Capability): Boolean {
        catalog.probedCapability(provider.id, modelId, cap)?.let { return it }
        try {
            val body = provider.chat(
                listOf(ChatMessage(role = "user", text = "Return {\"ok\": true} as your entire answer.")),
                modelId, temperature = 0.0, maxTokens = 16,
                responseFormat = format
            )
            val supported = body.isNotBlank()
            catalog.rememberProbedCapability(provider.id, modelId, cap, supported)
            return supported
        } catch (e: ProviderException) {
            return when (e.kind) {
                ProviderErrorKind.UNSUPPORTED_RESPONSE_FORMAT -> {
                    catalog.rememberProbedCapability(provider.id, modelId, cap, false)
                    false
                }
                else -> false   // inconclusive — not cached
            }
        } catch (_: Exception) {
            return false
        }
    }

    /**
     * Refine a candidate's capabilities with probes (used by Test & Enable and
     * the compatibility self-test — NEVER per agent step, §24).
     * @param deep also probe json_schema + tools (2 extra calls)
     */
    suspend fun refine(info: ModelInfo, deep: Boolean): ModelInfo {
        val caps = info.capabilities.toMutableSet()
        if (probeChat(info.id) == null) {
            return info.copy(chatCapable = false)
        }
        if (deep) {
            if (!probeJsonObject(info.id)) caps.remove(Capability.JSON_OBJECT)
            if (!probeJsonSchema(info.id)) caps.remove(Capability.JSON_SCHEMA)
            if (!probeToolCalling(info.id)) caps.remove(Capability.TOOL_CALLING)
        } else if (!probeJsonObject(info.id)) {
            caps.remove(Capability.JSON_OBJECT)
        }
        return info.copy(capabilities = caps)
    }
}

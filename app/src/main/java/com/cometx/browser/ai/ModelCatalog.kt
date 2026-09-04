package com.cometx.browser.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * ModelCatalog (Phase 2 §3/§23): discovers each provider's LIVE model list,
 * normalizes it into [ModelInfo] records and caches them locally.
 *
 * Cache refresh triggers (§23): API-key change, explicit refresh, model-not-
 * found at runtime, stale TTL on app start. A stale cache must never break the
 * app — every read has a fallback to a fresh fetch.
 */
class ModelCatalog(private val context: Context) {

    companion object {
        const val TTL_MS = 6L * 60 * 60 * 1000        // 6h
        private const val MAX_MODELS = 400
    }

    private val prefs = context.getSharedPreferences("cometx_catalog", Context.MODE_PRIVATE)
    private val memory = HashMap<String, List<ModelInfo>>()

    // ------------------------------------------------------------- discovery

    /** Fetch + normalize the live catalog. Throws [ProviderException] on failure. */
    suspend fun fetch(p: OpenAICompatibleProvider): List<ModelInfo> {
        val raw = p.fetchModelCatalog()
        val models = p.normalizeCatalog(raw)
        if (models.isEmpty()) throw ProviderException("${p.displayName}: model catalog is empty", -1)
        updateCache(p.id, models, p.keyFingerprint())
        return models
    }

    /**
     * Cached catalog read. [keyFingerprint] changes when the user pastes a new
     * key → automatic refresh (§23). Null when no usable cache exists.
     */
    suspend fun models(p: OpenAICompatibleProvider, forceRefresh: Boolean = false): List<ModelInfo>? {
        memory[p.id]?.takeIf { it.isNotEmpty() && !forceRefresh }?.let { return it }
        val fp = p.keyFingerprint()
        if (!forceRefresh) {
            readCache(p.id)?.let { cached ->
                if (cached.storedFp == fp && System.currentTimeMillis() - cached.ts < TTL_MS) {
                    memory[p.id] = cached.models
                    return cached.models
                }
            }
        }
        return try {
            fetch(p)
        } catch (_: Exception) {
            // stale cache is better than nothing for ranking, but never authoritative
            readCache(p.id)?.models
        }
    }

    fun invalidate(providerId: String) {
        memory.remove(providerId)
        prefs.edit().remove("catalog_$providerId").remove("catalog_ts_$providerId").remove("catalog_fp_$providerId").apply()
    }

    /** Non-suspender read of cached ids (UI dropdowns). Never hits the network. */
    fun cachedModels(providerId: String): List<String> =
        memory[providerId]?.map { it.id }
            ?: readCache(providerId)?.models?.map { it.id }
            ?: emptyList()

    // ----------------------------------------------------------------- cache

    private data class Cached(val models: List<ModelInfo>, val ts: Long, val storedFp: String?)

    private fun readCache(providerId: String): Cached? {
        val raw = prefs.getString("catalog_$providerId", null) ?: return null
        val ts = prefs.getLong("catalog_ts_$providerId", 0L)
        val fp = prefs.getString("catalog_fp_$providerId", null)
        return try {
            val arr = JSONArray(raw)
            val models = ArrayList<ModelInfo>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("id").isNotBlank()) models.add(ModelInfo.fromJson(o))
            }
            if (models.isEmpty()) null else Cached(models, ts, fp)
        } catch (_: Exception) {
            null
        }
    }

    private fun updateCache(providerId: String, models: List<ModelInfo>, fp: String?) {
        memory[providerId] = models
        val arr = JSONArray()
        for (m in models.take(MAX_MODELS)) arr.put(m.toJson())
        prefs.edit()
            .putString("catalog_$providerId", arr.toString())
            .putLong("catalog_ts_$providerId", System.currentTimeMillis())
            .putString("catalog_fp_$providerId", fp ?: "")
            .apply()
    }

    // -------------------------------------------------------- known protocols

    /** Last protocol a model was seen accepting, so we skip known-failing rungs. */
    fun knownGoodProtocol(providerId: String, modelId: String): AgentProtocol? {
        val v = prefs.getString("proto_${providerId}_$modelId", null) ?: return null
        return AgentProtocol.fromNameOrNull(v)
    }

    fun rememberGoodProtocol(providerId: String, modelId: String, protocol: AgentProtocol) {
        prefs.edit().putString("proto_${providerId}_$modelId", protocol.name).apply()
    }

    /** Capability probe cache (§24): only definitive results are persisted. */
    fun probedCapability(providerId: String, modelId: String, cap: Capability): Boolean? {
        val v = prefs.getString("probe_${providerId}_${modelId}_$cap", null) ?: return null
        val ts = prefs.getLong("probe_ts_${providerId}_${modelId}_$cap", 0L)
        if (System.currentTimeMillis() - ts > 24L * 60 * 60 * 1000) return null
        return when (v) {
            "1" -> true
            "0" -> false
            else -> null
        }
    }

    fun rememberProbedCapability(providerId: String, modelId: String, cap: Capability, supported: Boolean) {
        prefs.edit()
            .putString("probe_${providerId}_${modelId}_$cap", if (supported) "1" else "0")
            .putLong("probe_ts_${providerId}_${modelId}_$cap", System.currentTimeMillis())
            .apply()
    }
}

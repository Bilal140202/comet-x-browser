package com.cometx.browser.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * Universal model capability vocabulary (Phase 2 §4). A capability is claimed
 * from provider METADATA, confirmed by PROBES, or deduced from RUNTIME errors —
 * never assumed.
 */
enum class Capability {
    CHAT,
    TOOL_CALLING,
    JSON_OBJECT,
    JSON_SCHEMA,
    VISION,
    REASONING,
    STREAMING;

    companion object {
        /** Merge two capability sets — only CHAT can be lost (never downgraded away). */
        fun merge(a: Set<Capability>, b: Set<Capability>): Set<Capability> = a + b
    }
}

/**
 * One normalized model record from a provider catalog (Phase 2 §3).
 *
 * "Model exists" ≠ "model works": [chatCapable] excludes audio/guard/embedding
 * endpoints that appear in provider catalogs but cannot drive an agent, and
 * [capabilities] reflects only what we have evidence for.
 */
data class ModelInfo(
    val id: String,
    val provider: String,
    val displayName: String = id,
    val contextLength: Long = 0L,
    val ownedBy: String? = null,
    val capabilities: Set<Capability> = setOf(Capability.CHAT),
    val free: Boolean = false,
    val chatCapable: Boolean = true,
    val note: String? = null,
    val lastVerified: Long = System.currentTimeMillis()
) {
    fun supports(c: Capability): Boolean = capabilities.contains(c)

    fun toShortLabel(): String {
        val caps = buildList {
            if (supports(Capability.VISION)) add("vision")
            if (supports(Capability.TOOL_CALLING)) add("tools")
            if (supports(Capability.JSON_SCHEMA)) add("json-schema")
            else if (supports(Capability.JSON_OBJECT)) add("json")
            if (supports(Capability.REASONING)) add("reasoning")
        }
        return buildString {
            append(displayName)
            if (free) append(" (free)")
            if (caps.isNotEmpty()) append(" · ").append(caps.joinToString(", "))
        }
    }

    // ------------------------------------------------------------ persistence

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("provider", provider)
        .put("displayName", displayName)
        .put("contextLength", contextLength)
        .put("ownedBy", ownedBy ?: "")
        .put("capabilities", JSONArray().apply { for (c in capabilities) put(c.name) })
        .put("free", free)
        .put("chatCapable", chatCapable)
        .put("note", note ?: "")
        .put("lastVerified", lastVerified)

    companion object {
        fun fromJson(o: JSONObject): ModelInfo = ModelInfo(
            id = o.optString("id"),
            provider = o.optString("provider"),
            displayName = o.optString("displayName", o.optString("id")),
            contextLength = o.optLong("contextLength", 0L),
            ownedBy = o.optString("ownedBy").takeIf { it.isNotBlank() },
            capabilities = run {
                val arr = o.optJSONArray("capabilities")
                val out = mutableSetOf(Capability.CHAT)
                if (arr != null) for (i in 0 until arr.length()) {
                    runCatching { Capability.valueOf(arr.optString(i)) }.getOrNull()?.let { out.add(it) }
                }
                out
            },
            free = o.optBoolean("free", false),
            chatCapable = o.optBoolean("chatCapable", true),
            note = o.optString("note").takeIf { it.isNotBlank() },
            lastVerified = o.optLong("lastVerified", 0L)
        )
    }
}

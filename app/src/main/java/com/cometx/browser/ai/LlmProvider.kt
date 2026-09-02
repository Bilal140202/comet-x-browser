package com.cometx.browser.ai

import com.cometx.browser.util.Json
import org.json.JSONArray
import org.json.JSONObject

/**
 * Model provider abstraction. All four supported providers (Groq, OpenRouter,
 * Hugging Face router, generic OpenAI-compatible) speak the OpenAI wire format,
 * so a single transport serves them; subclasses customize endpoint/auth/defaults.
 *
 * Phase 2: response format is REQUESTED, not assumed — no provider may force
 * json mode onto models that cannot honor it (that was the Phase 1 bug).
 */
interface LlmProvider {
    val id: String
    val displayName: String
    val defaultBaseUrl: String

    /** True if the provider is configured (has an API key) and enabled. */
    fun isReady(): Boolean

    /**
     * Chat completion. Returns assistant message text.
     * @throws ProviderException on transport or API error (kind classified)
     */
    suspend fun chat(
        messages: List<ChatMessage>,
        model: String,
        temperature: Double = 0.2,
        maxTokens: Int = 1024
    ): String
}

/** Requested response format for one completion (Phase 2 §5/§6). */
sealed class ResponseFormat {
    /** Plain completion — the only format every chat model can serve. */
    object None : ResponseFormat()

    /** {"type":"json_object"} — broad compatibility, no schema guarantee. */
    object JsonObject : ResponseFormat()

    /** {"type":"json_schema","json_schema":{…}} — strict structured outputs. */
    data class JsonSchema(val name: String, val schema: JSONObject) : ResponseFormat()

    fun toWireJson(): JSONObject? = when (this) {
        None -> null
        JsonObject -> Json.obj("type", "json_object")
        is JsonSchema -> JSONObject()
            .put("type", "json_schema")
            .put("json_schema", JSONObject()
                .put("name", name)
                .put("strict", true)
                .put("schema", schema))
    }
}

data class ChatMessage(
    val role: String,            // "system" | "user" | "assistant"
    val text: String? = null,
    val imageBase64Jpeg: String? = null   // when set, message becomes multimodal
) {
    val hasImage: Boolean get() = imageBase64Jpeg != null

    fun toJson(): JSONObject {
        if (imageBase64Jpeg == null) {
            return Json.obj("role", role, "content", text ?: "")
        }
        val parts = org.json.JSONArray()
        parts.put(Json.obj("type", "text", "text", text ?: "Describe what you see."))
        parts.put(Json.obj(
            "type", "image_url",
            "image_url", Json.obj("url", "data:image/jpeg;base64,$imageBase64Jpeg")
        ))
        return Json.obj("role", role, "content", parts)
    }
}

/** Result of a tool-forced completion (TOOL_CALLING protocol). */
data class ToolCallResult(val rawBody: String)

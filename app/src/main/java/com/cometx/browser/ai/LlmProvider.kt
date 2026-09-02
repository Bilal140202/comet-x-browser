package com.cometx.browser.ai

import com.cometx.browser.util.Json
import org.json.JSONObject

/**
 * Model provider abstraction. All four supported providers (Groq, OpenRouter,
 * Hugging Face router, generic OpenAI-compatible) speak the OpenAI wire format,
 * so a single transport serves them; subclasses customize endpoint/auth/defaults.
 */
interface LlmProvider {
    val id: String
    val displayName: String
    val defaultBaseUrl: String

    /** True if the provider is configured (has an API key) and enabled. */
    fun isReady(): Boolean

    /**
     * Chat completion. Returns assistant message text.
     * @throws ProviderException on transport or API error
     */
    suspend fun chat(
        messages: List<ChatMessage>,
        model: String,
        temperature: Double = 0.2,
        maxTokens: Int = 1024
    ): String
}

data class ChatMessage(
    val role: String,            // "system" | "user" | "assistant"
    val text: String? = null,
    val imageBase64Jpeg: String? = null   // when set, message becomes multimodal
) {
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

class ProviderException(message: String, val httpCode: Int = -1) : Exception(message)

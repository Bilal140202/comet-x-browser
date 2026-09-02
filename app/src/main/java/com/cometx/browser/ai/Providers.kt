package com.cometx.browser.ai

import com.cometx.browser.util.Http
import com.cometx.browser.util.Json
import com.cometx.browser.util.Logx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * One OpenAI-compatible transport, four provider configurations.
 * Keys come from SettingsRepository at call time — never stored here.
 */
open class OpenAICompatibleProvider(
    override val id: String,
    override val displayName: String,
    override val defaultBaseUrl: String,
    private val keyProvider: () -> String?,
    private val extraHeaders: Map<String, String> = emptyMap(),
    private val useJsonMode: Boolean = false
) : LlmProvider {

    private var baseUrlOverride: String? = null

    fun setBaseUrl(url: String?) { baseUrlOverride = url?.takeIf { it.isNotBlank() } }
    fun effectiveBaseUrl(): String = baseUrlOverride ?: defaultBaseUrl

    override fun isReady(): Boolean = !keyProvider().isNullOrBlank()

    override suspend fun chat(
        messages: List<ChatMessage>,
        model: String,
        temperature: Double,
        maxTokens: Int
    ): String = withContext(Dispatchers.IO) {
        val key = keyProvider() ?: throw ProviderException("$displayName: no API key configured", 401)
        val payload = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply { for (m in messages) put(m.toJson()) })
            put("temperature", temperature)
            put("max_tokens", maxTokens)
            put("stream", false)
            if (useJsonMode) put("response_format", Json.obj("type", "json_object"))
        }
        val url = effectiveBaseUrl().trimEnd('/') + "/chat/completions"
        val headers = buildMap {
            put("Authorization", "Bearer $key")
            putAll(extraHeaders)
        }
        val resp = Http.postJson(url, payload.toString(), headers)
        if (!resp.ok) {
            throw ProviderException(
                "$displayName HTTP ${resp.code}: ${summarizeError(resp.body)}",
                resp.code
            )
        }
        parseContent(resp.body) ?: throw ProviderException("$displayName: unexpected response shape")
    }

    /** Extracts choices[0].message.content, tolerating provider quirks. */
    fun parseContent(body: String): String? {
        val root = Json.parseOrNull(body) ?: return null
        val choices = root.optJSONArray("choices") ?: return null
        if (choices.length() == 0) return null
        val msg = choices.optJSONObject(0)?.optJSONObject("message") ?: return null
        val content = msg.opt("content")
        return when (content) {
            is String -> content
            is JSONArray -> {           // some routers return content parts
                buildString {
                    for (i in 0 until content.length()) {
                        val part = content.optJSONObject(i)
                        val t = part?.optString("text", "")
                        if (!t.isNullOrEmpty()) append(t)
                    }
                }.ifEmpty { null }
            }
            else -> null
        }
    }

    private fun summarizeError(body: String): String = try {
        val o = Json.parseOrNull(body)
        val err = o?.optJSONObject("error")
        err?.optString("message")?.take(200) ?: body.take(200)
    } catch (_: Exception) { body.take(200) }
}

class GroqProvider(keyProvider: () -> String?) : OpenAICompatibleProvider(
    id = "groq",
    displayName = "Groq",
    defaultBaseUrl = "https://api.groq.com/openai/v1",
    keyProvider = keyProvider,
    useJsonMode = true
)

class OpenRouterProvider(keyProvider: () -> String?) : OpenAICompatibleProvider(
    id = "openrouter",
    displayName = "OpenRouter",
    defaultBaseUrl = "https://openrouter.ai/api/v1",
    keyProvider = keyProvider,
    extraHeaders = mapOf(
        "HTTP-Referer" to "https://github.com/Bilal140202/comet-x-browser",
        "X-Title" to "Comet-X"
    )
)

class HuggingFaceProvider(keyProvider: () -> String?) : OpenAICompatibleProvider(
    id = "huggingface",
    displayName = "Hugging Face",
    defaultBaseUrl = "https://router.huggingface.co/v1",
    keyProvider = keyProvider
)

class CustomOpenAIProvider(keyProvider: () -> String?) : OpenAICompatibleProvider(
    id = "custom",
    displayName = "Custom (OpenAI-compatible)",
    defaultBaseUrl = "https://api.openai.com/v1",
    keyProvider = keyProvider
)

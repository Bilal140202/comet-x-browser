package com.cometx.browser.ai

import com.cometx.browser.util.Http
import com.cometx.browser.util.Json
import com.cometx.browser.util.Logx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Normalizes a self-run endpoint: trims, ensures scheme, auto-appends /v1 when
 * clearly a bare local server (Ollama/LM Studio/vLLM style). Fixes "URL seems off".
 */
object UrlNormalizer {
    private val LOCAL_HOST = Regex("""^(localhost|127\.0\.0\.1|0\.0\.0\.0|\[::1\]|10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)""")
    private val VERSIONED = Regex("""/v\d+(/|$)""", RegexOption.IGNORE_CASE)

    fun normalize(raw: String): String {
        var url = (raw ?: "").trim().trimEnd('/')
        if (url.isEmpty()) return ""
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            val isLocal = LOCAL_HOST.containsMatchIn(url)
            url = (if (isLocal) "http://" else "https://") + url
        }
        if (!VERSIONED.containsMatchIn(url)) {
            val host = url.removePrefix("http://").removePrefix("https://").substringBefore('/')
            if (Regex("""(:11434|:1234|:8000|:1337|:4891)$""").containsMatchIn(host) ||
                host.startsWith("localhost") || host.startsWith("127.0.0.1")) url += "/v1"
        }
        return url
    }
}

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
    private val useJsonMode: Boolean = false,
    private val readyCheck: (() -> Boolean)? = null
) : LlmProvider {

    private var baseUrlOverride: String? = null

    fun setBaseUrl(url: String?) { baseUrlOverride = url?.takeIf { it.isNotBlank() } }
    fun effectiveBaseUrl(): String = baseUrlOverride ?: defaultBaseUrl

    override fun isReady(): Boolean = readyCheck?.invoke() ?: !keyProvider().isNullOrBlank()

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

    /** Live model catalog from GET {base}/models. */
    suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        val key = keyProvider()
        val url = effectiveBaseUrl().trimEnd('/') + "/models"
        val headers = buildMap { if (!key.isNullOrBlank()) put("Authorization", "Bearer $key"); putAll(extraHeaders) }
        val resp = Http.get(url, headers)
        if (!resp.ok) throw ProviderException("$displayName HTTP ${resp.code}: ${summarizeError(resp.body)}", resp.code)
        parseModelIds(resp.body)
    }

    fun parseModelIds(body: String): List<String> {
        val root = Json.parseOrNull(body) ?: return emptyList()
        val arr = root.optJSONArray("data") ?: root.optJSONArray("models") ?: return emptyList()
        val ids = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val item = arr.opt(i)
            val id = when (item) {
                is String -> item
                is JSONObject -> item.optString("id", "").ifBlank { item.optString("name", "") }
                else -> ""
            }
            if (id.isNotBlank()) ids.add(id)
        }
        return ids.distinct().sorted().take(300)
    }

    /** Quick connectivity test: tiny chat completion. Returns latency ms. */
    suspend fun ping(model: String): Long = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        chat(listOf(ChatMessage(role = "user", text = "Reply with the single word: pong")), model, temperature = 0.0, maxTokens = 8)
        System.currentTimeMillis() - t0
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

class CustomOpenAIProvider(
    keyProvider: () -> String?,
    readyCheck: (() -> Boolean)? = null
) : OpenAICompatibleProvider(
    id = "custom",
    displayName = "Self-run (OpenAI-compatible)",
    defaultBaseUrl = "https://api.openai.com/v1",
    keyProvider = keyProvider,
    readyCheck = readyCheck
)

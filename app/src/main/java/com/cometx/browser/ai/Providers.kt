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
 *
 * Phase 2 contract (§14 AIProvider):
 *   listModels / normalizeCatalog → ModelInfo discovery
 *   chat(messages, model, responseFormat) → format requested per negotiated protocol
 *   chatWithTools → single decision tool for TOOL_CALLING protocol
 * Every failure is thrown as [ProviderException] with a normalized [ProviderErrorKind].
 */
open class OpenAICompatibleProvider(
    override val id: String,
    override val displayName: String,
    override val defaultBaseUrl: String,
    private val keyProvider: () -> String?,
    private val extraHeaders: Map<String, String> = emptyMap(),
    private val readyCheck: (() -> Boolean)? = null,
    private val transport: HttpTransport = HttpTransport.REAL
) : LlmProvider {

    private var baseUrlOverride: String? = null

    fun setBaseUrl(url: String?) { baseUrlOverride = url?.takeIf { it.isNotBlank() } }
    fun effectiveBaseUrl(): String = baseUrlOverride ?: defaultBaseUrl

    override fun isReady(): Boolean = readyCheck?.invoke() ?: !keyProvider().isNullOrBlank()

    /** Stable non-secret fingerprint of the current key for cache invalidation. */
    fun keyFingerprint(): String {
        val key = keyProvider() ?: return "none"
        return Integer.toHexString(key.hashCode()).toString() + ":" + key.length
    }

    protected fun headers(): Map<String, String> = buildMap {
        val key = keyProvider()
        if (!key.isNullOrBlank()) put("Authorization", "Bearer $key")
        putAll(extraHeaders)
    }

    // ------------------------------------------------------------------ chat

    /**
     * Chat completion with an EXPLICITLY negotiated response format.
     * Never sends response_format unless the caller asked for it (Phase 2 §6).
     */
    override suspend fun chat(
        messages: List<ChatMessage>,
        model: String,
        temperature: Double,
        maxTokens: Int
    ): String = chat(messages, model, temperature, maxTokens, ResponseFormat.None)

    open suspend fun chat(
        messages: List<ChatMessage>,
        model: String,
        temperature: Double = 0.2,
        maxTokens: Int = 1024,
        responseFormat: ResponseFormat = ResponseFormat.None
    ): String = withContext(Dispatchers.IO) {
        val key = keyProvider() ?: throw ProviderException("$displayName: no API key configured", 401)
        val payload = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply { for (m in messages) put(m.toJson()) })
            put("temperature", temperature)
            put("max_tokens", maxTokens)
            put("stream", false)
            responseFormat.toWireJson()?.let { put("response_format", it) }
        }
        executeChat(payload)
    }

    /**
     * Single-decision tool call (TOOL_CALLING protocol): forces the model to
     * invoke [TOOL_NAME] whose arguments ARE the decision fields.
     */
    open suspend fun chatWithTools(
        messages: List<ChatMessage>,
        model: String,
        temperature: Double = 0.2,
        maxTokens: Int = 1024
    ): ToolCallResult = withContext(Dispatchers.IO) {
        val key = keyProvider() ?: throw ProviderException("$displayName: no API key configured", 401)
        val payload = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply { for (m in messages) put(m.toJson()) })
            put("temperature", temperature)
            put("max_tokens", maxTokens)
            put("stream", false)
            put("tools", JSONArray().put(TOOL_SPEC))
            put("tool_choice", JSONObject()
                .put("type", "function")
                .put("function", JSONObject().put("name", TOOL_NAME)))
        }
        ToolCallResult(executeChat(payload))
    }

    private suspend fun executeChat(payload: JSONObject): String {
        val url = effectiveBaseUrl().trimEnd('/') + "/chat/completions"
        val resp = transport.postJson(url, payload.toString(), headers(), 90_000)
        if (!resp.ok) throw ProviderException(
            "$displayName HTTP ${resp.code}: ${summarizeError(resp.body)}",
            resp.code
        )
        return resp.body
    }

    // -------------------------------------------------------------- discovery

    /** Raw /models body (Phase 2 §3 FETCH MODELS). */
    open suspend fun fetchModelCatalog(): String = withContext(Dispatchers.IO) {
        val url = effectiveBaseUrl().trimEnd('/') + "/models"
        val resp = transport.get(url, headers(), 30_000)
        if (!resp.ok) throw ProviderException(
            "$displayName HTTP ${resp.code}: ${summarizeError(resp.body)}",
            resp.code
        )
        resp.body
    }

    /** Legacy convenience: live model id list. */
    suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        parseModelIds(fetchModelCatalog())
    }

    /** Normalize THIS provider's catalog JSON into ModelInfo records. */
    open fun normalizeCatalog(raw: String): List<ModelInfo> {
        val root = Json.parseOrNull(raw) ?: return emptyList()
        val arr = root.optJSONArray("data") ?: root.optJSONArray("models") ?: return emptyList()
        val out = ArrayList<ModelInfo>(arr.length())
        val seen = HashSet<String>()
        for (i in 0 until arr.length()) {
            val item = arr.opt(i) ?: continue
            val info: ModelInfo? = when (item) {
                is JSONObject -> normalizeOne(item)
                is String -> normalizeIdOnly(item)
                else -> null
            }
            if (info != null && info.id.isNotBlank() && seen.add(info.id)) out.add(info)
            if (out.size >= 400) break
        }
        return out
    }

    /** Minimal record for providers without per-model metadata. */
    protected open fun normalizeOne(o: JSONObject): ModelInfo {
        val mid = o.optString("id", "").ifBlank { o.optString("name", "") }
        val ctx = o.optLong("context_length", 0L).let { if (it == 0L) o.optLong("context_window", 0L) else it }
        val caps = mutableSetOf(Capability.CHAT, Capability.JSON_OBJECT, Capability.STREAMING)
        val owned = o.optString("owned_by", "").ifBlank { o.optString("ownedBy", "") }
        return ModelInfo(
            id = mid,
            provider = id,
            displayName = mid,
            contextLength = ctx,
            ownedBy = owned.takeIf { it.isNotBlank() },
            capabilities = caps,
            free = false,
            chatCapable = isChatCapableId(mid),
            lastVerified = System.currentTimeMillis()
        )
    }

    private fun normalizeIdOnly(sid: String): ModelInfo = ModelInfo(
        id = sid, provider = id, displayName = sid,
        capabilities = setOf(Capability.CHAT, Capability.JSON_OBJECT, Capability.STREAMING),
        chatCapable = isChatCapableId(sid),
        lastVerified = System.currentTimeMillis()
    )

    // ------------------------------------------------------------------ ping

    /** Quick connectivity test: tiny chat completion. Returns latency ms. */
    suspend fun ping(model: String): Long = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        chat(listOf(ChatMessage(role = "user", text = "Reply with the single word: pong")),
            model, temperature = 0.0, maxTokens = 8)
        System.currentTimeMillis() - t0
    }

    // --------------------------------------------------------------- parsing

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

    fun parseModelIds(body: String): List<String> {
        val root = Json.parseOrNull(body) ?: return emptyList()
        val arr = root.optJSONArray("data") ?: root.optJSONArray("models") ?: return emptyList()
        val ids = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val item = arr.opt(i)
            val mid = when (item) {
                is String -> item
                is JSONObject -> item.optString("id", "").ifBlank { item.optString("name", "") }
                else -> ""
            }
            if (mid.isNotBlank()) ids.add(mid)
        }
        return ids.distinct().sorted().take(300)
    }

    private fun summarizeError(body: String): String = try {
        val o = Json.parseOrNull(body)
        val err = o?.optJSONObject("error")
        err?.optString("message")?.take(300)
            ?: (o?.optString("message")?.takeIf { it.isNotBlank() })
            ?: body.take(300)
    } catch (_: Exception) { body.take(300) }

    companion object {
        /** Name of the single decision tool used by the TOOL_CALLING protocol. */
        const val TOOL_NAME = "browser_action"

        /**
         * Loose schema: models vary wildly in strictness, and ActionValidator
         * re-checks everything downstream — the tool just shapes the output.
         */
        val TOOL_SPEC: JSONObject = JSONObject().put(
            "type", "function"
        ).put("function", JSONObject()
            .put("name", TOOL_NAME)
            .put("description", "Emit exactly one browser agent action.")
            .put("parameters", JSONObject()
                .put("type", "object")
                .put("properties", JSONObject()
                    .put("action", JSONObject().put("type", "string")
                        .put("description", "one of: navigate, back, forward, reload, click, click_at, type, press_key, scroll, select, wait, find_text, find_element, extract, screenshot, request_vision, open_tab, close_tab, switch_tab, download, copy, paste, zoom, remember, done, fail, ask_user"))
                    .put("ref", JSONObject().put("type", "string").put("description", "element ref from the observation, e.g. e7"))
                    .put("text", JSONObject().put("type", "string"))
                    .put("url", JSONObject().put("type", "string"))
                    .put("option", JSONObject().put("type", "string"))
                    .put("x", JSONObject().put("type", "integer"))
                    .put("y", JSONObject().put("type", "integer"))
                    .put("key", JSONObject().put("type", "string"))
                    .put("direction", JSONObject().put("type", "string"))
                    .put("amount", JSONObject().put("type", "integer"))
                    .put("ms", JSONObject().put("type", "integer"))
                    .put("what", JSONObject().put("type", "string"))
                    .put("description", JSONObject().put("type", "string"))
                    .put("index", JSONObject().put("type", "integer"))
                    .put("level", JSONObject().put("type", "string"))
                    .put("key_name", JSONObject().put("type", "string"))
                    .put("fact", JSONObject().put("type", "string"))
                    .put("question", JSONObject().put("type", "string"))
                    .put("summary", JSONObject().put("type", "string"))
                    .put("reason", JSONObject().put("type", "string"))
                    .put("note", JSONObject().put("type", "string")))
                .put("required", JSONArray().put("action"))))

        /** Ids that can NEVER drive a browser agent (audio/safety/embedding). */
        fun isChatCapableId(modelId: String): Boolean {
            val id = modelId.lowercase()
            val blocked = listOf(
                "whisper", "tts", "guard", "embed", "rerank", "moderation",
                "distil-whisper", "playai", "speech", "content-safety", "moderator"
            )
            return blocked.none { id.contains(it) }
        }
    }
}

// ---------------------------------------------------------------- providers

open class GroqProvider(
    keyProvider: () -> String?,
    transport: HttpTransport = HttpTransport.REAL
) : OpenAICompatibleProvider(
    id = "groq",
    displayName = "Groq",
    defaultBaseUrl = "https://api.groq.com/openai/v1",
    keyProvider = keyProvider,
    readyCheck = null,
    transport = transport
) {
    /**
     * Groq flavor: richer metadata (context_window) + vision-family hints.
     * NOTE: no default response_format anywhere — JSON modes are negotiated.
     */
    override fun normalizeOne(o: JSONObject): ModelInfo {
        val mid = o.optString("id", "")
        val ctx = o.optLong("context_window", 0L).let { if (it == 0L) o.optLong("context_length", 0L) else it }
        val caps = mutableSetOf(Capability.CHAT, Capability.JSON_OBJECT, Capability.STREAMING)
        val lower = mid.lowercase()
        // Groq vision families (metadata hint; runtime VISION_UNSUPPORTED still guards)
        if (Regex("""llama-4|llama-4-(scout|maverick)|qwen.*vl|gemma-3""").containsMatchIn(lower)) {
            caps.add(Capability.VISION)
        }
        if (lower.contains("deepseek-r1") || lower.contains("reasoning") || lower.contains("-r1-") || lower.contains("qwen3")) {
            caps.add(Capability.REASONING)
        }
        return ModelInfo(
            id = mid,
            provider = id,
            displayName = mid,
            contextLength = ctx,
            ownedBy = o.optString("owned_by").takeIf { it.isNotBlank() },
            capabilities = caps,
            free = false,
            chatCapable = OpenAICompatibleProvider.isChatCapableId(mid),
            lastVerified = System.currentTimeMillis()
        )
    }
}

class OpenRouterProvider(
    keyProvider: () -> String?,
    transport: HttpTransport = HttpTransport.REAL
) : OpenAICompatibleProvider(
    id = "openrouter",
    displayName = "OpenRouter",
    defaultBaseUrl = "https://openrouter.ai/api/v1",
    keyProvider = keyProvider,
    extraHeaders = mapOf(
        "HTTP-Referer" to "https://github.com/Bilal140202/comet-x-browser",
        "X-Title" to "Comet-X"
    ),
    readyCheck = null,
    transport = transport
) {
    /**
     * OpenRouter publishes everything we need: pricing (free detection),
     * input modalities (vision) and supported_parameters (json/tool/reasoning).
     * This is the metadata-first gold standard the other providers approximate.
     */
    override fun normalizeOne(o: JSONObject): ModelInfo {
        val mid = o.optString("id", "")
        val lower = mid.lowercase()
        val pricing = o.optJSONObject("pricing")
        val promptPrice = pricing?.optString("prompt", "1") ?: "1"
        val completionPrice = pricing?.optString("completion", "1") ?: "1"
        val isFree = (promptPrice == "0" && completionPrice == "0") || lower.endsWith(":free")

        val caps = mutableSetOf(Capability.CHAT, Capability.STREAMING)
        val params = o.optJSONArray("supported_parameters")
        if (params != null) {
            val set = mutableSetOf<String>()
            for (i in 0 until params.length()) set.add(params.optString(i))
            if ("tools" in set || "tool_choice" in set) caps.add(Capability.TOOL_CALLING)
            if ("response_format" in set) caps.add(Capability.JSON_OBJECT)
            if ("structured_outputs" in set) caps.add(Capability.JSON_SCHEMA)
            if ("reasoning" in set || "include_reasoning" in set) caps.add(Capability.REASONING)
        }
        val arch = o.optJSONObject("architecture")
        val inMods = arch?.optJSONArray("input_modalities")
        if (inMods != null) {
            for (i in 0 until inMods.length()) if (inMods.optString(i) == "image") caps.add(Capability.VISION)
        }
        val outMods = arch?.optJSONArray("output_modalities")
        val textOut = outMods == null || run {
            var has = false
            for (i in 0 until outMods.length()) if (outMods.optString(i) == "text") has = true
            has
        }
        return ModelInfo(
            id = mid,
            provider = id,
            displayName = o.optString("name", mid),
            contextLength = o.optLong("context_length", 0L),
            ownedBy = mid.substringBefore('/').takeIf { it != mid },
            capabilities = caps,
            free = isFree,
            // non-text outputs (image gen) and known non-agent endpoints are excluded
            chatCapable = textOut && OpenAICompatibleProvider.isChatCapableId(mid),
            lastVerified = System.currentTimeMillis()
        )
    }
}

class HuggingFaceProvider(
    keyProvider: () -> String?,
    transport: HttpTransport = HttpTransport.REAL
) : OpenAICompatibleProvider(
    id = "huggingface",
    displayName = "Hugging Face",
    defaultBaseUrl = "https://router.huggingface.co/v1",
    keyProvider = keyProvider,
    readyCheck = null,
    transport = transport
)

class CustomOpenAIProvider(
    keyProvider: () -> String?,
    readyCheck: (() -> Boolean)? = null,
    transport: HttpTransport = HttpTransport.REAL
) : OpenAICompatibleProvider(
    id = "custom",
    displayName = "Self-run (OpenAI-compatible)",
    defaultBaseUrl = "https://api.openai.com/v1",
    keyProvider = keyProvider,
    readyCheck = readyCheck,
    transport = transport
)

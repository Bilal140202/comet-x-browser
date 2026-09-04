package com.cometx.browser

import com.cometx.browser.ai.Capability
import com.cometx.browser.ai.GroqProvider
import com.cometx.browser.ai.ModelRanker
import com.cometx.browser.ai.OpenRouterProvider
import com.cometx.browser.ai.ProviderErrorClassifier
import com.cometx.browser.ai.ProviderErrorKind
import com.cometx.browser.ai.ProviderException
import com.cometx.browser.util.Http
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2 — catalog normalization + capability metadata + error classification.
 * Fixtures mirror the providers' real response shapes (live-verified against
 * OpenRouter's public /models endpoint).
 */
class CapabilityNegotiationTest {

    private val groq = GroqProvider(keyProvider = { "k" })
    private val openrouter = OpenRouterProvider(keyProvider = { "k" })

    // ------------------------------------------------- Groq normalization

    @Test fun `groq excludes audio and guard endpoints from chat capability`() {
        val body = JSONObject().put("data", JSONArray()
            .put(JSONObject().put("id", "llama-3.3-70b-versatile").put("context_window", 131072))
            .put(JSONObject().put("id", "whisper-large-v3-turbo"))
            .put(JSONObject().put("id", "playai-tts"))
            .put(JSONObject().put("id", "llama-guard-4-12b"))
            .put(JSONObject().put("id", "meta-llama/llama-4-scout-17b-16e-instruct").put("context_window", 131072))
        ).toString()
        val models = groq.normalizeCatalog(body)
        val byId = models.associateBy { it.id }
        assertTrue(byId["llama-3.3-70b-versatile"]!!.chatCapable)
        assertFalse(byId["whisper-large-v3-turbo"]!!.chatCapable)
        assertFalse(byId["playai-tts"]!!.chatCapable)
        assertFalse(byId["llama-guard-4-12b"]!!.chatCapable)
        assertTrue(byId["meta-llama/llama-4-scout-17b-16e-instruct"]!!.supports(Capability.VISION))
    }

    @Test fun `groq context window is captured`() {
        val models = groq.normalizeCatalog(
            """{"data":[{"id":"llama-3.1-8b-instant","context_window":131072}]}"""
        )
        assertEquals(131072L, models[0].contextLength)
    }

    // ------------------------------------------------- OpenRouter normalization

    private fun orModel(
        id: String,
        free: Boolean,
        params: List<String>,
        inputMods: List<String> = listOf("text"),
        ctx: Long = 8192
    ): String {
        val paramArr = JSONArray()
        for (p in params) paramArr.put(p)
        val modArr = JSONArray()
        for (m in inputMods) modArr.put(m)
        return JSONObject()
            .put("id", id)
            .put("name", "Pretty Name")
            .put("context_length", ctx)
            .put("pricing", JSONObject().put("prompt", if (free) "0" else "0.000001").put("completion", if (free) "0" else "0.000002"))
            .put("architecture", JSONObject().put("input_modalities", modArr).put("output_modalities", JSONArray().put("text")))
            .put("supported_parameters", paramArr)
            .toString()
    }

    @Test fun `openrouter metadata maps to capabilities`() {
        val body = JSONObject().put("data", JSONArray()
            .put(JSONObject(orModel("free/with-tools:free", true, listOf("tools", "tool_choice", "response_format"))))
            .put(JSONObject(orModel("paid/strict", false, listOf("structured_outputs", "reasoning"))))
            .put(JSONObject(orModel("vision/model", true, listOf("response_format"), inputMods = listOf("text", "image"))))
            .put(JSONObject(orModel("text/plain-model", true, listOf("temperature"))))  // no json, no tools
        ).toString()
        val models = openrouter.normalizeCatalog(body)
        val byId = models.associateBy { it.id }

        val withTools = byId["free/with-tools:free"]!!
        assertTrue(withTools.free)
        assertTrue(withTools.supports(Capability.TOOL_CALLING))
        assertTrue(withTools.supports(Capability.JSON_OBJECT))
        assertFalse(withTools.supports(Capability.JSON_SCHEMA))

        val strict = byId["paid/strict"]!!
        assertFalse(strict.free)
        assertTrue(strict.supports(Capability.JSON_SCHEMA))
        assertTrue(strict.supports(Capability.REASONING))
        assertFalse(strict.supports(Capability.TOOL_CALLING))

        assertTrue(byId["vision/model"]!!.supports(Capability.VISION))
        // text/plain-model: CHAT only — must still be rankable (§11 single-model rule)
        val plain = byId["text/plain-model"]!!
        assertTrue(plain.chatCapable)
        assertTrue(ModelRanker.bestOrOnly(listOf(plain), ModelRanker.Purpose.AGENT) != null)
    }

    @Test fun `openrouter free-only ranking excludes every paid model`() {
        val body = JSONObject().put("data", JSONArray()
            .put(JSONObject(orModel("free/a:free", true, listOf("tools"))))
            .put(JSONObject(orModel("free/b:free", true, listOf("response_format"))))
            .put(JSONObject(orModel("paid/c", false, listOf("structured_outputs", "tools", "response_format", "reasoning"))))
        ).toString()
        val models = openrouter.normalizeCatalog(body)
        val ranked = ModelRanker.rank(models, ModelRanker.Purpose.AGENT, freeOnly = true)
        assertTrue(ranked.isNotEmpty())
        assertTrue(ranked.all { it.info.free })
        // the strongest free model (tools +30, json +15) beats the weaker one
        assertEquals("free/a:free", ranked.first().info.id)
    }

    @Test fun `openrouter non-text-output models are not chat capable`() {
        val img = JSONObject()
            .put("id", "image/gen")
            .put("pricing", JSONObject().put("prompt", "0").put("completion", "0"))
            .put("architecture", JSONObject()
                .put("input_modalities", JSONArray().put("text"))
                .put("output_modalities", JSONArray().put("image")))
            .put("supported_parameters", JSONArray().put("temperature"))
        val models = openrouter.normalizeCatalog(JSONObject().put("data", JSONArray().put(img)).toString())
        assertFalse(models[0].chatCapable)
    }

    /**
     * REAL live-catalog snapshot (fetched from openrouter.ai/api/v1/models at
     * release time, 2026-09-03). Verifies the parser + ranker against actual
     * provider data, not just synthetic fixtures. The ranker must prefer the
     * fully-capable free model and refuse the content-safety classifier.
     */
    @Test fun `live openrouter snapshot - real models normalize and rank correctly`() {
        val realTop = """{"id":"dots-studio/dots-3-note-preview:free","name":"Dots Studio: Dots3-Note Preview (free)","context_length":512000,"pricing":{"prompt":"0","completion":"0"},"architecture":{"input_modalities":["text","image"],"output_modalities":["text"]},"supported_parameters":["include_reasoning","max_tokens","reasoning","response_format","structured_outputs","temperature","tool_choice","tools","top_p"]}"""
        val realSafety = """{"id":"nvidia/nemotron-3.5-content-safety:free","name":"NVIDIA: Nemotron 3.5 Content Safety (free)","context_length":128000,"pricing":{"prompt":"0","completion":"0"},"architecture":{"input_modalities":["text","image"],"output_modalities":["text"]},"supported_parameters":["include_reasoning","max_tokens","reasoning","seed","temperature","top_p"]}"""
        val models = openrouter.normalizeCatalog(
            JSONObject().put("data", JSONArray().put(JSONObject(realTop)).put(JSONObject(realSafety))).toString()
        )
        val byId = models.associateBy { it.id }
        val top = byId["dots-studio/dots-3-note-preview:free"]!!
        assertTrue(top.free)
        assertTrue(top.supports(Capability.TOOL_CALLING))
        assertTrue(top.supports(Capability.JSON_SCHEMA))
        assertTrue(top.supports(Capability.VISION))
        assertTrue(top.supports(Capability.REASONING))
        assertEquals(512000L, top.contextLength)
        // the NVIDIA content-safety classifier must NOT be an agent candidate
        assertFalse(byId["nvidia/nemotron-3.5-content-safety:free"]!!.chatCapable)
        val ranked = ModelRanker.rank(models, ModelRanker.Purpose.AGENT, freeOnly = true)
        assertEquals(listOf("dots-studio/dots-3-note-preview:free"), ranked.map { it.info.id })
    }

    // ------------------------------------------------- error classification (§15)

    @Test fun `provider errors map to the normalized taxonomy`() {
        assertEquals(ProviderErrorKind.UNSUPPORTED_RESPONSE_FORMAT,
            ProviderErrorClassifier.classify(400, "This model does not support JSON format"))
        assertEquals(ProviderErrorKind.UNSUPPORTED_RESPONSE_FORMAT,
            ProviderErrorClassifier.classify(400, "response_format is not supported for this model"))
        assertEquals(ProviderErrorKind.UNSUPPORTED_TOOL_CALLING,
            ProviderErrorClassifier.classify(400, "tools parameter is not supported by this model"))
        assertEquals(ProviderErrorKind.MODEL_NOT_FOUND,
            ProviderErrorClassifier.classify(404, "The model `gpt-x` does not exist"))
        assertEquals(ProviderErrorKind.MODEL_NOT_FOUND,
            ProviderErrorClassifier.classify(400, "model decommissioned"))
        assertEquals(ProviderErrorKind.RATE_LIMIT,
            ProviderErrorClassifier.classify(429, "Rate limit reached"))
        assertEquals(ProviderErrorKind.RATE_LIMIT,
            ProviderErrorClassifier.classify(402, "This request requires more credits"))
        assertEquals(ProviderErrorKind.CONTEXT_TOO_LARGE,
            ProviderErrorClassifier.classify(400, "maximum context length exceeded"))
        assertEquals(ProviderErrorKind.INVALID_API_KEY,
            ProviderErrorClassifier.classify(401, "Invalid API key"))
        assertEquals(ProviderErrorKind.NETWORK_ERROR,
            ProviderErrorClassifier.classify(-1, "SocketTimeoutException"))
        assertEquals(ProviderErrorKind.PROVIDER_ERROR,
            ProviderErrorClassifier.classify(503, "upstream unavailable"))
    }

    @Test fun `provider exception carries classification`() {
        val e = ProviderException("Groq HTTP 400: does not support structured output", 400)
        assertEquals(ProviderErrorKind.UNSUPPORTED_RESPONSE_FORMAT, e.kind)
        assertEquals(400, e.httpCode)
    }

    // ------------------------------------------------- ModelInfo persistence

    @Test fun `modelinfo survives json round trip`() {
        val m = com.cometx.browser.ai.ModelInfo(
            id = "m1", provider = "groq", displayName = "Model One",
            contextLength = 131072, ownedBy = "Meta",
            capabilities = setOf(Capability.CHAT, Capability.VISION, Capability.REASONING),
            free = true, note = "n"
        )
        val back = com.cometx.browser.ai.ModelInfo.fromJson(m.toJson())
        assertEquals(m.id, back.id)
        assertEquals(m.capabilities, back.capabilities)
        assertEquals(m.contextLength, back.contextLength)
        assertTrue(m.supports(Capability.VISION))
    }

    // ------------------------------------------------- Http.Response helper

    @Test fun `http response ok semantics unchanged`() {
        assertTrue(Http.Response(200, "", null).ok)
        assertFalse(Http.Response(429, "", "x").ok)
    }
}

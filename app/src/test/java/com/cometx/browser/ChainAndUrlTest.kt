package com.cometx.browser

import com.cometx.browser.ai.OpenAICompatibleProvider
import com.cometx.browser.ai.UrlNormalizer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.1.0 — fallback chain + self-run URL normalization + model catalog parsing. */
class ChainAndUrlTest {

    // ---------- UrlNormalizer ----------

    @Test fun `normalizes bare ollama url`() {
        assertEquals("http://localhost:11434/v1", UrlNormalizer.normalize("http://localhost:11434"))
        assertEquals("http://localhost:11434/v1", UrlNormalizer.normalize("localhost:11434"))
        assertEquals("http://127.0.0.1:11434/v1", UrlNormalizer.normalize("http://127.0.0.1:11434/"))
    }

    @Test fun `normalizes lm studio and vllm ports`() {
        assertEquals("http://localhost:1234/v1", UrlNormalizer.normalize("localhost:1234"))
        assertEquals("http://192.168.1.5:8000/v1", UrlNormalizer.normalize("http://192.168.1.5:8000"))
    }

    @Test fun `keeps explicit versioned urls untouched`() {
        assertEquals("https://api.openai.com/v1", UrlNormalizer.normalize("https://api.openai.com/v1/"))
        assertEquals("http://myhost:9999/v2", UrlNormalizer.normalize("http://myhost:9999/v2"))
    }

    @Test fun `adds scheme when missing for non-local hosts`() {
        assertEquals("https://api.example.com", UrlNormalizer.normalize("api.example.com"))
    }

    // ---------- model catalog parsing ----------

    private val provider = object : OpenAICompatibleProvider(
        id = "test", displayName = "Test", defaultBaseUrl = "https://x/v1", keyProvider = { "k" }
    ) {}

    @Test fun `parses openai-style model list`() {
        val body = JSONObject().put("data", JSONArray()
            .put(JSONObject().put("id", "model-b"))
            .put(JSONObject().put("id", "model-a"))
            .put(JSONObject().put("id", "model-a"))).toString()
        assertEquals(listOf("model-a", "model-b"), provider.parseModelIds(body))
    }

    @Test fun `parses plain-string model list and caps size`() {
        val arr = JSONArray()
        for (i in 0 until 400) arr.put("m$i")
        assertEquals(300, provider.parseModelIds(JSONObject().put("data", arr).toString()).size)
    }

    @Test fun `empty body yields empty list`() {
        assertTrue(provider.parseModelIds("not json").isEmpty())
        assertTrue(provider.parseModelIds("""{"other":1}""").isEmpty())
    }

    // ---------- readyCheck (self-run without key) ----------

    @Test fun `custom readyCheck overrides key requirement`() {
        val withUrlOnly = com.cometx.browser.ai.CustomOpenAIProvider(
            keyProvider = { null },
            readyCheck = { true }
        )
        assertTrue(withUrlOnly.isReady())
    }
}

package com.cometx.browser

import com.cometx.browser.ai.GroqProvider
import com.cometx.browser.ai.HuggingFaceProvider
import com.cometx.browser.ai.OpenAICompatibleProvider
import com.cometx.browser.ai.OpenRouterProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderParsingTest {

    private val groq = GroqProvider { "test-key" }
    private val or = OpenRouterProvider { "test-key" }
    private val hf = HuggingFaceProvider { "hf-key" }

    @Test fun `parses standard openai completion body`() {
        val body = JSONObject()
            .put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", "{\"action\":\"done\"}"))))
            .toString()
        assertEquals("{\"action\":\"done\"}", groq.parseContent(body))
    }

    @Test fun `parses content-parts body`() {
        val parts = JSONArray().put(JSONObject().put("type", "text").put("text", "hello "))
            .put(JSONObject().put("type", "text").put("text", "world"))
        val body = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", parts)))).toString()
        assertEquals("hello world", or.parseContent(body))
    }

    @Test fun `returns null on malformed body`() {
        assertNull(groq.parseContent("not json"))
        assertNull(groq.parseContent("""{"choices":[]}"""))
        assertNull(groq.parseContent("""{"nope":1}"""))
    }

    @Test fun `providers carry correct endpoints`() {
        assertEquals("https://api.groq.com/openai/v1", groq.effectiveBaseUrl())
        assertEquals("https://openrouter.ai/api/v1", or.effectiveBaseUrl())
        assertEquals("https://router.huggingface.co/v1", hf.effectiveBaseUrl())
    }

    @Test fun `isReady reflects key presence`() {
        assertTrue(groq.isReady())
        val empty = GroqProvider { null }
        assertEquals(false, empty.isReady())
    }

    @Test fun `chatmessage serializes vision parts`() {
        val m = com.cometx.browser.ai.ChatMessage(role = "user", text = "look", imageBase64Jpeg = "QUJD")
        val j = m.toJson()
        assertEquals("user", j.optString("role"))
        val content = j.optJSONArray("content")
        assertTrue(content != null && content.length() == 2)
        assertTrue(content!!.getJSONObject(1).getJSONObject("image_url").getString("url").startsWith("data:image/jpeg;base64,QUJD"))
    }
}

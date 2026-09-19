package com.cometx.browser

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cometx.browser.ai.Capability
import com.cometx.browser.ai.ChatMessage
import com.cometx.browser.ai.HttpTransport
import com.cometx.browser.ai.ModelInfo
import com.cometx.browser.ai.NvidiaProvider
import com.cometx.browser.ai.OpenAICompatibleProvider
import com.cometx.browser.ai.ProviderException
import com.cometx.browser.security.SecureStore
import com.cometx.browser.util.Http
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2.2.0 — NVIDIA NIM provider: base URL (live-verified against
 * integrate.api.nvidia.com), wire contract, catalog normalization with
 * reasoning-family hints, and error normalization.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NvidiaProviderTest {

    private lateinit var context: Context

    private class FakeTransport : HttpTransport {
        var lastUrl: String? = null
        var lastBody: String? = null
        var lastHeaders: Map<String, String> = emptyMap()
        var chatResponse = Http.Response(200, """{"choices":[{"message":{"content":"pong"}}]}""", null)
        var modelsResponse = Http.Response(200, """{"object":"list","data":[]}""", null)

        override suspend fun postJson(
            url: String, body: String, headers: Map<String, String>, timeoutMs: Int
        ): Http.Response {
            lastUrl = url; lastBody = body; lastHeaders = headers
            return chatResponse
        }

        override suspend fun get(
            url: String, headers: Map<String, String>, timeoutMs: Int
        ): Http.Response {
            lastUrl = url; lastHeaders = headers
            return modelsResponse
        }
    }

    private fun makeProvider(t: FakeTransport): NvidiaProvider =
        NvidiaProvider({ "nvapi_test_key" }, t)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        SecureStore(context)
    }

    private fun catalogBody(vararg ids: Pair<String, String>): String {
        val arr = JSONArray()
        for ((id, owned) in ids) {
            arr.put(JSONObject().put("id", id).put("object", "model").put("owned_by", owned))
        }
        return JSONObject().put("object", "list").put("data", arr).toString()
    }

    @Test fun `base url is the live-verified NIM endpoint`() {
        val p = makeProvider(FakeTransport())
        assertEquals("https://integrate.api.nvidia.com/v1", p.effectiveBaseUrl())
        assertEquals("nvidia", p.id)
        assertEquals("NVIDIA NIM", p.displayName)
    }

    @Test fun `chat posts to the OpenAI-compatible path with bearer auth`() = runBlocking {
        val t = FakeTransport()
        val p = makeProvider(t)
        val out = p.chat(
            listOf(ChatMessage(role = "user", text = "Reply with the single word: pong")),
            model = "openai/gpt-oss-20b", temperature = 0.0, maxTokens = 8
        )
        assertEquals("pong", p.parseContent(out))
        assertEquals("https://integrate.api.nvidia.com/v1/chat/completions", t.lastUrl)
        assertEquals("Bearer nvapi_test_key", t.lastHeaders["Authorization"])
        val sent = JSONObject(t.lastBody ?: "{}")
        assertEquals("openai/gpt-oss-20b", sent.getString("model"))
        assertEquals(false, sent.getBoolean("stream"))
        assertNotNull(sent.getJSONArray("messages"))
    }

    @Test fun `model catalog discovery parses ids and owned_by`() = runBlocking {
        val t = FakeTransport()
        t.modelsResponse = Http.Response(
            200,
            catalogBody(
                "openai/gpt-oss-20b" to "openai",
                "meta/llama-3.1-8b-instruct" to "meta"
            ),
            null
        )
        val p = makeProvider(t)
        val models: List<ModelInfo> = p.normalizeCatalog(p.fetchModelCatalog())
        assertEquals(listOf("openai/gpt-oss-20b", "meta/llama-3.1-8b-instruct"), models.map { it.id })
        assertTrue(models.all { it.provider == "nvidia" })
        assertTrue(models.all { it.chatCapable })
    }

    @Test fun `reasoning families are flagged from model ids`() {
        val p = makeProvider(FakeTransport())
        val flag = p.normalizeCatalog(
            catalogBody(
                "openai/gpt-oss-120b" to "openai",
                "deepseek-ai/deepseek-r1" to "deepseek",
                "qwen/qwen3-next-80b-a3b-instruct" to "qwen",
                "mistralai/mistral-small-24b-instruct" to "mistral"
            )
        ).associateBy { it.id }
        assertTrue(flag["openai/gpt-oss-120b"]!!.capabilities.contains(Capability.REASONING))
        assertTrue(flag["deepseek-ai/deepseek-r1"]!!.capabilities.contains(Capability.REASONING))
        assertTrue(flag["qwen/qwen3-next-80b-a3b-instruct"]!!.capabilities.contains(Capability.REASONING))
        assertFalse(flag["mistralai/mistral-small-24b-instruct"]!!.capabilities.contains(Capability.REASONING))
    }

    @Test fun `non-chat endpoints stay excluded`() {
        assertTrue(!OpenAICompatibleProvider.isChatCapableId("nvidia/llama-3.1-nemotron-guard"))
        assertTrue(OpenAICompatibleProvider.isChatCapableId("openai/gpt-oss-20b"))
    }

    @Test fun `retired model id surfaces as provider error with the http code`() = runBlocking {
        val t = FakeTransport()
        t.chatResponse = Http.Response(
            404,
            """{"status":404,"title":"Not Found","detail":"Function 'x': Not found for account 'y'"}""",
            null
        )
        val p = makeProvider(t)
        val err = runCatching {
            p.chat(listOf(ChatMessage(role = "user", text = "hi")), model = "01-ai/yi-large")
        }.exceptionOrNull()
        assertTrue(err is ProviderException)
        assertEquals(404, (err as ProviderException).httpCode)
        assertTrue(err.message!!.contains("404"))
    }

    @Test fun `no key means not ready`() {
        val p = NvidiaProvider({ null }, FakeTransport())
        assertFalse(p.isReady())
    }
}

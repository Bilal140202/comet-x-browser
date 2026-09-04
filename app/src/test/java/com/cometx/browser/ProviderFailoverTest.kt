package com.cometx.browser

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cometx.browser.ai.AgentProtocol
import com.cometx.browser.ai.Capability
import com.cometx.browser.ai.HttpTransport
import com.cometx.browser.ai.ModelCatalog
import com.cometx.browser.ai.ModelInfo
import com.cometx.browser.ai.ModelRanker
import com.cometx.browser.ai.ModelRouter
import com.cometx.browser.ai.OpenAICompatibleProvider
import com.cometx.browser.ai.ProviderException
import com.cometx.browser.ai.ResponseInterpreters
import com.cometx.browser.ai.SettingsRepository
import com.cometx.browser.security.SecureStore
import com.cometx.browser.util.Http
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 2 regression matrix (§27–§32) + red-team scenarios (§39), executed
 * against a scripted HTTP transport — no network, no secrets, real router code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ProviderFailoverTest {

    private lateinit var context: Context
    private lateinit var settings: SettingsRepository
    private lateinit var catalog: ModelCatalog

    /** Scripted transport: per-URL handlers + request log. */
    private class FakeTransport : HttpTransport {
        val posts = mutableListOf<Pair<String, String>>()   // url to body
        // handler returns String (HTTP 200 body) or Http.Response (full control)
        var chatHandler: ((body: String) -> Any) = { _ -> """{"choices":[{"message":{"content":"pong"}}]}""" }
        var modelsBody: String = "{}"
        var modelsCode: Int = 200

        override suspend fun postJson(url: String, body: String, headers: Map<String, String>, timeoutMs: Int): Http.Response {
            posts.add(url to body)
            if (!url.endsWith("/chat/completions")) return Http.Response(404, "", "unexpected post $url")
            return when (val out = chatHandler(body)) {
                is Http.Response -> out
                is String -> Http.Response(200, out, null)
                else -> Http.Response(500, "", "bad fixture")
            }
        }

        override suspend fun get(url: String, headers: Map<String, String>, timeoutMs: Int): Http.Response =
            if (url.endsWith("/models")) Http.Response(modelsCode, modelsBody, if (modelsCode in 200..299) null else modelsBody.take(300))
            else Http.Response(404, "", "unexpected get $url")
    }

    private fun makeProvider(transport: FakeTransport): com.cometx.browser.ai.GroqProvider =
        com.cometx.browser.ai.GroqProvider({ "gsk_test" }, transport)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Robolectric has no AndroidKeyStore; override the key accessor instead of
        // writing through SecureStore. Everything else stays the REAL settings code.
        settings = object : SettingsRepository(context, SecureStore(context)) {
            override fun apiKey(id: String): String? = if (id == "groq") "gsk_test" else null
        }
        settings.runModeMigration()
        catalog = ModelCatalog(context)
        settings.clearAiLog()
    }

    // ---------------------------------------------------------- fixtures

    private fun groqCatalog(vararg models: Triple<String, Long, String>): String {
        val arr = JSONArray()
        for ((id, ctx, extra) in models) {
            val o = JSONObject().put("id", id).put("context_window", ctx).put("owned_by", "Meta")
            if (extra.isNotBlank()) o.put("note", extra)
            arr.put(o)
        }
        return JSONObject().put("data", arr).toString()
    }

    private fun chatBody(content: String): String =
        JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", content)))).toString()

    private fun decisionJson(action: String = "done", summary: String = "finished"): String =
        """{"action":"$action","summary":"$summary"}"""

    /** Groq-flavored provider wired to a scripted transport (id is "groq"). */
    private class ProviderUnderTest(transport: HttpTransport) :
        com.cometx.browser.ai.GroqProvider({ "gsk_test" }, transport)

    // ---------------------------------------------------------- R1 (§28)

    @Test fun `r1 - key only AUTO flow selects a discovered model with no manual config`() = runBlocking {
        val t = FakeTransport()
        t.modelsBody = groqCatalog(
            Triple("llama-3.3-70b-versatile", 131072, ""),
            Triple("whisper-large-v3", 0, ""),
            Triple("llama-guard-4-12b", 8192, "")
        )
        t.chatHandler = { chatBody(decisionJson()) }
        val p = ProviderUnderTest(t)
        val router = ModelRouter(settings, mapOf("groq" to p), catalog)

        val turn = router.agentStep(routerRequest())
        assertNotNull(turn.decision)
        assertEquals("done", turn.decision.action)
        // model came from the LIVE catalog, not from any hardcoded table
        assertTrue("selected ${turn.target.modelId}", turn.target.modelId == "llama-3.3-70b-versatile")
        // audio/safety endpoints excluded
        assertTrue(turn.target.model.chatCapable)
        // JSON-object mode requested up-front (Groq chat heuristic) and accepted — no failure surfaced
        assertEquals(com.cometx.browser.ai.AgentProtocol.JSON_OBJECT, turn.target.protocol)
    }

    // ---------------------------------------------------------- R2 (§29)

    @Test fun `r2 - model rejecting json mode downgrades protocol and continues`() = runBlocking {
        val t = FakeTransport()
        t.modelsBody = groqCatalog(Triple("chat-model-1", 32768, ""))
        var jsonAttempts = 0
        t.chatHandler = { body ->
            if (body.contains("\"response_format\"")) {
                jsonAttempts++
                Http.Response(400, """{"error":{"message":"This model does not support JSON format"}}""", "400")
            } else {
                chatBody("<agent>\nACTION=CLICK\nREF=e2\n</agent>")
            }
        }
        val p = ProviderUnderTest(t)
        val router = ModelRouter(settings, mapOf("groq" to p), catalog)

        val turn = router.agentStep(routerRequest())
        assertEquals("click", turn.decision.action)
        assertEquals("e2", turn.decision.target)
        assertTrue("expected at least one json attempt", jsonAttempts >= 1)
        // final successful call used the tagged protocol (no response_format)
        assertTrue(t.posts.last().second.contains("<agent>") || !t.posts.last().second.contains("response_format"))
        // negotiation is remembered for subsequent steps
        assertEquals(AgentProtocol.TAGGED_TEXT, catalog.knownGoodProtocol("groq", "chat-model-1"))
    }

    // ---------------------------------------------------------- R3 (§30)

    @Test fun `r3 - configured model vanishing triggers replacement and retry`() = runBlocking {
        val t = FakeTransport()
        t.modelsBody = groqCatalog(
            Triple("old-model", 32768, ""),
            Triple("new-model", 32768, "")
        )
        val attemptedModels = mutableListOf<String>()
        t.chatHandler = { body ->
            val model = JSONObject(body).optString("model")
            attemptedModels.add(model)
            if (model == "old-model") Http.Response(404, """{"error":{"message":"model decommissioned and no longer available"}}""", "404")
            else chatBody(decisionJson())
        }
        val p = ProviderUnderTest(t)
        val router = ModelRouter(settings, mapOf("groq" to p), catalog)

        val turn = router.agentStep(routerRequest())
        assertEquals("done", turn.decision.action)
        assertTrue("replacement used", turn.target.modelId != "old-model")
        assertTrue(attemptedModels.contains("new-model"))
    }

    // ---------------------------------------------------------- R4 (§31)

    @Test fun `r4 - chat model without vision falls back to dom perception`() = runBlocking {
        val t = FakeTransport()
        t.modelsBody = groqCatalog(Triple("text-only-chat", 32768, ""))
        var sawImage = false
        t.chatHandler = { body ->
            if (body.contains("image_url")) sawImage = true
            chatBody(decisionJson())
        }
        val p = ProviderUnderTest(t)
        val router = ModelRouter(settings, mapOf("groq" to p), catalog)

        // resolved agent model has no VISION capability
        val target = router.resolve(ModelRouter.Role.AGENT)
        assertNotNull(target)
        assertTrue(!target!!.model.supports(Capability.VISION))
        // describeScreenshot returns null (no vision model anywhere) → engine uses DOM
        assertNull(router.describeScreenshot("ZmFrZQ=="))
        // and a normal step never receives an image (engine strips it — covered by AgentEngine)
        router.agentStep(routerRequest())
        assertTrue("pixels must not reach a non-vision model", !sawImage)
    }

    // ---------------------------------------------------------- R5 (§32)

    @Test fun `r5 - model with no structured output still executes browser actions via text protocol`() = runBlocking {
        val t = FakeTransport()
        t.modelsBody = groqCatalog(Triple("plain-texter", 8192, ""))
        var sawResponseFormat = false
        val bodies = mutableListOf<String>()
        t.chatHandler = { body ->
            bodies.add(body)
            if (body.contains("\"response_format\"")) sawResponseFormat = true
            // model ignores JSON instructions entirely, answers in tagged text
            chatBody("ACTION=TYPE\nREF=e3\nTEXT=hello world")
        }
        val p = ProviderUnderTest(t)
        val router = ModelRouter(settings, mapOf("groq" to p), catalog)

        val turn = router.agentStep(routerRequest())
        assertEquals("type", turn.decision.action)
        assertEquals("hello world", turn.decision.value)
        assertEquals(AgentProtocol.TAGGED_TEXT, turn.target.protocol)
        assertTrue("structured format must not be demanded forever", !sawResponseFormat || bodies.size > 1)
    }

    // ---------------------------------------------------------- §16/§17 catalog changes

    @Test fun `removed model triggers catalog refresh and auto-switch with user-visible note`() = runBlocking {
        val t = FakeTransport()
        t.modelsBody = groqCatalog(Triple("legacy", 4096, ""))
        t.chatHandler = { chatBody(decisionJson()) }
        val p = ProviderUnderTest(t)
        val router = ModelRouter(settings, mapOf("groq" to p), catalog)
        // prime cache with legacy model, then the catalog rotates
        router.resolve(ModelRouter.Role.AGENT)
        t.modelsBody = groqCatalog(Triple("fresh-model", 4096, ""))
        t.chatHandler = { body ->
            if (JSONObject(body).optString("model") == "legacy")
                Http.Response(404, """{"error":{"message":"model not found"}}""", "404")
            else chatBody(decisionJson())
        }
        val turn = router.agentStep(routerRequest())
        assertEquals("fresh-model", turn.target.modelId)
        assertTrue(turn.events.any { it.contains("auto-switched") || it.contains("not found") })
    }

    // ---------------------------------------------------------- §18 rate limit

    @Test fun `rate limited provider falls through and recovers via backoff`() = runBlocking {
        val t = FakeTransport()
        t.modelsBody = groqCatalog(Triple("busy-model", 8192, ""))
        var calls = 0
        t.chatHandler = { _ ->
            calls++
            if (calls <= 1) Http.Response(429, """{"error":{"message":"rate limit exceeded, too many requests"}}""", "429")
            else chatBody(decisionJson())
        }
        val p = ProviderUnderTest(t)
        val router = ModelRouter(settings, mapOf("groq" to p), catalog)
        val turn = router.agentStep(routerRequest())
        assertEquals("done", turn.decision.action)
        assertTrue(calls >= 2)
    }

    @Test fun `invalid api key skips provider instead of hanging`() = runBlocking {
        val t = FakeTransport()
        t.modelsBody = groqCatalog(Triple("m", 8192, ""))
        t.chatHandler = { Http.Response(401, """{"error":{"message":"invalid api key"}}""", "401") }
        val p = ProviderUnderTest(t)
        val router = ModelRouter(settings, mapOf("groq" to p), catalog)
        try {
            router.agentStep(routerRequest())
            throw AssertionError("expected failure after only provider rejected the key")
        } catch (e: ProviderException) {
            // normalized classification, not a raw leak
            assertEquals(com.cometx.browser.ai.ProviderErrorKind.INVALID_API_KEY, e.kind)
        }
    }

    // ---------------------------------------------------------- red-team catalogs (§39)

    @Test fun `malformed catalog bodies produce clear errors not crashes`() = runBlocking {
        val t = FakeTransport()
        val p = ProviderUnderTest(t)
        val router = ModelRouter(settings, mapOf("groq" to p), catalog)
        for (body in listOf("not json", "{}", """{"data":[]}""", """{"data":[null,{},"x"]}""")) {
            t.modelsBody = body
            t.modelsCode = 200
            try {
                router.agentStep(routerRequest())
            } catch (e: Exception) {
                // graceful degradation: only normalized failures are acceptable
                assertTrue(
                    "unexpected failure class: ${e.javaClass.simpleName}",
                    e is ProviderException || e is com.cometx.browser.ai.UnparseableOutputException
                )
            }
        }
    }

    @Test fun `duplicate model entries are deduplicated`() = runBlocking {
        val t = FakeTransport()
        t.modelsBody = groqCatalog(Triple("dup", 8192, ""), Triple("dup", 8192, ""), Triple("other", 8192, ""))
        val p = ProviderUnderTest(t)
        val models = catalog.fetch(p)
        assertEquals(listOf("dup", "other"), models.map { it.id }.sorted())
    }

    @Test fun `catalog of only non-chat endpoints yields no candidates`() = runBlocking {
        val t = FakeTransport()
        t.modelsBody = groqCatalog(Triple("whisper-large-v3", 0, ""), Triple("llama-guard-4-12b", 8192, ""))
        val p = ProviderUnderTest(t)
        val router = ModelRouter(settings, mapOf("groq" to p), catalog)
        val target = router.resolve(ModelRouter.Role.AGENT)
        assertNull("non-chat models must not be selected", target)
    }

    @Test fun `model disappearing mid-task is surfaced in events`() = runBlocking {
        val t = FakeTransport()
        t.modelsBody = groqCatalog(Triple("vanishing", 8192, ""))
        var flip = false
        t.chatHandler = { body ->
            flip = !flip
            if (flip) Http.Response(404, """{"error":{"message":"model does not exist"}}""", "404")
            else chatBody(decisionJson())
        }
        val p = ProviderUnderTest(t)
        val router = ModelRouter(settings, mapOf("groq" to p), catalog)
        val turn = router.agentStep(routerRequest())
        assertEquals("done", turn.decision.action)
        assertTrue(turn.events.isNotEmpty())
    }

    // ---------------------------------------------------------- helpers

    private fun routerRequest() = ModelRouter.AgentRequest(
        ModelRouter.Role.AGENT
    ) { protocol ->
        listOf(
            com.cometx.browser.ai.ChatMessage("system", "system prompt for $protocol"),
            com.cometx.browser.ai.ChatMessage("user", "OBSERVATION: e1 button")
        )
    }
}

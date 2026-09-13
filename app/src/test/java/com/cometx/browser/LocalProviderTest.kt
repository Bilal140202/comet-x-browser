package com.cometx.browser

import com.cometx.browser.ai.ChatMessage
import com.cometx.browser.ai.ContextTooLargeException
import com.cometx.browser.ai.ProviderErrorKind
import com.cometx.browser.ai.ProviderException
import com.cometx.browser.ai.local.GenProgressListener
import com.cometx.browser.ai.local.LocalLlamaProvider
import com.cometx.browser.ai.local.LocalModelCatalog
import com.cometx.browser.ai.local.NativeLlama
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * v1.6.0 on-device AI: provider contract against a FAKE native engine (never
 * the real JNI boundary). Covers the failover semantics the router relies on:
 * ProviderException kinds, the §19 context budget, and the single-completion guard.
 */
class LocalProviderTest {

    private class FakeEngine(
        override val available: Boolean = true,
        private val ctxSize: Int = 4096,
        private val promptTokens: Int = 0,
        private val output: String? = "{\"action\":\"done\"}",
    ) : NativeLlama {
        var loaded: Boolean = false
        var loadCalls: Int = 0
        var lastPrompt: String? = null
        var lastStops: List<String> = emptyList()
        var lastMaxTokens: Int = -1

        override fun load(path: String, contextSize: Int, threads: Int): Boolean {
            loadCalls++
            loaded = true
            return true
        }
        override fun isLoaded(): Boolean = loaded
        override fun contextSize(): Int = if (loaded) ctxSize else 0
        override fun countTokens(text: String): Int = promptTokens
        override fun complete(
            prompt: String, maxTokens: Int, temperature: Float,
            listener: GenProgressListener?, stopSequences: List<String>,
        ): String? {
            if (!loaded) return null
            lastPrompt = prompt
            lastStops = stopSequences
            lastMaxTokens = maxTokens
            return output
        }
        override fun cancel() { loaded = loaded }
        override fun free() { loaded = false }
    }

    private val model = LocalModelCatalog.byId("llama32-1b")!!
    private val file = File("/tmp/fake-llama32-1b.gguf")

    private fun provider(engine: FakeEngine, selected: Boolean = true, loadResult: Boolean = true) =
        LocalLlamaProvider(
            engine,
            resolveSelected = { if (selected) model to file else null },
            loadSync = { _, _ -> loadResult.also { if (loadResult) engine.loaded = true } },
            contextSize = { 4096 },
        )

    private val messages = listOf(
        ChatMessage("system", "You are Comet-X."),
        ChatMessage("user", "OBSERVATION: page"),
    )

    @Test fun `unavailable native runtime fails honestly and never loads`() = runBlocking {
        val p = provider(FakeEngine(available = false))
        assertTrue(!p.isReady())
        try {
            p.chat(messages, "local", 0.2, 512)
            fail("expected ProviderException")
        } catch (e: ProviderException) {
            assertEquals(ProviderErrorKind.MODEL_UNAVAILABLE, e.kind)
        }
    }

    @Test fun `no downloaded model is not ready and chat throws model_unavailable`() = runBlocking {
        val p = provider(FakeEngine(), selected = false)
        assertTrue(!p.isReady())
        try {
            p.chat(messages, "local", 0.2, 512)
            fail("expected ProviderException")
        } catch (e: ProviderException) {
            assertEquals(ProviderErrorKind.MODEL_UNAVAILABLE, e.kind)
        }
    }

    @Test fun `on-demand load path activates a downloaded but unloaded model`() = runBlocking {
        val engine = FakeEngine()          // resident = false
        val p = provider(engine, loadResult = true)
        assertTrue(p.isReady())            // cheap readiness: file selected + runtime available
        val out = p.chat(messages, "local", 0.2, 512)
        assertEquals("{\"action\":\"done\"}", out)
        assertTrue(engine.loaded)          // on-demand load went through the manager lambda
    }

    @Test fun `failed on-demand load surfaces as model_unavailable`() = runBlocking {
        val engine = FakeEngine()
        val p = provider(engine, loadResult = false)
        try {
            p.chat(messages, "local", 0.2, 512)
            fail("expected ProviderException")
        } catch (e: ProviderException) {
            assertEquals(ProviderErrorKind.MODEL_UNAVAILABLE, e.kind)
        }
    }

    @Test fun `prompt over context budget raises ContextTooLargeException`() = runBlocking {
        // budget = 4096 - 512 - 8 = 3576 tokens; prompt claims 4000
        val engine = FakeEngine(promptTokens = 4000)
        val p = provider(engine)
        p.markLoaded(model) // skip load path
        try {
            p.chat(messages, "local", 0.2, 512)
            fail("expected ContextTooLargeException")
        } catch (_: ContextTooLargeException) { /* §19 compression ladder */ }
    }

    @Test fun `llama3 template prompt and native stop sequences reach the engine`() = runBlocking {
        val engine = FakeEngine()
        val p = provider(engine)
        p.markLoaded(model)
        p.chat(messages, "local", 0.2, 512)
        assertTrue(engine.lastPrompt!!.startsWith("<|begin_of_text|>"))
        assertTrue(engine.lastStops.contains("<|eot_id|>"))
    }

    @Test fun `agent maxTokens request is capped for the small local window`() = runBlocking {
        val engine = FakeEngine()
        val p = provider(engine)
        p.markLoaded(model)
        p.chat(messages, "local", 0.2, 2000)   // what the router requests by default
        assertEquals(512, engine.lastMaxTokens)
    }

    @Test fun `empty model output is a provider error so the router moves on`() = runBlocking {
        val engine = FakeEngine(output = "   ")
        val p = provider(engine)
        p.markLoaded(model)
        try {
            p.chat(messages, "local", 0.2, 512)
            fail("expected ProviderException")
        } catch (e: ProviderException) {
            assertEquals(ProviderErrorKind.PROVIDER_ERROR, e.kind)
        }
    }

    @Test fun `native null output is a provider error`() = runBlocking {
        val engine = FakeEngine(output = null)
        val p = provider(engine)
        p.markLoaded(model)
        try {
            p.chat(messages, "local", 0.2, 512)
            fail("expected ProviderException")
        } catch (e: ProviderException) {
            assertEquals(ProviderErrorKind.PROVIDER_ERROR, e.kind)
        }
    }

    @Test fun `second concurrent completion is refused (single native context)`() = runBlocking {
        val engine = FakeEngine().also { it.loaded = true } // engine resident: skip on-demand load path
        val slow = object : NativeLlama by engine {
            override fun complete(
                prompt: String, maxTokens: Int, temperature: Float,
                listener: GenProgressListener?, stopSequences: List<String>,
            ): String? {
                Thread.sleep(150)
                return "slow"
            }
        }
        val slowProvider = LocalLlamaProvider(
            slow,
            resolveSelected = { model to file },
            loadSync = { _, _ -> true },
            contextSize = { 4096 },
        )

        val t1 = Thread { runBlocking { slowProvider.chat(messages, "local", 0.2, 512) } }
        t1.start()
        Thread.sleep(50) // t1 inside nativeComplete now
        try {
            runBlocking { slowProvider.chat(messages, "local", 0.2, 512) }
            fail("expected ProviderException for concurrent completion")
        } catch (e: ProviderException) {
            assertEquals(ProviderErrorKind.MODEL_UNAVAILABLE, e.kind)
        } finally {
            t1.join()
        }
    }

    @Test fun `defaultModelId mirrors the selected catalog entry`() {
        val p = provider(FakeEngine())
        assertEquals("llama32-1b", p.defaultModelId)
    }
}

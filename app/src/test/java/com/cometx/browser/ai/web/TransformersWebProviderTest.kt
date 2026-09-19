package com.cometx.browser.ai.web

import com.cometx.browser.ai.ChatMessage
import com.cometx.browser.ai.ContextTooLargeException
import com.cometx.browser.ai.ProviderErrorKind
import com.cometx.browser.ai.ProviderException
import com.cometx.browser.ai.SettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TransformersWebProvider (v2.1.0) — contract tests against a fake engine.
 * The provider must behave EXACTLY like LocalLlamaProvider from the router's
 * point of view: same guards, same exception kinds, same readiness semantics,
 * same "text-only" honesty. These tests pin that parity.
 */
class TransformersWebProviderTest {

    private class FakeEngine(
        private val genText: String = "  {\"action\":\"click\"}  ",
        override val healthy: Boolean = true,
        private val failGenerate: String? = null,
        private val gate: CompletableDeferred<Unit>? = null,
    ) : WebLlmEngine {
        var ensureStartedCalls = 0
        var released = false
        var interruptedFor = -1L
        var lastLoadedModel: String? = null
        var lastMaxTokens = 0
        var lastTemperature = 0.0
        var lastMessages: List<ChatMessage> = emptyList()
        var streamSink: ((String) -> Unit)? = null

        override suspend fun ensureStarted() { ensureStartedCalls++ }

        override suspend fun load(model: String, dtype: String) { lastLoadedModel = model }

        override suspend fun generate(
            sid: Long,
            messages: List<ChatMessage>,
            maxTokens: Int,
            temperature: Double,
            onStream: (String) -> Unit,
        ): String {
            lastMaxTokens = maxTokens
            lastTemperature = temperature
            lastMessages = messages
            streamSink = onStream
            gate?.await()
            failGenerate?.let { throw WebLlmException(it) }
            onStream("He")
            onStream("llo")
            return genText
        }

        override fun interrupt(sid: Long) { interruptedFor = sid }

        override fun release() { released = true }

        override val progress = MutableStateFlow(WebLoadProgress())
    }

    private class FakeEngineProgress(override val healthy: Boolean = true) : WebLlmEngine {
        override suspend fun ensureStarted() {}
        override suspend fun load(model: String, dtype: String) {}
        override suspend fun generate(
            sid: Long, messages: List<ChatMessage>, maxTokens: Int,
            temperature: Double, onStream: (String) -> Unit,
        ) = "x"
        override fun interrupt(sid: Long) {}
        override fun release() {}
        override val progress = MutableStateFlow(WebLoadProgress())
    }

    private var selectedId: String? = null
    private val engine = FakeEngine()
    private val provider = TransformersWebProvider(engine) {
        WebLlmCatalog.byIdOrNull(selectedId)
    }

    private fun userMsg(text: String) = listOf(ChatMessage("user", text))

    // ------------------------------------------------------------ readiness

    @Test fun `not ready when nothing selected - cheap check never starts the engine`() {
        selectedId = null
        assertFalse(provider.isReady())
        assertEquals(0, engine.ensureStartedCalls)
    }

    @Test fun `ready when a web model is selected and engine healthy`() {
        selectedId = WebLlmCatalog.DEFAULT_MODEL_ID
        assertTrue(provider.isReady())
    }

    @Test fun `not ready when engine is broken (renderer lost)`() {
        selectedId = WebLlmCatalog.DEFAULT_MODEL_ID
        val broken = TransformersWebProvider(FakeEngine(healthy = false)) {
            WebLlmCatalog.byIdOrNull(selectedId)
        }
        assertFalse(broken.isReady())
    }

    @Test fun `unknown persisted id resolves to not-ready (no crash)`() {
        selectedId = "deleted/model-v99"
        assertFalse(provider.isReady())
    }

    // ------------------------------------------------------------ chat guards

    @Test fun `multimodal input is refused as MODEL_UNAVAILABLE (text-only honesty)`() = runBlocking {
        selectedId = WebLlmCatalog.DEFAULT_MODEL_ID
        val msgs = listOf(ChatMessage("user", "look", imageBase64Jpeg = "b64"))
        try {
            provider.chat(msgs, model = WebLlmCatalog.DEFAULT_MODEL_ID)
            throw AssertionError("expected refusal")
        } catch (e: ProviderException) {
            assertEquals(ProviderErrorKind.MODEL_UNAVAILABLE, e.kind)
        }
    }

    @Test fun `chat with no selection throws MODEL_UNAVAILABLE`() = runBlocking {
        selectedId = null
        try {
            provider.chat(userMsg("hi"), model = "x")
            throw AssertionError("expected failure")
        } catch (e: ProviderException) {
            assertEquals(ProviderErrorKind.MODEL_UNAVAILABLE, e.kind)
        }
    }

    @Test fun `happy path trims output and loads the selected model`() = runBlocking {
        selectedId = WebLlmCatalog.QWEN_05B.id
        val out = provider.chat(userMsg("decide"), model = WebLlmCatalog.QWEN_05B.id)
        assertEquals("{\"action\":\"click\"}", out)
        assertEquals(WebLlmCatalog.QWEN_05B.id, engine.lastLoadedModel)
        assertEquals(1, engine.ensureStartedCalls)
    }

    @Test fun `output cap clamped to 64-512 (protects wasm ram)`() = runBlocking {
        selectedId = WebLlmCatalog.DEFAULT_MODEL_ID
        provider.chat(userMsg("a"), model = "x", maxTokens = 5000)
        assertEquals(512, engine.lastMaxTokens)
        provider.chat(userMsg("b"), model = "x", maxTokens = 1)
        assertEquals(64, engine.lastMaxTokens)
    }

    @Test fun `stream chunks accumulate into the gen snapshot`() = runBlocking {
        selectedId = WebLlmCatalog.DEFAULT_MODEL_ID
        provider.chat(userMsg("hi"), model = "x")
        val snap = provider.genState.value
        assertEquals("Hello", snap.partialText)
        assertEquals(2, snap.outTokens)
        assertFalse(snap.generating)
    }

    @Test fun `empty output is PROVIDER_ERROR (never return empty to the engine)`() = runBlocking {
        val p = TransformersWebProvider(FakeEngine(genText = "   ")) {
            WebLlmCatalog.byIdOrNull(WebLlmCatalog.DEFAULT_MODEL_ID)
        }
        try {
            p.chat(userMsg("hi"), model = "x")
            throw AssertionError("expected failure")
        } catch (e: ProviderException) {
            assertEquals(ProviderErrorKind.PROVIDER_ERROR, e.kind)
        }
    }

    @Test fun `runtime failure maps to PROVIDER_ERROR with the real message`() = runBlocking {
        val p = TransformersWebProvider(FakeEngine(failGenerate = "wasm oom")) {
            WebLlmCatalog.byIdOrNull(WebLlmCatalog.DEFAULT_MODEL_ID)
        }
        try {
            p.chat(userMsg("hi"), model = "x")
            throw AssertionError("expected failure")
        } catch (e: ProviderException) {
            assertEquals(ProviderErrorKind.PROVIDER_ERROR, e.kind)
            assertTrue(e.message!!.contains("wasm oom"))
        }
    }

    @Test fun `second concurrent chat is refused (single-flight)`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val slow = TransformersWebProvider(FakeEngine(gate = gate)) {
            WebLlmCatalog.byIdOrNull(WebLlmCatalog.DEFAULT_MODEL_ID)
        }
        selectedId = WebLlmCatalog.DEFAULT_MODEL_ID
        val job = launch { slow.chat(userMsg("first"), model = "x") }
        // deterministic: wait until the first chat actually holds the flag
        val deadline = System.currentTimeMillis() + 5_000
        while (!slow.genState.value.generating && System.currentTimeMillis() < deadline) yield()
        try {
            slow.chat(userMsg("second"), model = "x")
            throw AssertionError("expected single-flight refusal")
        } catch (e: ProviderException) {
            assertEquals(ProviderErrorKind.MODEL_UNAVAILABLE, e.kind)
        } finally {
            gate.complete(Unit)
            job.join()
        }
    }

    // ------------------------------------------------------------ wiring contract

    @Test fun `provider id matches the router constant (lockstep)`() {
        assertEquals(SettingsRepository.WEB_PROVIDER_ID, provider.id)
        assertEquals("On-device (Transformers.js)", provider.displayName)
        assertEquals("", provider.defaultBaseUrl)
    }

    @Test fun `defaultModelId mirrors the selection (router hook)`() {
        selectedId = WebLlmCatalog.QWEN_15B.id
        assertEquals(WebLlmCatalog.QWEN_15B.id, provider.defaultModelId)
        selectedId = null
        assertEquals(null, provider.defaultModelId)
    }

    // ------------------------------------------------------------ budget

    @Test fun `absurd prompt lengths are refused before the renderer ooms`() {
        val p = TransformersWebProvider(FakeEngineProgress()) {
            WebLlmCatalog.byIdOrNull(WebLlmCatalog.DEFAULT_MODEL_ID)
        }
        try {
            p.checkPromptBudget(120_000, WebLlmCatalog.QWEN_05B)
            throw AssertionError("expected ContextTooLargeException")
        } catch (e: ContextTooLargeException) {
            assertTrue(e.message!!.contains("in-browser"))
        }
        // normal sizes pass
        p.checkPromptBudget(5_000, WebLlmCatalog.QWEN_05B)
    }

    @Test fun `chat() enforces the budget too (engine §19 compression path)`() = runBlocking {
        selectedId = WebLlmCatalog.DEFAULT_MODEL_ID
        val huge = "a".repeat(60_001)
        try {
            provider.chat(userMsg(huge), model = "x")
            throw AssertionError("expected ContextTooLargeException")
        } catch (e: ContextTooLargeException) {
            assertTrue(engine.lastLoadedModel == null) // never even loaded
        }
        // a 60_000-char prompt (exactly at the cap) passes the guard
        provider.chat(userMsg("a".repeat(60_000)), model = "x")
        assertEquals(WebLlmCatalog.DEFAULT_MODEL_ID, engine.lastLoadedModel)
    }
}

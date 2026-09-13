package com.cometx.browser

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cometx.browser.ai.AgentProtocol
import com.cometx.browser.ai.LlmProvider
import com.cometx.browser.ai.ModelRouter
import com.cometx.browser.ai.SettingsRepository
import com.cometx.browser.security.SecureStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.6.0 on-device AI: router chain integration. The local provider must join
 * the fallback chain ADDITIVELY — absent when not ready, last when it is a
 * fallback, first when the user prefers local — and the cloud chain positions
 * must be untouched in every case.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LocalChainTest {

    private lateinit var context: Context
    private lateinit var settings: SettingsRepository

    private class FakeProvider(
        override val id: String,
        private val ready: Boolean,
        override val defaultModelId: String? = null,
    ) : LlmProvider {
        override val displayName = "fake:$id"
        override val defaultBaseUrl = ""
        override fun isReady(): Boolean = ready
        override suspend fun chat(
            messages: List<com.cometx.browser.ai.ChatMessage>,
            model: String, temperature: Double, maxTokens: Int
        ): String = ""
    }

    private fun localProvider(ready: Boolean) =
        FakeProvider(SettingsRepository.LOCAL_PROVIDER_ID, ready, "fake-local-model")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        settings = object : SettingsRepository(context, SecureStore(context)) {
            override fun apiKey(id: String): String? = if (id == "groq") "gsk_test" else null
        }
        settings.runModeMigration()
    }

    private fun router(local: LlmProvider?) = ModelRouter(
        settings,
        buildMap {
            put("groq", FakeProvider("groq", ready = true))
            if (local != null) put(SettingsRepository.LOCAL_PROVIDER_ID, local)
        },
        null
    )

    @Test fun `no model downloaded means local not ready - chain is cloud-only`() {
        // real LocalLlamaProvider.isReady() is false when nothing is selected
        // (cheap file check) — the fake mirrors that state via ready=false
        val chain = router(localProvider(ready = false)).chain()
        assertEquals(listOf("fake:groq"), chain.map { it.displayName })
    }

    @Test fun `local ready and not preferred - appended as last-resort fallback`() {
        settings.setLocalModelId("llama32-1b")
        val chain = router(localProvider(ready = true)).chain()
        assertEquals(listOf("fake:groq", "fake:local"), chain.map { it.displayName })
    }

    @Test fun `local ready and preferred - prepended before cloud`() {
        settings.setLocalModelId("llama32-1b")
        settings.setLocalAiPreferred(true)
        val chain = router(localProvider(ready = true)).chain()
        assertEquals(listOf("fake:local", "fake:groq"), chain.map { it.displayName })
    }

    @Test fun `local preferred but not ready - chain stays cloud-only`() {
        settings.setLocalModelId("llama32-1b")
        settings.setLocalAiPreferred(true)
        val chain = router(localProvider(ready = false)).chain()
        assertEquals(listOf("fake:groq"), chain.map { it.displayName })
    }

    @Test fun `local provider absent from map entirely - chain unchanged`() {
        settings.setLocalModelId("llama32-1b")
        settings.setLocalAiPreferred(true)
        val chain = router(null).chain()
        assertEquals(listOf("fake:groq"), chain.map { it.displayName })
    }

    @Test fun `local candidates use the provider-named model with json object protocol`() = runBlocking {
        settings.setLocalModelId("llama32-1b")
        val r = router(localProvider(ready = true))
        val target = r.resolve(ModelRouter.Role.AGENT)
        // preferred is false → groq first (fake groq has no catalog here, so it still resolves)
        assertTrue(target != null)
        // force local-first ordering
        settings.setLocalAiPreferred(true)
        val localTarget = r.resolve(ModelRouter.Role.AGENT)
        assertEquals("fake-local-model", localTarget?.modelId)
        // local caps {CHAT, JSON_OBJECT, STREAMING} → JSON_OBJECT protocol, never TOOL_CALLING
        assertEquals(AgentProtocol.JSON_OBJECT, localTarget?.protocol)
    }
}

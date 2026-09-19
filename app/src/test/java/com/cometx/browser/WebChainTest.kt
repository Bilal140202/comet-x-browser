package com.cometx.browser

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cometx.browser.ai.LlmProvider
import com.cometx.browser.ai.ModelRouter
import com.cometx.browser.ai.SettingsRepository
import com.cometx.browser.security.SecureStore
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2.1.0 in-browser AI: router chain integration. The Transformers.js provider
 * must join the fallback chain EXACTLY like the llama.cpp provider did in
 * v1.6.0 — additively, ranked AFTER native llama.cpp (native ARM beats WASM),
 * absent when no web model is selected, first when the user prefers on-device.
 * Cloud chain positions must be untouched in every case.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WebChainTest {

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

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        settings = object : SettingsRepository(context, SecureStore(context)) {
            override fun apiKey(id: String): String? = if (id == "groq") "gsk_test" else null
        }
        settings.runModeMigration()
    }

    private fun router(
        localReady: Boolean = false,
        webReady: Boolean = false,
    ) = ModelRouter(
        settings,
        buildMap {
            put("groq", FakeProvider("groq", ready = true))
            put(SettingsRepository.LOCAL_PROVIDER_ID, FakeProvider(SettingsRepository.LOCAL_PROVIDER_ID, localReady))
            put(SettingsRepository.WEB_PROVIDER_ID, FakeProvider(SettingsRepository.WEB_PROVIDER_ID, webReady))
        },
        null
    )

    @Test fun `no web model selected - chain byte-identical to v2_0_0`() {
        settings.setLocalModelId(null)
        settings.setWebModelId(null)
        assertEquals(listOf("fake:groq"), router().chain().map { it.displayName })
    }

    @Test fun `local ready web not - v2_0_0 behavior preserved`() {
        settings.setLocalModelId("llama32-1b")
        settings.setWebModelId(null)
        assertEquals(listOf("fake:groq", "fake:local"), router(localReady = true).chain().map { it.displayName })
    }

    @Test fun `web ready - appended after local (native beats wasm)`() {
        settings.setWebModelId("onnx-community/Qwen2.5-0.5B-Instruct")
        assertEquals(
            listOf("fake:groq", "fake:local", "fake:webtransformers"),
            router(localReady = true, webReady = true).chain().map { it.displayName })
    }

    @Test fun `web ready without local model - web is the last resort`() {
        settings.setWebModelId("onnx-community/Qwen2.5-0.5B-Instruct")
        assertEquals(
            listOf("fake:groq", "fake:webtransformers"),
            router(webReady = true).chain().map { it.displayName })
    }

    @Test fun `prefer on-device - local then web then cloud`() {
        settings.setLocalModelId("llama32-1b")
        settings.setWebModelId("onnx-community/Qwen2.5-0.5B-Instruct")
        settings.setLocalAiPreferred(true)
        assertEquals(
            listOf("fake:local", "fake:webtransformers", "fake:groq"),
            router(localReady = true, webReady = true).chain().map { it.displayName })
    }
}

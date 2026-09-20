package com.cometx.browser

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cometx.browser.ai.KeyFormat
import com.cometx.browser.ai.LlmProvider
import com.cometx.browser.ai.ModelRouter
import com.cometx.browser.ai.NvidiaProvider
import com.cometx.browser.ai.ProviderException
import com.cometx.browser.ai.SettingsRepository
import com.cometx.browser.security.SecureStore
import com.cometx.browser.util.Http
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2.3.0 Cloud AI center: key format/masking utilities, the verified NVIDIA
 * model-id defaults in the router, chain additivity guarantees (the Compose
 * center shares the same settings keys as the legacy UI) and Discord settings
 * round-trips through the v2.2.0 accessors. NvidiaProvider / DiscordNotifier
 * internals are covered by NvidiaProviderTest / DiscordNotifierTest (v2.2.0).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CloudAiV22Test {

    private lateinit var context: Context
    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        settings = object : SettingsRepository(context, SecureStore(context)) {
            override fun apiKey(id: String): String? = if (id == "nvidia") "nvapi-test123456789" else null
        }
        settings.runModeMigration()
    }

    // ------------------------------------------------------------ chain (additive)

    @Test fun `default chain contains nvidia after openrouter`() {
        assertEquals(
            listOf("groq", "openrouter", "nvidia", "huggingface", "custom"),
            settings.chainOrder()
        )
    }

    @Test fun `stored pre-nvidia chain gets nvidia appended - no migration write`() {
        settings.setChainOrder(listOf("groq", "openrouter", "huggingface", "custom"))
        assertEquals(
            listOf("groq", "openrouter", "huggingface", "custom", "nvidia"),
            settings.chainOrder()
        )
    }

    @Test fun `nvidia disabled by default - enable flag persists`() {
        assertFalse(settings.providerEnabled("nvidia"))
        settings.setProviderEnabled("nvidia", true)
        assertTrue(settings.providerEnabled("nvidia"))
    }

    @Test fun `live chain excludes nvidia until enabled`() {
        settings.setProviderEnabled("nvidia", false)
        assertFalse(settings.liveChain().contains("nvidia"))
        settings.setProviderEnabled("nvidia", true)
        assertTrue(settings.liveChain().contains("nvidia"))
    }

    private class FakeProvider(
        override val id: String,
        private val ready: Boolean,
    ) : LlmProvider {
        override val displayName = "fake:$id"
        override val defaultBaseUrl = ""
        override fun isReady(): Boolean = ready
        override suspend fun chat(
            messages: List<com.cometx.browser.ai.ChatMessage>,
            model: String, temperature: Double, maxTokens: Int
        ): String = ""
    }

    @Test fun `router chain places keyed+enabled nvidia in the cloud section`() {
        settings.setProviderEnabled("nvidia", true)
        val router = ModelRouter(
            settings,
            mapOf(
                "groq" to FakeProvider("groq", ready = false),
                "nvidia" to FakeProvider("nvidia", ready = true),
                SettingsRepository.LOCAL_PROVIDER_ID to FakeProvider(SettingsRepository.LOCAL_PROVIDER_ID, false)
            ),
            null
        )
        assertEquals(listOf("fake:nvidia"), router.chain().map { it.displayName })
    }

    // ------------------------------------------------------------ NVIDIA defaults + live shape

    @Test fun `nvidia router defaults cover every role`() {
        for (role in ModelRouter.Role.entries) {
            assertTrue("role $role", ModelRouter.defaultModelFor("nvidia", role).isNotBlank())
        }
        assertEquals("openai/gpt-oss-20b", ModelRouter.defaultModelFor("nvidia", ModelRouter.Role.AGENT))
        assertEquals("meta/llama-3.2-11b-vision-instruct", ModelRouter.defaultModelFor("nvidia", ModelRouter.Role.VISION))
    }

    @Test fun `nvidia provider posts to the live-verified url and adds reasoning hints`() = runBlocking {
        var hitUrl = ""
        val transport = object : com.cometx.browser.ai.HttpTransport {
            override suspend fun postJson(url: String, body: String, headers: Map<String, String>, timeoutMs: Int): Http.Response {
                hitUrl = url
                return Http.Response(200, """{"choices":[{"message":{"content":"pong"}}]}""", null)
            }
            override suspend fun get(url: String, headers: Map<String, String>, timeoutMs: Int): Http.Response {
                return Http.Response(200, """{"object":"list","data":[
                    {"id":"openai/gpt-oss-20b","object":"model","owned_by":"nvidia"},
                    {"id":"google/gemma-3-4b-it","object":"model","owned_by":"google"}]}""", null)
            }
        }
        val p = NvidiaProvider({ "nvapi-x" }, transport)
        val out = p.chat(
            listOf(com.cometx.browser.ai.ChatMessage(role = "user", text = "hi")),
            "openai/gpt-oss-20b"
        )
        assertEquals("pong", p.parseContent(out))
        assertEquals("https://integrate.api.nvidia.com/v1/chat/completions", hitUrl)

        val infos = p.normalizeCatalog("""{"data":[{"id":"openai/gpt-oss-20b","object":"model","owned_by":"nvidia"}]}""")
        assertTrue(infos.first().capabilities.contains(com.cometx.browser.ai.Capability.REASONING))
    }

    @Test fun `nvidia auth failure surfaces as provider exception`() = runBlocking {
        val transport = object : com.cometx.browser.ai.HttpTransport {
            override suspend fun postJson(url: String, body: String, headers: Map<String, String>, timeoutMs: Int): Http.Response {
                return Http.Response(401, """{"status":401,"title":"Unauthorized","detail":"Authentication failed"}""", "")
            }
            override suspend fun get(url: String, headers: Map<String, String>, timeoutMs: Int): Http.Response {
                return Http.Response(401, "", "")
            }
        }
        val p = NvidiaProvider({ "nvapi-x" }, transport)
        try {
            p.chat(listOf(com.cometx.browser.ai.ChatMessage(role = "user", text = "hi")), "openai/gpt-oss-20b")
            throw AssertionError("expected ProviderException")
        } catch (e: ProviderException) {
            assertEquals(401, e.httpCode)
        }
    }

    // ------------------------------------------------------------ key formats (Compose center)

    // Synthetic keys with the same SHAPES as the live-verified ones — never real credentials.
    private val FAKE_NVIDIA = "nvapi-" + "A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q7r8S9t0U1v"
    private val FAKE_OPENROUTER = "sk-or-v1-" + "0f1e2d3c4b5a69788796a5b4c3d2e1f00f1e2d3c4b5a69788796a5b4c3d2e1f0"

    @Test fun `key format warnings are shape hints`() {
        assertTrue(KeyFormat.warning("nvidia", FAKE_NVIDIA) == null)
        assertTrue(KeyFormat.warning("nvidia", "sk-or-v1-abc") != null)
        assertTrue(KeyFormat.warning("openrouter", FAKE_OPENROUTER) == null)
        assertTrue(KeyFormat.warning("openrouter", "sk-or-v1-short") != null)
        assertTrue(KeyFormat.warning("groq", "gsk_" + "aB3dEf7h9jK2mN4pQ6r8sU1vW3x5yZ7a") == null)
        assertTrue(KeyFormat.warning("huggingface", "hf_" + "aB3dEf7h9jK2mN4pQ6r8sU1vW3x5yZ7a") == null)
        assertTrue(KeyFormat.warning("custom", "whatever-key-shape-123") == null)
        assertTrue(KeyFormat.warning("custom", "short") != null)
    }

    @Test fun `mask keeps prefix and tail only`() {
        assertEquals("nvapi-A1••••0U1v", KeyFormat.mask(FAKE_NVIDIA))
        assertEquals("•".repeat(10), KeyFormat.mask("0123456789"))
        assertEquals("", KeyFormat.mask(""))
        val m = KeyFormat.mask(FAKE_OPENROUTER)
        assertTrue(m.length <= 16)
        assertFalse(m.contains("0f1e2d3c4b5a6978"))
    }

    // ------------------------------------------------------------ Discord settings (shared keys with legacy UI)

    @Test fun `discord prefs roundtrip through v2_2_0 accessors`() {
        settings.setDiscordEnabled(true)
        settings.setDiscordChannelId(" 1546327935894421516 ")
        assertTrue(settings.discordEnabled())
        assertEquals("1546327935894421516", settings.discordChannelId())
        // token comes from SecureStore (empty here) → monitor not ready
        val cfg = com.cometx.browser.background.DiscordNotifier.configFrom(settings)
        assertFalse(cfg.ready)
    }

    @Test fun `discord config ready requires all three pieces`() {
        val withToken = object : SettingsRepository(context, SecureStore(context)) {
            override fun discordBotToken(): String? = "tok"
        }
        assertFalse(com.cometx.browser.background.DiscordNotifier.configFrom(withToken).ready)
        withToken.setDiscordEnabled(true)
        withToken.setDiscordChannelId("42")
        assertTrue(com.cometx.browser.background.DiscordNotifier.configFrom(withToken).ready)
    }
}

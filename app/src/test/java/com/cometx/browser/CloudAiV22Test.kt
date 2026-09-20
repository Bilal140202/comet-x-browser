package com.cometx.browser

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cometx.browser.ai.KeyFormat
import com.cometx.browser.ai.LlmProvider
import com.cometx.browser.ai.ModelRouter
import com.cometx.browser.ai.NvidiaNimProvider
import com.cometx.browser.ai.ProviderException
import com.cometx.browser.ai.SettingsRepository
import com.cometx.browser.background.BackgroundAgentStore
import com.cometx.browser.security.SecureStore
import com.cometx.browser.social.DiscordApi
import com.cometx.browser.social.DiscordNotifier
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2.2.0 Cloud AI round: NVIDIA NIM provider, key format/masking utilities and
 * the Discord agent-results push. Everything here is additive: pre-2.2.0 chain
 * behavior must be preserved verbatim when NVIDIA/Discord are unused.
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

    @Test fun `stored v2_1_0 chain gets nvidia appended - no migration needed`() {
        settings.setChainOrder(listOf("groq", "openrouter", "huggingface", "custom"))
        assertEquals(
            listOf("groq", "openrouter", "huggingface", "custom", "nvidia"),
            settings.chainOrder()
        )
    }

    @Test fun `nvidia disabled by default - enabled persists`() {
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

    @Test fun `router chain puts nvidia in cloud section in user order`() {
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

    // ------------------------------------------------------------ NVIDIA provider

    /** Live catalog shape captured 2026-09-20 (subset). */
    private val nimCatalog = """
        {"object":"list","data":[
          {"id":"openai/gpt-oss-20b","object":"model","created":1754947896,"owned_by":"nvidia"},
          {"id":"google/gemma-3-4b-it","object":"model","created":1754947896,"owned_by":"google"},
          {"id":"meta/llama-3.2-11b-vision-instruct","object":"model","created":1754947896,"owned_by":"meta"},
          {"id":"nvidia/nvclip","object":"model","created":1754947896,"owned_by":"nvidia"},
          {"id":"nvidia/riva-translate-4b-instruct","object":"model","created":1754947896,"owned_by":"nvidia"},
          {"id":"nvidia/llama-3.2-nv-embedqa-1b-v1","object":"model","created":1754947896,"owned_by":"nvidia"},
          {"id":"nvidia/nemotron-parse","object":"model","created":1754947896,"owned_by":"nvidia"}
        ]}
    """.trimIndent()

    @Test fun `nim catalog normalization - capabilities and chat gating`() {
        val p = NvidiaNimProvider({ "nvapi-x" })
        val infos = p.normalizeCatalog(nimCatalog)
        assertEquals(7, infos.size)
        val byId = infos.associateBy { it.id }

        assertTrue(byId["openai/gpt-oss-20b"]!!.capabilities.contains(com.cometx.browser.ai.Capability.REASONING))
        assertTrue(byId["meta/llama-3.2-11b-vision-instruct"]!!.capabilities.contains(com.cometx.browser.ai.Capability.VISION))
        assertTrue(byId["google/gemma-3-4b-it"]!!.chatCapable)

        // non-chat endpoints must never drive the agent
        assertFalse(byId["nvidia/nvclip"]!!.chatCapable)
        assertFalse(byId["nvidia/riva-translate-4b-instruct"]!!.chatCapable)
        assertFalse(byId["nvidia/llama-3.2-nv-embedqa-1b-v1"]!!.chatCapable)
        assertFalse(byId["nvidia/nemotron-parse"]!!.chatCapable)
    }

    @Test fun `nim defaults cover every role and are chat-capable`() {
        for (role in ModelRouter.Role.entries) {
            val model = ModelRouter.defaultModelFor("nvidia", role)
            assertTrue("role $role", model.isNotBlank())
            assertTrue("role $role", NvidiaNimProvider.isNimChatCapable(model))
        }
        assertEquals("openai/gpt-oss-20b", ModelRouter.defaultModelFor("nvidia", ModelRouter.Role.AGENT))
    }

    @Test fun `nim chat posts to verified base url and parses content`() = runBlocking {
        var hitUrl = ""
        val transport = object : com.cometx.browser.ai.HttpTransport {
            override suspend fun postJson(url: String, body: String, headers: Map<String, String>, timeoutMs: Int): com.cometx.browser.util.Http.Response {
                hitUrl = url
                return com.cometx.browser.util.Http.Response(200, """{"choices":[{"message":{"content":"pong"}}]}""", null)
            }
            override suspend fun get(url: String, headers: Map<String, String>, timeoutMs: Int): com.cometx.browser.util.Http.Response {
                return com.cometx.browser.util.Http.Response(200, nimCatalog, null)
            }
        }
        val p = NvidiaNimProvider({ "nvapi-x" }, transport)
        val out = p.chat(
            listOf(com.cometx.browser.ai.ChatMessage(role = "user", text = "hi")),
            "openai/gpt-oss-20b"
        )
        assertEquals("pong", p.parseContent(out))
        assertEquals("https://integrate.api.nvidia.com/v1/chat/completions", hitUrl)
    }

    @Test fun `nim errors normalize to provider exceptions`() = runBlocking {
        val transport = object : com.cometx.browser.ai.HttpTransport {
            override suspend fun postJson(url: String, body: String, headers: Map<String, String>, timeoutMs: Int): com.cometx.browser.util.Http.Response {
                return com.cometx.browser.util.Http.Response(401, """{"status":401,"title":"Unauthorized","detail":"Authentication failed"}""", "")
            }
            override suspend fun get(url: String, headers: Map<String, String>, timeoutMs: Int): com.cometx.browser.util.Http.Response {
                return com.cometx.browser.util.Http.Response(401, "", "")
            }
        }
        val p = NvidiaNimProvider({ "nvapi-x" }, transport)
        try {
            p.chat(listOf(com.cometx.browser.ai.ChatMessage(role = "user", text = "hi")), "openai/gpt-oss-20b")
            throw AssertionError("expected ProviderException")
        } catch (e: ProviderException) {
            assertEquals(401, e.httpCode)
        }
    }

    // ------------------------------------------------------------ key formats

    // Synthetic keys with the same SHAPES as the live-verified ones — never real credentials.
    private val FAKE_NVIDIA = "nvapi-" + "A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q7r8S9t0U1v"
    private val FAKE_OPENROUTER = "sk-or-v1-" + "0f1e2d3c4b5a69788796a5b4c3d2e1f00f1e2d3c4b5a69788796a5b4c3d2e1f0"

    @Test fun `key format warnings and masks`() {
        assertNull(KeyFormat.warning("nvidia", FAKE_NVIDIA))
        assertTrue(KeyFormat.warning("nvidia", "sk-or-v1-abc") != null)
        assertNull(KeyFormat.warning("openrouter", FAKE_OPENROUTER))
        assertTrue(KeyFormat.warning("openrouter", "sk-or-v1-short") != null)
        assertNull(KeyFormat.warning("custom", "whatever-key-shape-123"))
        assertTrue(KeyFormat.warning("custom", "short") != null)
    }

    @Test fun `mask keeps prefix and tail only`() {
        assertEquals("nvapi-A1••••0U1v", KeyFormat.mask(FAKE_NVIDIA))
        assertEquals("•".repeat(10), KeyFormat.mask("0123456789"))
        assertEquals("", KeyFormat.mask(""))
        val m = KeyFormat.mask(FAKE_OPENROUTER)
        assertTrue(m.length <= 12 + 4) // head(8) + dots(4) + tail(4)
        assertFalse(m.contains("0f1e2d3c"))
    }

    // ------------------------------------------------------------ Discord

    @Test fun `discord embed payload is well formed and secret-free`() {
        val payload = DiscordApi.buildEmbedPayload(
            "Task completed", "Background agent task bg-abc", DiscordApi.COLOR_COMPLETED,
            listOf("Goal" to "buy milk", "Duration" to "1m 2s")
        )
        val root = JSONObject(payload)
        assertEquals("Comet-X agent update", root.getString("content"))
        val embed = root.getJSONArray("embeds").getJSONObject(0)
        assertEquals("Task completed", embed.getString("title"))
        assertEquals("Background agent task bg-abc", embed.getString("description"))
        assertEquals(DiscordApi.COLOR_COMPLETED, embed.getInt("color"))
        val fields = embed.getJSONArray("fields")
        assertEquals(2, fields.length())
        assertEquals("Goal", fields.getJSONObject(0).getString("name"))
        assertFalse(payload.contains("Bot ")) // no Authorization material ever
    }

    @Test fun `human duration formatting`() {
        assertEquals("—", DiscordApi.humanDuration(0))
        assertEquals("42s", DiscordApi.humanDuration(42_000))
        assertEquals("2m 3s", DiscordApi.humanDuration(123_000))
        assertEquals("1h 1m", DiscordApi.humanDuration(3_660_000))
    }

    @Test fun `discord not configured - notifier is a no-op`() {
        val notifier = DiscordNotifier(settings)
        assertFalse(notifier.configured())
        // both calls must return WITHOUT touching the network (disabled by default)
        val rec = BackgroundAgentStore.TaskRecord(
            id = "bg-test", goal = "g", state = BackgroundAgentStore.STATE_COMPLETED,
            detail = "d", stepUsed = 3, stepBudget = 10,
            startedAtMs = 1_000L, endedAtMs = 61_000L, logs = emptyList()
        )
        notifier.onTaskTerminal(rec)
        assertEquals("Save a bot token first", notifier.sendTest())
    }

    @Test fun `discord non-terminal and cancelled states never push`() {
        val notifier = DiscordNotifier(settings)
        for (state in listOf(
            BackgroundAgentStore.STATE_RUNNING, BackgroundAgentStore.STATE_CANCELLED,
            BackgroundAgentStore.STATE_INTERRUPTED
        )) {
            val rec = BackgroundAgentStore.TaskRecord(
                id = "bg-x", goal = "g", state = state, detail = "d",
                stepUsed = 0, stepBudget = 0, startedAtMs = 0L, endedAtMs = 0L, logs = emptyList()
            )
            notifier.onTaskTerminal(rec) // disabled → immediate no-op, no crash
        }
    }

    // ------------------------------------------------------------ settings roundtrip

    @Test fun `discord prefs roundtrip - channel enabled and token via open hook`() {
        settings.setDiscordEnabled(true)
        settings.setDiscordChannelId(" 1546327935894421516 ")
        assertTrue(settings.discordEnabled())
        assertEquals("1546327935894421516", settings.discordChannelId())
        assertFalse(settings.discordConfigured()) // token still missing
        val withToken = object : SettingsRepository(context, SecureStore(context)) {
            override fun discordToken(): String? = "tok"
        }
        withToken.setDiscordEnabled(true)
        withToken.setDiscordChannelId("42")
        assertTrue(withToken.discordConfigured())
    }

    @Test fun `nvidia base url override applies through applyBaseUrls path`() {
        val p = NvidiaNimProvider({ "nvapi-x" })
        assertNotEquals("https://custom.example/v1", p.effectiveBaseUrl())
        p.setBaseUrl("https://custom.example/v1")
        assertEquals("https://custom.example/v1", p.effectiveBaseUrl())
    }
}

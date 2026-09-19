package com.cometx.browser

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cometx.browser.ai.HttpTransport
import com.cometx.browser.background.BackgroundAgentStore
import com.cometx.browser.background.DiscordNotifier
import com.cometx.browser.security.SecureStore
import com.cometx.browser.util.Http
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2.2.0 — Discord agent monitor: message formatting (DM-4), config gating
 * (DM-1) and wire contract (DM-3) against a scripted transport — no network,
 * no real bot token.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DiscordNotifierTest {

    private lateinit var context: Context

    /** Records the last POST; returns a scripted Discord response. */
    private class FakeTransport : HttpTransport {
        var lastUrl: String? = null
        var lastBody: String? = null
        var lastHeaders: Map<String, String> = emptyMap()
        var response = Http.Response(204, "", null)
        var failWith: Exception? = null

        override suspend fun postJson(
            url: String, body: String, headers: Map<String, String>, timeoutMs: Int
        ): Http.Response {
            failWith?.let { throw it }
            lastUrl = url; lastBody = body; lastHeaders = headers
            return response
        }

        override suspend fun get(
            url: String, headers: Map<String, String>, timeoutMs: Int
        ): Http.Response = Http.Response(404, "", "unexpected get $url")
    }

    private val readyConfig = DiscordNotifier.Config(true, "bot-token-xyz", "123456789")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // touch context/SecureStore so the Robolectric app is fully initialized
        SecureStore(context)
    }

    private fun rec(
        goal: String = "Find the cheapest flight to Delhi",
        stepUsed: Int = 7, stepBudget: Int = 24
    ): BackgroundAgentStore.TaskRecord = BackgroundAgentStore.TaskRecord(
        id = "t1", goal = goal,
        state = BackgroundAgentStore.STATE_RUNNING,
        detail = "working", stepUsed = stepUsed, stepBudget = stepBudget,
        startedAtMs = 0L, endedAtMs = 0L, logs = emptyList()
    )

    // ------------------------------------------------------------ formatting

    @Test fun `started message carries goal`() {
        val body = DiscordNotifier.buildBody(
            DiscordNotifier.Event.STARTED, rec().goal, rec().goal)
        val content = body.getString("content")
        assertTrue(content.contains("Comet-X agent — Task started"))
        assertTrue(content.contains("Goal: Find the cheapest flight to Delhi"))
    }

    @Test fun `step message carries progress`() {
        val body = DiscordNotifier.buildBody(
            DiscordNotifier.Event.STEP, rec().goal, "→ clicked e12", 7, 24)
        val content = body.getString("content")
        assertTrue(content.contains("Progress"))
        assertTrue(content.contains("Step 7/24"))
        assertTrue(content.contains("→ clicked e12"))
    }

    @Test fun `final messages carry step tally`() {
        val ok = DiscordNotifier.buildBody(
            DiscordNotifier.Event.FINISHED_OK, rec().goal, "done", 10, 24).getString("content")
        assertTrue(ok.contains("Task completed"))
        assertTrue(ok.contains("10/24 steps"))
        val fail = DiscordNotifier.buildBody(
            DiscordNotifier.Event.FINISHED_FAIL, rec().goal, "boom", 3, 24).getString("content")
        assertTrue(fail.contains("Task failed"))
    }

    @Test fun `gate message carries detail`() {
        val body = DiscordNotifier.buildBody(
            DiscordNotifier.Event.GATE, rec().goal,
            "buy — exceeds spend limit", 4, 24)
        val content = body.getString("content")
        assertTrue(content.contains("Waiting for you"))
        assertTrue(content.contains("buy — exceeds spend limit"))
    }

    @Test fun `content is capped at the discord-safe limit`() {
        val huge = "x".repeat(5000)
        val content = DiscordNotifier.buildBody(
            DiscordNotifier.Event.STARTED, huge, huge).getString("content")
        assertTrue(content.length <= DiscordNotifier.CONTENT_LIMIT)
    }

    // ----------------------------------------------------------- config gate

    @Test fun `config gate requires all three parts`() {
        assertFalse(DiscordNotifier.Config(false, "t", "c").ready)
        assertFalse(DiscordNotifier.Config(true, null, "c").ready)
        assertFalse(DiscordNotifier.Config(true, "", "c").ready)
        assertFalse(DiscordNotifier.Config(true, "t", null).ready)
        assertFalse(DiscordNotifier.Config(true, "t", "  ").ready)
        assertTrue(DiscordNotifier.Config(true, "t", "c").ready)
    }

    @Test fun `post with unready config sends nothing`() = runBlocking {
        val t = FakeTransport()
        assertNull(DiscordNotifier.post(t, DiscordNotifier.Config(false, "t", "c"), JSONObject().put("content", "x")))
        assertNull(DiscordNotifier.post(t, DiscordNotifier.Config(true, "t", null), JSONObject().put("content", "x")))
        assertNull(t.lastUrl)
    }

    // ---------------------------------------------------------- wire contract

    @Test fun `post hits the channel messages endpoint with bot auth`() = runBlocking {
        val t = FakeTransport()
        t.response = Http.Response(200, """{"id":"m1"}""", null)
        val body = DiscordNotifier.buildBody(DiscordNotifier.Event.TEST, "", "ping")
        val resp = DiscordNotifier.post(t, readyConfig, body)
        assertEquals(200, resp?.code)
        assertEquals(
            DiscordNotifier.API_BASE + "/channels/123456789/messages",
            t.lastUrl
        )
        assertEquals("Bot bot-token-xyz", t.lastHeaders["Authorization"])
        val sentContent = JSONObject(t.lastBody ?: "{}").getString("content")
        assertTrue(sentContent.contains("Test message"))
        assertTrue(sentContent.contains("ping"))
    }

    @Test fun `post propagates discord error codes for the settings UI`() = runBlocking {
        val t = FakeTransport()
        t.response = Http.Response(401, """{"message":"Unauthorized"}""", null)
        val resp = DiscordNotifier.post(t, readyConfig, JSONObject().put("content", "x"))
        assertEquals(401, resp?.code)
        assertFalse(resp?.ok ?: true)
    }
}

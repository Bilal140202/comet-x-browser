package com.cometx.browser

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cometx.browser.ai.ChatMessage
import com.cometx.browser.ai.LlmProvider
import com.cometx.browser.ai.ModelRouter
import com.cometx.browser.engine.AgentEngine
import com.cometx.browser.engine.AgentPrompt
import com.cometx.browser.engine.AgentSink
import com.cometx.browser.engine.SomShot
import com.cometx.browser.automation.ActionExecutor
import com.cometx.browser.memory.MemoryStore
import com.cometx.browser.perception.PageObservation
import com.cometx.browser.perception.VisionPolicy
import com.cometx.browser.security.SecureStore
import com.cometx.browser.ai.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * v1.5.0 OPERATION COMET RELIABILITY — Set-of-Marks + simple-stats integration
 * tests against the REAL engine loop (mirrors AgentLoopIntegrationTest house style).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SomAgentLoopTest {

    private lateinit var context: Context
    private lateinit var settings: SettingsRepository
    private lateinit var memory: MemoryStore

    /** Fake sink that records BOTH screenshot paths and reports marks. */
    private class SomFakeSink(var page: PageObservation, private val marksToReport: Int) : AgentSink {
        val executed = mutableListOf<JSONObject>()
        var annotatedCalls = 0
        var plainCalls = 0

        override suspend fun observe(): PageObservation? = page

        override suspend fun execute(action: JSONObject): ActionExecutor.Result {
            executed.add(action)
            return ActionExecutor.Result(true, "ok: ${action.optString("action")}")
        }

        override suspend fun screenshotBase64(): String? { plainCalls++; return "ZmFrZQ==" }

        override suspend fun screenshotAnnotatedBase64(obs: PageObservation): SomShot? {
            annotatedCalls++
            return SomShot("ZmFrZQ==", marksToReport)
        }

        companion object {
            fun page(url: String, els: List<Triple<String, String, String>>): PageObservation =
                PageObservation(
                    url = url, title = "Test", viewportW = 360, viewportH = 640,
                    scrollY = 0, scrollMax = 0,
                    elements = els.map { (ref, tag, text) ->
                        PageObservation.Element(ref, tag, null, null, null, null, null, text, null, null, 10, 10, 100, 40, false, false)
                    },
                    forms = emptyList(), tabs = listOf(PageObservation.TabInfo(0, "Test", url, true)),
                    activeTabIndex = 0, textSample = "Sample page content for testing."
                )
        }
    }

    private class ScriptedProvider(vararg responses: String) : LlmProvider {
        override val id = "scripted"; override val displayName = "Scripted"
        override val defaultBaseUrl = "unused"
        override fun isReady() = true
        val queue = ArrayDeque(responses.toList())
        val receivedSystemPrompts = mutableListOf<String>()
        val receivedUserPrompts = mutableListOf<String>()
        val receivedUserImages = mutableListOf<String?>()

        override suspend fun chat(messages: List<ChatMessage>, model: String, temperature: Double, maxTokens: Int): String {
            messages.first { it.role == "system" }.let { receivedSystemPrompts.add(it.text ?: "") }
            messages.last { it.role == "user" }.let {
                receivedUserPrompts.add(it.text ?: "")
                receivedUserImages.add(it.imageBase64Jpeg)
            }
            if (queue.isEmpty()) throw IllegalStateException("script exhausted")
            return queue.removeFirst()
        }
    }

    private class StatsListener : AgentEngine.Listener {
        val states = mutableListOf<Pair<AgentEngine.State, String>>()
        val stats = mutableListOf<AgentEngine.RunResult>()
        override fun onStateChanged(state: AgentEngine.State, message: String) { states.add(state to message) }
        override fun onLog(line: String, isError: Boolean) {}
        override fun onConfirmRequired(action: JSONObject, reason: String) {}
        override fun onAskUser(question: String) {}
        override fun onChallengeDetected(detail: String) {}
        override fun onRunStats(stats: AgentEngine.RunResult) { this.stats.add(stats) }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        settings = SettingsRepository(context, SecureStore(context))
        settings.setConfirmHighRisk(true)
        settings.setMemoryEnabled(true)
        settings.setMaxSteps(24)
        settings.setSomOverlay(true)
        memory = MemoryStore(context.filesDir) { settings.memoryEnabled() }
        memory.clearAll()
    }

    private fun awaitState(listener: StatsListener, state: AgentEngine.State, timeoutSec: Long = 20): Boolean {
        val deadline = System.currentTimeMillis() + timeoutSec * 1000
        while (System.currentTimeMillis() < deadline) {
            if (listener.states.any { it.first == state }) return true
            Thread.sleep(50)
        }
        return false
    }

    @Test fun `som on - annotated path taken, rule 12 present, legend gated on pixels`() = runBlocking {
        val sink = SomFakeSink(
            SomFakeSink.page("https://example.com/", listOf(Triple("e14", "button", "Search"))),
            marksToReport = 1
        )
        val provider = ScriptedProvider("""{"action":"done","summary":"ok"}""")
        val router = ModelRouter(settings, mapOf("scripted" to provider))
        val engine = AgentEngine(router, settings, memory, VisionPolicy(settings), sink)
        val listener = StatsListener()
        engine.bind(listener)
        engine.run(CoroutineScope(SupervisorJob() + Dispatchers.Default), "find products", null)

        assertTrue(awaitState(listener, AgentEngine.State.COMPLETED))
        assertEquals(1, sink.annotatedCalls)
        assertEquals(0, sink.plainCalls)
        val sys = provider.receivedSystemPrompts[0]
        assertTrue("rule 12 missing from system prompt", sys.contains("Set-of-Marks"))
        // Scripted model is text-only: §20 drops pixels — the legend must be
        // withheld with them (legend only ever rides with an attached image).
        assertFalse(provider.receivedUserPrompts[0].contains("MARKS:"))
    }

    @Test fun `marks legend and image travel together through stepMessage`() {
        // Plumbing test for the vision-capable path the scripted model can't drive:
        // when pixels are attached, the legend rides in the same message.
        val obs = SomFakeSink.page("https://example.com/", listOf(Triple("e1", "button", "Search")))
        val msg = AgentPrompt.stepMessage(
            obs, emptyList(), "ZmFrZQ==", null, null,
            marksLegend = AgentPrompt.marksLegend(2)
        )
        assertEquals("ZmFrZQ==", msg.imageBase64Jpeg)
        assertTrue(msg.text!!.contains("MARKS:"))
        assertTrue(msg.text!!.contains("count: 2"))
        assertTrue(msg.text!!.contains("Badge N is drawn on the element with ref eN"))
        // No marks → no legend, message stays clean
        val clean = AgentPrompt.stepMessage(obs, emptyList(), "ZmFrZQ==", null, null, marksLegend = AgentPrompt.marksLegend(0))
        assertFalse(clean.text!!.contains("MARKS:"))
    }

    @Test fun `som off - plain screenshot path, no legend, no rule 12`() = runBlocking {
        settings.setSomOverlay(false)
        val sink = SomFakeSink(
            SomFakeSink.page("https://example.com/", listOf(Triple("e1", "button", "Search"))),
            marksToReport = 0
        )
        val provider = ScriptedProvider("""{"action":"done","summary":"ok"}""")
        val router = ModelRouter(settings, mapOf("scripted" to provider))
        val engine = AgentEngine(router, settings, memory, VisionPolicy(settings), sink)
        val listener = StatsListener()
        engine.bind(listener)
        engine.run(CoroutineScope(SupervisorJob() + Dispatchers.Default), "find products", null)

        assertTrue(awaitState(listener, AgentEngine.State.COMPLETED))
        assertEquals(0, sink.annotatedCalls)
        assertEquals(1, sink.plainCalls)
        assertFalse(provider.receivedUserPrompts[0].contains("MARKS:"))
        assertFalse(provider.receivedSystemPrompts[0].contains("Set-of-Marks"))
    }

    @Test fun `run stats emitted exactly once with honest counters`() = runBlocking {
        val sink = SomFakeSink(
            SomFakeSink.page("https://example.com/", listOf(Triple("e1", "button", "Search"))),
            marksToReport = 3
        )
        val provider = ScriptedProvider(
            """{"action":"click","ref":"e1","note":"searching"}""",
            """{"action":"done","summary":"search executed"}"""
        )
        val router = ModelRouter(settings, mapOf("scripted" to provider))
        val engine = AgentEngine(router, settings, memory, VisionPolicy(settings), sink)
        val listener = StatsListener()
        engine.bind(listener)
        engine.run(CoroutineScope(SupervisorJob() + Dispatchers.Default), "search for products", null)

        assertTrue(awaitState(listener, AgentEngine.State.COMPLETED))
        // let any (forbidden) duplicate stats emission surface
        Thread.sleep(200)
        assertEquals("exactly one stats emission per run", 1, listener.stats.size)
        val s = listener.stats[0]
        assertEquals("completed", s.outcome)
        assertEquals(2, s.stepsUsed)
        assertEquals(24, s.stepBudget)
        assertTrue("token estimate must be positive", s.estTokens > 0)
        assertTrue("duration must be non-negative", s.durationMs >= 0)
        assertTrue("screenshot counter must count the capture", s.screenshots >= 1)
    }

    @Test fun `failed run reports outcome failed with steps used`() = runBlocking {
        settings.setMaxSteps(4)
        val sink = SomFakeSink(
            SomFakeSink.page("https://example.com/", listOf(Triple("e1", "button", "OK"))),
            marksToReport = 1
        )
        val provider = object : LlmProvider {
            override val id = "wait"; override val displayName = "wait"; override val defaultBaseUrl = "x"
            override fun isReady() = true
            override suspend fun chat(messages: List<ChatMessage>, model: String, temperature: Double, maxTokens: Int) =
                """{"action":"wait","ms":100,"note":"waiting"}"""
        }
        val router = ModelRouter(settings, mapOf("wait" to provider))
        val engine = AgentEngine(router, settings, memory, VisionPolicy(settings), sink)
        val listener = StatsListener()
        engine.bind(listener)
        engine.run(CoroutineScope(SupervisorJob() + Dispatchers.Default), "never-ending task", null)

        assertTrue(awaitState(listener, AgentEngine.State.FAILED))
        assertEquals(1, listener.stats.size)
        assertEquals("failed", listener.stats[0].outcome)
        // wait actions SUCCEED, so the adaptive budget extends (Phase 3):
        // steps consumed keep growing until the 3-extension ceiling bites.
        assertTrue("stepsUsed ${listener.stats[0].stepsUsed} must exceed the initial budget",
            listener.stats[0].stepsUsed >= 4)
        assertTrue("budget must have auto-extended",
            listener.stats[0].stepBudget > 4)
    }

    @Test fun `zero marks degrade to plain path without legend`() = runBlocking {
        // annotation path returns 0 marks (e.g. nothing drawable) → engine
        // falls back to the reported base64 WITHOUT a second capture and
        // emits no legend.
        val sink = SomFakeSink(
            SomFakeSink.page("https://example.com/", listOf(Triple("e1", "button", "Search"))),
            marksToReport = 0
        )
        val provider = ScriptedProvider("""{"action":"done","summary":"ok"}""")
        val router = ModelRouter(settings, mapOf("scripted" to provider))
        val engine = AgentEngine(router, settings, memory, VisionPolicy(settings), sink)
        val listener = StatsListener()
        engine.bind(listener)
        engine.run(CoroutineScope(SupervisorJob() + Dispatchers.Default), "find products", null)

        assertTrue(awaitState(listener, AgentEngine.State.COMPLETED))
        assertEquals("no second capture expected", 0, sink.plainCalls)
        assertEquals(1, sink.annotatedCalls)
        assertFalse(provider.receivedUserPrompts[0].contains("MARKS:"))
    }
}

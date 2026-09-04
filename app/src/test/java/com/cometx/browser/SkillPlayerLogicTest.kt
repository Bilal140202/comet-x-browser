package com.cometx.browser

import com.cometx.browser.automation.ActionExecutor
import com.cometx.browser.engine.AgentSink
import com.cometx.browser.perception.PageObservation
import com.cometx.browser.skills.RecordedSkill
import com.cometx.browser.skills.SkillPlayer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3 — player logic that is testable without a WebView: step
 * descriptions (what the user sees in the log / review dialogs) and the
 * implicit start-page gate against a scripted sink.
 */
class SkillPlayerLogicTest {

    private class FakeSink(var resultOk: Boolean = true, var url: String = "https://site.example/home") : AgentSink {
        val executed = mutableListOf<JSONObject>()
        override suspend fun observe(): PageObservation? = PageObservation(
            url = url, title = "Fake", viewportW = 360, viewportH = 640,
            scrollY = 0, scrollMax = 0, elements = emptyList(), forms = emptyList(),
            tabs = emptyList(), activeTabIndex = 0, textSample = ""
        )
        override suspend fun execute(action: JSONObject): ActionExecutor.Result {
            executed.add(action)
            return ActionExecutor.Result(resultOk, if (resultOk) "ok" else "boom")
        }
        override suspend fun screenshotBase64(): String? = null
    }

    private class FakeListener : SkillPlayer.Listener {
        val logs = mutableListOf<String>()
        override fun onStepStarted(index: Int, total: Int, description: String) { logs.add("start $index: $description") }
        override fun onStepResult(index: Int, ok: Boolean, message: String) { logs.add("result $index ok=$ok") }
        override fun onFinished(success: Boolean, summary: String) { logs.add("finished ok=$success") }
        override suspend fun askSensitiveValue(fieldDescription: String): String? = "typed-in"
        override suspend fun confirmStep(message: String): Boolean = true
    }

    private fun makePlayer(sink: FakeSink, listener: FakeListener): SkillPlayer = SkillPlayer(
        sink, webViewProvider = { null }, router = null,
        aiFallbackEnabled = { false }, confirmHighRisk = { true }, listener = listener
    )

    // ------------------------------------------------------- descriptions

    @Test fun `descriptions cover every playable verb`() {
        val p = makePlayer(FakeSink(), FakeListener())
        assertTrue(p.describeStep(RecordedSkill.Step(action = "navigate", url = "https://x.example/long-url-string-that-should-truncate")).startsWith("Open https://x.example"))
        assertTrue(p.describeStep(RecordedSkill.Step(action = "click", target = RecordedSkill.Target(text = "Buy now"))).contains("Buy now"))
        assertTrue(p.describeStep(RecordedSkill.Step(action = "type", target = RecordedSkill.Target(name = "q"), text = "hello world")).contains("hello world"))
        assertTrue(p.describeStep(RecordedSkill.Step(action = "type", target = RecordedSkill.Target(name = "pw"), text = "", sensitive = true)).contains("private"))
        assertTrue(p.describeStep(RecordedSkill.Step(action = "select", target = RecordedSkill.Target(name = "s"), option = "Option A")).contains("Option A"))
        assertEquals("Scroll down", p.describeStep(RecordedSkill.Step(action = "scroll", direction = "down")))
        assertEquals("Wait 2000ms", p.describeStep(RecordedSkill.Step(action = "wait", ms = 2000)))
        assertEquals("Go back", p.describeStep(RecordedSkill.Step(action = "back")))
    }

    @Test fun `description falls back through target attributes`() {
        val p = makePlayer(FakeSink(), FakeListener())
        // no text → aria → placeholder → name → tag
        assertEquals("Click btn", p.describeStep(RecordedSkill.Step(action = "click", target = RecordedSkill.Target(tag = "btn"))))
        assertEquals("Click the label", p.describeStep(RecordedSkill.Step(action = "click", target = RecordedSkill.Target(ariaLabel = "the label"))))
        assertEquals("Click element", p.describeStep(RecordedSkill.Step(action = "click", target = null)))
    }

    // --------------------------------------------------------- replay gate

    @Test fun `skill with no steps completes trivially`() = runBlocking {
        val sink = FakeSink()
        val listener = FakeListener()
        val report = makePlayer(sink, listener).run(
            RecordedSkill(id = "x", name = "empty", description = "", startUrl = "", steps = emptyList())
        )
        assertTrue(report.ok)
        assertEquals(0, report.ranSteps)
        assertTrue(listener.logs.last().startsWith("finished ok=true"))
    }

    @Test fun `failed action stops the run with a report`() = runBlocking {
        val sink = FakeSink(resultOk = false)
        val listener = FakeListener()
        val report = makePlayer(sink, listener).run(
            RecordedSkill(
                id = "x", name = "nav-first", description = "", startUrl = "",
                steps = listOf(RecordedSkill.Step(action = "wait", ms = 100))
            )
        )
        assertFalse(report.ok)
        assertEquals(1, report.ranSteps)
        assertEquals(1, report.failures.size)
    }

    @Test fun `unreachable start url fails fast before any step`() = runBlocking {
        val sink = FakeSink(resultOk = false, url = "https://elsewhere.example/")
        val listener = FakeListener()
        val report = makePlayer(sink, listener).run(
            RecordedSkill(
                id = "x", name = "gated", description = "", startUrl = "https://portal.example/app",
                steps = listOf(RecordedSkill.Step(action = "wait", ms = 100))
            )
        )
        assertFalse(report.ok)
        assertEquals(0, report.ranSteps)
        assertTrue(report.failures.first().contains("start page"))
    }

    @Test fun `same-host start url does not inject a navigation`() = runBlocking {
        val sink = FakeSink(url = "https://site.example/home")
        val listener = FakeListener()
        makePlayer(sink, listener).run(
            RecordedSkill(
                id = "x", name = "noop", description = "", startUrl = "https://site.example/other-page",
                steps = listOf(RecordedSkill.Step(action = "wait", ms = 50))
            )
        )
        // Same host → the start gate is skipped; the wait step executes
        assertTrue(sink.executed.any { it.optString("action") == "wait" })
        assertFalse(sink.executed.any { it.optString("action") == "navigate" })
    }

    @Test fun `different-host start url injects the navigation first`() = runBlocking {
        val sink = FakeSink(url = "https://elsewhere.example/")
        makePlayer(sink, FakeListener()).run(
            RecordedSkill(
                id = "x", name = "gated2", description = "", startUrl = "https://portal.example/app",
                steps = listOf(RecordedSkill.Step(action = "wait", ms = 50))
            )
        )
        assertEquals("navigate", sink.executed.first().optString("action"))
        assertEquals("https://portal.example/app", sink.executed.first().optString("url"))
    }

    @Test fun `non-http start url fails the gate — replay never launches it`() = runBlocking {
        val sink = FakeSink()
        val report = makePlayer(sink, FakeListener()).run(
            RecordedSkill(
                id = "x", name = "evil", description = "", startUrl = "javascript:alert(1)",
                steps = emptyList()
            )
        )
        // start URL is non-blank but non-http → gate attempts, hygiene rejects → run aborts
        assertFalse(sink.executed.any { it.optString("action") == "navigate" })
        assertFalse(report.ok)
        assertTrue(report.failures.first().contains("start page"))
    }
}

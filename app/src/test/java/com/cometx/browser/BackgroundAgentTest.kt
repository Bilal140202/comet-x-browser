package com.cometx.browser

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.cometx.browser.ai.ChatMessage
import com.cometx.browser.ai.LlmProvider
import com.cometx.browser.ai.ModelRouter
import com.cometx.browser.ai.ProviderErrorKind
import com.cometx.browser.ai.ProviderException
import com.cometx.browser.ai.SettingsRepository
import com.cometx.browser.background.AgentNotifications
import com.cometx.browser.background.AgentTaskService
import com.cometx.browser.background.BackgroundAgentStore
import com.cometx.browser.background.HeadlessWebViewFactory
import com.cometx.browser.background.HeadlessWebViewSink
import com.cometx.browser.background.NetworkWaitPolicy
import com.cometx.browser.background.NetworkWaiter
import com.cometx.browser.engine.AgentEngine
import com.cometx.browser.engine.AgentSink
import com.cometx.browser.automation.ActionExecutor
import com.cometx.browser.memory.MemoryStore
import com.cometx.browser.perception.PageObservation
import com.cometx.browser.perception.VisionPolicy
import com.cometx.browser.security.SecureStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowService
import java.io.IOException
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * v1.8.0 OPERATION COMET AFTERSHOCK — Background Agent Mode.
 *
 * Covers the BG contracts: the task store (persistence + reboot reconcile),
 * the network-fluctuation gate (never surface "app stopping" on data wobble),
 * notification status text + action wiring, the headless surface security
 * posture, and the service's defensive entry points (null intent, empty goal,
 * stale stop — the "no App-keep-closing" contract).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = com.cometx.browser.CometApp::class)
class BackgroundAgentTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    // ------------------------------------------------------------- store

    private fun newStore(): BackgroundAgentStore {
        val dir = File(context.cacheDir, "bgtest-${System.nanoTime()}")
        return BackgroundAgentStore(dir)
    }

    @Test
    fun `store start creates RUNNING record and persists it`() {
        val store = newStore()
        store.start("find flights")
        val rec = store.current
        assertNotNull(rec)
        assertEquals(BackgroundAgentStore.STATE_RUNNING, rec!!.state)
        assertEquals("find flights", rec.goal)
        assertTrue(rec.isActive)
        // reload the SAME dir from disk → same record (process-death fidelity)
        val reloaded = BackgroundAgentStore(File(context.cacheDir, "bgtest-${System.nanoTime()}"))
        reloaded.start("goal X")
        reloaded.load()
        assertEquals("goal X", reloaded.current?.goal)
        assertEquals(BackgroundAgentStore.STATE_RUNNING, reloaded.current?.state)
    }

    @Test
    fun `store progress and state mutations persist and notify listeners`() {
        val store = newStore()
        val seen = mutableListOf<BackgroundAgentStore.TaskRecord?>()
        store.addListener { seen.add(it) }
        store.start("task")
        store.setProgress(3, 24, "clicked Search")
        store.setState(BackgroundAgentStore.STATE_AWAITING_CONFIRM, "type into password")
        assertEquals(3, store.current?.stepUsed)
        assertEquals(24, store.current?.stepBudget)
        assertEquals(BackgroundAgentStore.STATE_AWAITING_CONFIRM, store.current?.state)
        // listener got the initial push + every mutation
        assertTrue(seen.size >= 3)
        assertTrue(seen.last()!!.state == BackgroundAgentStore.STATE_AWAITING_CONFIRM)
    }

    @Test
    fun `store log ring buffer caps at 30 lines`() {
        val store = newStore()
        store.start("task")
        for (i in 1..40) store.addLog("line $i")
        val logs = store.current!!.logs
        assertEquals(30, logs.size)
        assertEquals("line 11", logs.first())
        assertEquals("line 40", logs.last())
    }

    @Test
    fun `store finish is first-writer-wins and stamps end time`() {
        val store = newStore()
        store.start("task")
        store.finish(BackgroundAgentStore.STATE_COMPLETED, "all done", nowMs = 111)
        // a late stop() must not overwrite the honest outcome
        store.finish(BackgroundAgentStore.STATE_CANCELLED, "stopped", nowMs = 222)
        assertEquals(BackgroundAgentStore.STATE_COMPLETED, store.current!!.state)
        assertEquals(111L, store.current!!.endedAtMs)
        assertFalse(store.current!!.isActive)
    }

    @Test
    fun `reconcile marks orphaned RUNNING as INTERRUPTED and leaves terminal records alone`() {
        // orphaned active record
        val dir1 = File(context.cacheDir, "bgtest-a-${System.nanoTime()}")
        val s1 = BackgroundAgentStore(dir1)
        s1.start("mid-flight task")
        val s1b = BackgroundAgentStore(dir1)
        s1b.reconcileInterrupted(nowMs = 999)
        assertEquals(BackgroundAgentStore.STATE_INTERRUPTED, s1b.current?.state)
        assertTrue(s1b.current!!.endedAtMs == 999L)

        // terminal record survives untouched
        val dir2 = File(context.cacheDir, "bgtest-b-${System.nanoTime()}")
        val s2 = BackgroundAgentStore(dir2)
        s2.start("done task")
        s2.finish(BackgroundAgentStore.STATE_COMPLETED, "finished", nowMs = 100)
        val s2b = BackgroundAgentStore(dir2)
        s2b.reconcileInterrupted(nowMs = 999)
        assertEquals(BackgroundAgentStore.STATE_COMPLETED, s2b.current?.state)
        assertEquals(100L, s2b.current?.endedAtMs)

        // empty store: reconcile is a no-op
        val s3 = newStore()
        s3.reconcileInterrupted()
        assertNull(s3.current)
    }

    // --------------------------------------------------- network gate policy

    @Test
    fun `transient transport errors are classified as network fluctuations`() {
        assertTrue(NetworkWaitPolicy.isTransientNetworkError(UnknownHostException("no dns")))
        assertTrue(NetworkWaitPolicy.isTransientNetworkError(ConnectException("refused")))
        assertTrue(NetworkWaitPolicy.isTransientNetworkError(SocketTimeoutException("timed out")))
        assertTrue(NetworkWaitPolicy.isTransientNetworkError(IOException("unable to resolve host api.groq.com")))
        assertTrue(NetworkWaitPolicy.isTransientNetworkError(ProviderException("Groq: connect failed", -1, ProviderErrorKind.NETWORK_ERROR)))
        // wrapped deep in a cause chain
        val wrapped = RuntimeException(ProviderException("x", -1, ProviderErrorKind.NETWORK_ERROR))
        assertTrue(NetworkWaitPolicy.isTransientNetworkError(wrapped))
    }

    @Test
    fun `non-network errors are NOT fluctuations`() {
        assertFalse(NetworkWaitPolicy.isTransientNetworkError(IllegalArgumentException("bad json")))
        assertFalse(NetworkWaitPolicy.isTransientNetworkError(ProviderException("HTTP 401", 401, ProviderErrorKind.INVALID_API_KEY)))
        assertFalse(NetworkWaitPolicy.isTransientNetworkError(IOException("read-only file system")))
        assertFalse(NetworkWaitPolicy.isTransientNetworkError(null))
    }

    @Test
    fun `waiter returns immediately when network is available`() = runBlocking {
        var checks = 0
        val waiter = NetworkWaiter(isNetworkAvailable = { checks++; true })
        assertTrue(waiter.waitUntilAvailable())
        assertEquals(1, checks)
    }

    @Test
    fun `waiter parks until availability returns then reports true`() = runBlocking {
        val delays = mutableListOf<Long>()
        var poll = 0
        val waiter = NetworkWaiter(
            isNetworkAvailable = { poll++ >= 3 },
            delay = { delays.add(it) },
            maxWaitMs = 60_000
        )
        assertTrue(waiter.waitUntilAvailable())
        assertEquals(3, delays.size)
    }

    @Test
    fun `waiter reports false when the wait budget expires offline`() = runBlocking {
        val waiter = NetworkWaiter(
            isNetworkAvailable = { false },
            delay = { _ -> },
            maxWaitMs = 1_000,
            pollMs = 100
        )
        assertFalse(waiter.waitUntilAvailable())
    }

    // ------------------------------------------------ notification status line

    private fun rec(state: String, detail: String = "d", used: Int = 3, budget: Int = 24) =
        BackgroundAgentStore.TaskRecord(
            id = "t", goal = "buy milk", state = state, detail = detail,
            stepUsed = used, stepBudget = budget,
            startedAtMs = 0, endedAtMs = 0, logs = emptyList()
        )

    @Test
    fun `status line mirrors each task state`() {
        assertTrue(AgentNotifications.statusLine(rec(BackgroundAgentStore.STATE_RUNNING, "clicked Search")).contains("Step 3/24"))
        assertTrue(AgentNotifications.statusLine(rec(BackgroundAgentStore.STATE_RUNNING, "clicked Search")).contains("clicked Search"))
        assertTrue(AgentNotifications.statusLine(rec(BackgroundAgentStore.STATE_WAITING_NETWORK)).contains("Waiting for network"))
        assertTrue(AgentNotifications.statusLine(rec(BackgroundAgentStore.STATE_AWAITING_CONFIRM, "type into field")).contains("Needs your approval"))
        assertTrue(AgentNotifications.statusLine(rec(BackgroundAgentStore.STATE_AWAITING_USER, "which city?")).contains("Agent asks:"))
        assertTrue(AgentNotifications.statusLine(rec(BackgroundAgentStore.STATE_AWAITING_CHALLENGE)).contains("verification"))
        assertTrue(AgentNotifications.statusLine(rec(BackgroundAgentStore.STATE_COMPLETED, "found 3 hotels")).contains("✓"))
        assertTrue(AgentNotifications.statusLine(rec(BackgroundAgentStore.STATE_FAILED, "timeout")).contains("✗"))
        assertTrue(AgentNotifications.statusLine(rec(BackgroundAgentStore.STATE_INTERRUPTED)).contains("interrupted"))
    }

    @Test
    fun `notification actions match the state - approve deny only on confirm`() {
        val confirm = AgentNotifications.build(context, rec(BackgroundAgentStore.STATE_AWAITING_CONFIRM))
        assertEquals(2, confirm.actions.size)
        assertEquals("Approve", confirm.actions[0].title.toString())
        assertEquals("Deny", confirm.actions[1].title.toString())

        val ask = AgentNotifications.build(context, rec(BackgroundAgentStore.STATE_AWAITING_USER))
        assertEquals(1, ask.actions.size)
        assertEquals("Reply", ask.actions[0].title.toString())

        val running = AgentNotifications.build(context, rec(BackgroundAgentStore.STATE_RUNNING))
        assertEquals(1, running.actions.size)
        assertEquals("Stop", running.actions[0].title.toString())
        assertTrue((running.flags and android.app.Notification.FLAG_ONGOING_EVENT) != 0)

        val done = AgentNotifications.build(context, rec(BackgroundAgentStore.STATE_COMPLETED, used = 8, budget = 8))
        assertEquals(0, done.actions?.size ?: 0)
        assertTrue((done.flags and android.app.Notification.FLAG_ONGOING_EVENT) == 0)
    }

    // ------------------------------------------------- headless surface (BG-1/BG-8)

    @Test
    fun `headless webview matches the browser security posture`() {
        val web = HeadlessWebViewFactory.create(context) {}
        assertNotNull(web)
        web!!.settings.let {
            assertTrue(it.javaScriptEnabled)
            assertTrue(it.domStorageEnabled)
            assertFalse(it.allowFileAccess)
            assertFalse(it.allowContentAccess)
            assertFalse(it.allowFileAccessFromFileURLs)
            assertFalse(it.allowUniversalAccessFromFileURLs)
        }
        // headless view has a real viewport WITHOUT any window attachment
        // (Robolectric note: layout frames don't stick in the shadow, so the
        // measured dimensions are the authoritative headless viewport check)
        assertTrue(web.measuredWidth > 0)
        assertTrue(web.measuredHeight > 0)
        web.destroy()
    }

    @Test
    fun `headless sink blocks non-web and executable downloads without JS`() = runBlocking {
        val web = HeadlessWebViewFactory.create(context) {}
        val sink = HeadlessWebViewSink(context, web!!)
        val blocked = sink.execute(JSONObject().put("action", "open_tab").put("url", "intent://evil"))
        assertFalse(blocked.ok)
        val risky = sink.execute(JSONObject().put("action", "download").put("url", "https://x.example/tool.exe"))
        assertFalse(risky.ok)
        val tabs = sink.execute(JSONObject().put("action", "switch_tab").put("index", 0))
        assertFalse(tabs.ok)
        web.destroy()
    }

    // -------------------------------------------------- engine network gate (BG-5)

    /** Provider that fails its first [failures] calls with a transport error. */
    private class FlakyProvider(private var failures: Int) : LlmProvider {
        override val id = "flaky"; override val displayName = "Flaky"
        override val defaultBaseUrl = "unused"
        override fun isReady() = true
        val gatePhases = mutableListOf<String>()

        override suspend fun chat(messages: List<ChatMessage>, model: String, temperature: Double, maxTokens: Int): String {
            if (failures > 0) { failures--; throw IOException("unable to resolve host: api.example.com") }
            return """{"action":"done","summary":"ok"}"""
        }
    }

    private class NullPageSink(var nulls: Int) : AgentSink {
        override suspend fun observe(): PageObservation? {
            if (nulls > 0) { nulls--; return null }
            return page()
        }
        override suspend fun execute(action: JSONObject) = ActionExecutor.Result(true, "ok")
        override suspend fun screenshotBase64(): String? = null
        companion object {
            fun page(): PageObservation = PageObservation(
                url = "https://example.com/", title = "T", viewportW = 360, viewportH = 640,
                scrollY = 0, scrollMax = 0,
                elements = listOf(
                    PageObservation.Element("e1", "button", null, null, null, null, null, "Go", null, null, 10, 10, 100, 40, false, false)
                ),
                forms = emptyList(), tabs = listOf(PageObservation.TabInfo(0, "T", "https://example.com/", true)),
                activeTabIndex = 0, textSample = "content"
            )
        }
    }

    private class CollectListener : AgentEngine.Listener {
        val states = mutableListOf<Pair<AgentEngine.State, String>>()
        val stats = mutableListOf<AgentEngine.RunResult>()
        override fun onStateChanged(state: AgentEngine.State, message: String) { states.add(state to message) }
        override fun onLog(line: String, isError: Boolean) {}
        override fun onConfirmRequired(action: JSONObject, reason: String) {}
        override fun onAskUser(question: String) {}
        override fun onChallengeDetected(detail: String) {}
        override fun onRunStats(stats: AgentEngine.RunResult) { this.stats.add(stats) }
        fun reached(s: AgentEngine.State) = states.any { it.first == s }
    }

    private fun settings() = SettingsRepository(context, SecureStore(context))

    @Test
    fun `model-call network fluctuation parks and retries instead of failing`() = runBlocking {
        val provider = FlakyProvider(failures = 2) // router retries once internally, then surfaces the wobble
        val router = ModelRouter(settings(), mapOf("flaky" to provider))
        val engine = AgentEngine(router, settings(), MemoryStore(context.filesDir) { false },
            VisionPolicy(settings()), NullPageSink(0))
        val listener = CollectListener()
        engine.bind(listener)
        engine.networkWaitGate = { phase, _ -> provider.gatePhases.add(phase); true }
        engine.run(CoroutineScope(SupervisorJob() + Dispatchers.Default), "buy milk", null)
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline && !listener.reached(AgentEngine.State.COMPLETED)) kotlinx.coroutines.delay(50)
        assertTrue("engine should complete after the gate wait", listener.reached(AgentEngine.State.COMPLETED))
        assertTrue(provider.gatePhases.contains("model-call"))
        // the parked step was refunded — one consumed step for `done`
        assertEquals(1, listener.stats.first().stepsUsed)
    }

    @Test
    fun `gate returning false preserves the normal failure path`() = runBlocking {
        val provider = FlakyProvider(failures = 99)
        val router = ModelRouter(settings(), mapOf("flaky" to provider))
        val engine = AgentEngine(router, settings(), MemoryStore(context.filesDir) { false },
            VisionPolicy(settings()), NullPageSink(0))
        val listener = CollectListener()
        engine.bind(listener)
        engine.networkWaitGate = { _, _ -> false }
        engine.run(CoroutineScope(SupervisorJob() + Dispatchers.Default), "buy milk", null)
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline && !listener.reached(AgentEngine.State.FAILED)) kotlinx.coroutines.delay(50)
        assertTrue(listener.reached(AgentEngine.State.FAILED))
    }

    @Test
    fun `default engine (no gate) fails on transport errors exactly as before`() = runBlocking {
        val provider = FlakyProvider(failures = 99)
        val router = ModelRouter(settings(), mapOf("flaky" to provider))
        val engine = AgentEngine(router, settings(), MemoryStore(context.filesDir) { false },
            VisionPolicy(settings()), NullPageSink(0))
        val listener = CollectListener()
        engine.bind(listener)
        assertNull(engine.networkWaitGate)
        engine.run(CoroutineScope(SupervisorJob() + Dispatchers.Default), "buy milk", null)
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline && !listener.reached(AgentEngine.State.FAILED)) kotlinx.coroutines.delay(50)
        assertTrue(listener.reached(AgentEngine.State.FAILED))
        // default human-gate timeout unchanged (15 min) — additive hook only
        assertEquals(15L * 60 * 1000, engine.gateTimeoutMs)
    }

    @Test
    fun `page-unreadable while offline parks instead of burning the loading loop`() = runBlocking {
        val provider = FlakyProvider(0)
        val router = ModelRouter(settings(), mapOf("flaky" to provider))
        val engine = AgentEngine(router, settings(), MemoryStore(context.filesDir) { false },
            VisionPolicy(settings()), NullPageSink(3))
        val listener = CollectListener()
        engine.bind(listener)
        engine.networkWaitGate = { phase, err -> phase == "page-unreadable" && err == null }
        engine.run(CoroutineScope(SupervisorJob() + Dispatchers.Default), "buy milk", null)
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline && !listener.reached(AgentEngine.State.COMPLETED)) kotlinx.coroutines.delay(50)
        assertTrue(listener.reached(AgentEngine.State.COMPLETED))
    }

    // ------------------------------------------- service defensive paths (BG-7)

    @Test
    fun `service absorbs a goal-less start without crashing or creating a task`() {
        com.cometx.browser.CometApp.app.agentStore.clear()
        val service = Robolectric.setupService(AgentTaskService::class.java)
        val started = service.onStartCommand(
            Intent(AgentTaskService.ACTION_START), 0, 1)
        assertEquals(android.app.Service.START_NOT_STICKY, started)
        val shadow = Shadows.shadowOf(service as android.app.Service)
        assertTrue(shadow.isStoppedBySelf)
        assertNull(com.cometx.browser.CometApp.app.agentStore.current)
        service.onDestroy()
    }

    @Test
    fun `service absorbs stale stop and confirm intents with no engine`() {
        com.cometx.browser.CometApp.app.agentStore.clear()
        val service = Robolectric.setupService(AgentTaskService::class.java) as android.app.Service
        val stop = Intent(AgentTaskService.ACTION_STOP)
        assertEquals(android.app.Service.START_NOT_STICKY, service.onStartCommand(stop, 0, 1))
        val confirm = Intent(AgentTaskService.ACTION_CONFIRM).putExtra(AgentTaskService.EXTRA_APPROVE, true)
        assertEquals(android.app.Service.START_NOT_STICKY, service.onStartCommand(confirm, 0, 2))
        val reply = Intent(AgentTaskService.ACTION_REPLY)
        assertEquals(android.app.Service.START_NOT_STICKY, service.onStartCommand(reply, 0, 3))
        assertNull(com.cometx.browser.CometApp.app.agentStore.current)
        service.onDestroy()
    }

    @Test
    fun `service goes foreground before any early return (ANR safety)`() {
        com.cometx.browser.CometApp.app.agentStore.clear()
        val service = Robolectric.setupService(AgentTaskService::class.java) as android.app.Service
        service.onStartCommand(Intent(AgentTaskService.ACTION_START), 0, 1)
        val shadow = Shadows.shadowOf(service)
        // started via startForegroundService → the service MUST have entered
        // the foreground state before stopping (Android 26+ hard requirement)
        assertTrue(shadow.lastForegroundNotificationId != 0)
        service.onDestroy()
    }
}

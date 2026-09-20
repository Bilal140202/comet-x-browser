package com.cometx.browser.background

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.app.RemoteInput
import com.cometx.browser.ai.ModelCatalog
import com.cometx.browser.ai.ModelRouter
import com.cometx.browser.ai.ProviderSet
import com.cometx.browser.ai.SettingsRepository
import com.cometx.browser.CometApp
import com.cometx.browser.engine.AgentEngine
import com.cometx.browser.memory.MemoryStore
import com.cometx.browser.perception.VisionPolicy
import com.cometx.browser.security.SecureStore
import com.cometx.browser.skills.SkillRegistry
import com.cometx.browser.util.Logx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject

/**
 * AgentTaskService (v1.8.0) — runs ONE agent task headlessly in a foreground
 * service while the user does their job (or nothing at all):
 *
 *   user taps "Run in background" → startForegroundService → the service owns
 *   an ISOLATED WebView (never in any view hierarchy — the user's tabs, the
 *   omnibox and the panels are structurally untouched) and its own AgentEngine.
 *
 * Monitoring lives entirely in the notification bar (AgentNotifications):
 * step-by-step progress, Approve/Deny for high-risk actions, direct Reply for
 * ask_user, "tap to take control" for challenges, completion summary.
 *
 * The "never show App-keep-closing" contract (BG-7..BG-9):
 *  - START_NOT_STICKY: the system never resurrects the service with a null
 *    goal; a null-intent start is absorbed and the service exits quietly
 *  - every entry point (onStartCommand, engine callbacks, network callbacks,
 *    onDestroy) is wrapped — a failure degrades the TASK's notification,
 *    never the process
 *  - no boot receiver: after a reboot nothing of ours runs, so nothing can
 *    crash-loop; the persisted task record is reconciled to INTERRUPTED at
 *    the next app start (BackgroundAgentStore.reconcileInterrupted)
 *  - network fluctuations park the engine in a wait gate (never a failure)
 *  - a headless renderer crash fails the TASK gracefully (factory hook)
 */
class AgentTaskService : Service(), AgentEngine.Listener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var engine: AgentEngine? = null
    private var headlessWeb: android.webkit.WebView? = null
    private var settings: SettingsRepository? = null
    private var waiter: NetworkWaiter? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastNotifyAt = 0L

    // ------------------------------------------------------------- lifecycle

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // BG-7: system restarts / malformed starts must never crash — absorb
        // and exit quietly (START_NOT_STICKY means this is already rare).
        return try {
            when (intent?.action) {
                ACTION_STOP -> stopTask("stopped from the notification")
                ACTION_CONFIRM -> {
                    engine?.confirm(intent.getBooleanExtra(EXTRA_APPROVE, false))
                    store()?.setState(
                        BackgroundAgentStore.STATE_RUNNING,
                        "resumed — action " + (if (intent.getBooleanExtra(EXTRA_APPROVE, false)) "approved" else "denied")
                    )
                }
                ACTION_REPLY -> {
                    val answer = RemoteInput.getResultsFromIntent(intent)
                        ?.getCharSequence(AgentNotifications.KEY_REPLY_TEXT)?.toString()
                    engine?.resume(answer?.takeIf { it.isNotBlank() })
                    store()?.setState(BackgroundAgentStore.STATE_RUNNING, "resumed — answered from notification")
                }
                else -> startTask(intent?.getStringExtra(EXTRA_GOAL).orEmpty())
            }
            START_NOT_STICKY
        } catch (t: Throwable) {
            Logx.e("bg-agent: onStartCommand failed: ${t.message}")
            runCatching { store()?.finish(BackgroundAgentStore.STATE_FAILED, "Internal error: ${t.message?.take(80)}") }
            stopSelf()
            START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        runCatching {
            networkCallback?.let { cb ->
                (getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager)
                    ?.unregisterNetworkCallback(cb)
            }
        }
        networkCallback = null
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        val web = headlessWeb
        headlessWeb = null
        web?.let { w ->
            runCatching {
                android.os.Handler(android.os.Looper.getMainLooper()).post { runCatching { w.destroy() } }
            }
        }
        // Process teardown while a task was live (system kill / swipe-from-
        // recents): leave an honest record. Reboot reconciliation happens at
        // the next app start — no receiver, nothing runs at boot (BG-7).
        runCatching {
            store()?.current?.let { rec ->
                if (rec.isActive) {
                    CometApp.app.agentStore.finish(
                        BackgroundAgentStore.STATE_INTERRUPTED,
                        "Task stopped while running (system freed resources). Re-run it from the agent panel."
                    )
                    postFinal(CometApp.app.agentStore.current ?: rec)
                }
            }
        }
        runCatching { scope.cancel() }
        super.onDestroy()
    }

    // ------------------------------------------------------------- task start

    private fun startTask(rawGoal: String) {
        // BG-7 ANR safety: any startForegroundService launch MUST reach
        // startForeground within ~5s — do it unconditionally, first thing,
        // before any early return.
        goForeground()
        val goal = rawGoal.trim()
        if (goal.isEmpty()) {
            // launched without a goal (e.g. after process death) — nothing to do
            stopSelf()
            return
        }
        val store = CometApp.app.agentStore
        if (store.current?.isActive == true && engine != null) {
            // one task at a time — surface the live one instead of duplicating
            postNotificationNow()
            return
        }

        // -- infrastructure (same construction as the foreground path) --------
        val st = SettingsRepository(this, SecureStore(this))
        settings = st
        val memory = MemoryStore(filesDir) { st.memoryEnabled() }
        val providers = ProviderSet.build(st)
        ProviderSet.applyBaseUrls(st, providers)
        val router = ModelRouter(st, providers, ModelCatalog(this))

        // -- isolated browser surface ------------------------------------------
        val web = HeadlessWebViewFactory.create(this) { reason ->
            // renderer gone → fail the TASK, never the app (BG-8)
            runCatching {
                engine?.stop()
                store.finish(BackgroundAgentStore.STATE_FAILED, "$reason — task stopped")
                postFinal(store.current ?: return@runCatching)
                stopSelf()
            }
        }
        if (web == null) {
            store.start(goal)
            store.finish(BackgroundAgentStore.STATE_FAILED, "Could not create the isolated browser surface")
            store.current?.let { postFinal(it) }
            stopSelf()
            return
        }
        headlessWeb = web

        // -- engine -------------------------------------------------------------
        val eng = AgentEngine(router, st, memory, VisionPolicy(st), HeadlessWebViewSink(this, web))
        engine = eng
        eng.bind(this)
        // Background-specific knobs (additive engine hooks; UI paths keep the
        // defaults → foreground behavior is byte-identical):
        eng.gateTimeoutMs = GATE_TIMEOUT_MS          // notification replies may take a while
        eng.networkWaitGate = { phase, error -> handleNetworkGate(phase, error) }

        // -- go foreground is already done (top of this method); refresh the
        //    shade with the fresh RUNNING record -------------------------------
        store.start(goal)
        postNotificationNow()

        // -- keep the CPU alive so deep sleep never stalls the task ------------
        acquireWakeLock()

        // -- connectivity watching: only for fast "waiting for network" UX ----
        registerNetworkWatch()

        val skill = try { SkillRegistry(this).match(goal) } catch (_: Exception) { null }
        eng.run(scope, goal, skill)
        Logx.i("bg-agent: task started (${goal.take(60)})")
    }

    // --------------------------------------------------------- network gate

    /**
     * The engine's additive hook: invoked when a model step or a page read
     * fails. Decision table (BG-5):
     *   transient transport error          → wait for connectivity, retry
     *   other error BUT network is down    → wait for connectivity, retry
     *   other error AND network is fine    → false (engine fails as usual)
     * A null error means "page never became readable" — wait only if the
     * network is actually down, otherwise let the loading-loop logic run.
     */
    private suspend fun handleNetworkGate(phase: String, error: Exception?): Boolean {
        val w = waiter ?: NetworkWaiter(
            isNetworkAvailable = { isNetworkUp() }
        ).also { waiter = it }
        val transient = error == null || NetworkWaitPolicy.isTransientNetworkError(error)
        if (!transient && isNetworkUp()) return false
        if (!isNetworkUp()) {
            CometApp.app.agentStore.setState(
                BackgroundAgentStore.STATE_WAITING_NETWORK,
                "network lost — the task continues automatically"
            )
            postNotificationNow()   // the shade must show "waiting for network" NOW
            Logx.i("bg-agent: network gate engaged ($phase: ${error?.message ?: "page unreadable"})")
        }
        val ok = w.waitUntilAvailable()
        if (ok) {
            CometApp.app.agentStore.setState(
                BackgroundAgentStore.STATE_RUNNING,
                "resumed after network wait"
            )
            postNotificationNow()
        }
        return ok
    }

    private fun isNetworkUp(): Boolean {
        return try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            true // cannot tell → never block on our own probe
        }
    }

    private fun registerNetworkWatch() {
        if (networkCallback != null) return
        runCatching {
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    // the engine's wait loop polls every 2s; nothing to resume
                    // here — keep this callback strictly observational
                }
            }
            (getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager)
                ?.registerDefaultNetworkCallback(cb)
            networkCallback = cb
        }
    }

    // --------------------------------------------------- engine listener

    override fun onStateChanged(state: AgentEngine.State, message: String) {
        val store = store() ?: return
        val mapped = when (state) {
            AgentEngine.State.RUNNING -> BackgroundAgentStore.STATE_RUNNING
            AgentEngine.State.AWAITING_CONFIRM -> BackgroundAgentStore.STATE_AWAITING_CONFIRM
            AgentEngine.State.AWAITING_USER -> BackgroundAgentStore.STATE_AWAITING_USER
            AgentEngine.State.COMPLETED -> BackgroundAgentStore.STATE_COMPLETED
            AgentEngine.State.FAILED -> BackgroundAgentStore.STATE_FAILED
            AgentEngine.State.CANCELLED -> BackgroundAgentStore.STATE_CANCELLED
            AgentEngine.State.IDLE -> BackgroundAgentStore.STATE_RUNNING
        }
        if (mapped == BackgroundAgentStore.STATE_COMPLETED ||
            mapped == BackgroundAgentStore.STATE_FAILED ||
            mapped == BackgroundAgentStore.STATE_CANCELLED
        ) {
            store.finish(mapped, message)
            postFinal(store.current ?: return)
            // v2.2.0: optional Discord push (no-op unless the user enabled it;
            // runs on its own daemon thread — never blocks the terminal path)
            com.cometx.browser.social.DiscordBridge.onTaskTerminal(
                applicationContext, store.current
            )
            releaseWakeLock()
            scheduleStop()
            return
        }
        store.setState(mapped, message)
        // A challenge arrives as onChallengeDetected (rich detail) followed by
        // AWAITING_USER/"verification required" — keep the challenge identity
        // and its detail so the notification shows "tap to take control".
        if (state == AgentEngine.State.AWAITING_USER &&
            message.contains("verification", ignoreCase = true)
        ) {
            store.update { it.copy(state = BackgroundAgentStore.STATE_AWAITING_CHALLENGE) }
            postNotification(force = true)
            return
        }
        // gate states MUST hit the shade immediately (action buttons appear)
        postNotification(force = mapped != BackgroundAgentStore.STATE_RUNNING)
    }

    override fun onLog(line: String, isError: Boolean) {
        val store = store() ?: return
        store.addLog(line)
        renewWakeLock()
        // "— Step 3/24" (and "3/24 (+2⤴)") drives the progress bar
        val m = Regex("Step\\s*(\\d+)/(\\d+)").find(line)
        if (m != null) {
            val used = m.groupValues[1].toIntOrNull() ?: return
            val budget = m.groupValues[2].toIntOrNull() ?: return
            store.setProgress(used, budget, lastActionLine(store))
        }
        postNotification()
    }

    override fun onConfirmRequired(action: JSONObject, reason: String) {
        // state change already routed via onStateChanged; enrich the detail
        store()?.setState(
            BackgroundAgentStore.STATE_AWAITING_CONFIRM,
            "${action.optString("action")} — $reason"
        )
        postNotification(force = true)
    }

    override fun onAskUser(question: String) {
        store()?.setState(BackgroundAgentStore.STATE_AWAITING_USER, question)
        postNotification(force = true)
    }

    override fun onChallengeDetected(detail: String) {
        store()?.setState(BackgroundAgentStore.STATE_AWAITING_CHALLENGE, detail)
        postNotification(force = true)
    }

    override fun onRunStats(stats: AgentEngine.RunResult) {
        store()?.addLog(
            "Σ ${stats.stepsUsed}/${stats.stepBudget} steps · ${stats.outcome}"
        )
    }

    /** Last "→ <action result>" line: the most user-meaningful step detail. */
    private fun lastActionLine(store: BackgroundAgentStore): String =
        store.current?.logs?.lastOrNull { it.startsWith("→ ") || it.startsWith("✗ ") }
            ?: store.current?.detail?.takeIf { it.isNotBlank() }
            ?: "working…"

    // ------------------------------------------------------------- control

    private fun stopTask(reason: String) {
        runCatching { engine?.stop() }
        val store = store() ?: return
        store.finish(BackgroundAgentStore.STATE_CANCELLED, reason)
        store.current?.let { postFinal(it) }
        releaseWakeLock()
        scheduleStop()
    }

    // -------------------------------------------------------- notification

    private fun store(): BackgroundAgentStore? =
        runCatching { CometApp.app.agentStore }.getOrNull()

    private fun goForeground() {
        val rec = store()?.current
        val notification = rec?.let {
            runCatching { AgentNotifications.build(this, it) }.getOrNull()
        } ?: silentFallbackNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            runCatching {
                startForeground(
                    AgentNotifications.NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
                return
            }
        }
        startForeground(AgentNotifications.NOTIFICATION_ID, notification)
    }

    private fun silentFallbackNotification(): Notification =
        AgentNotifications.build(
            this,
            BackgroundAgentStore.TaskRecord(
                id = "boot", goal = "Background task", state = BackgroundAgentStore.STATE_RUNNING,
                detail = "starting…", stepUsed = 0, stepBudget = 0,
                startedAtMs = System.currentTimeMillis(), endedAtMs = 0L, logs = emptyList()
            )
        )

    /** Progress updates are throttled; gate/completion updates never are. */
    private fun postNotification(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotifyAt < NOTIFY_THROTTLE_MS) return
        lastNotifyAt = now
        postNotificationNow()
    }

    private fun postNotificationNow() {
        val rec = store()?.current ?: return
        runCatching {
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.notify(AgentNotifications.NOTIFICATION_ID, AgentNotifications.build(this, rec))
        }.onFailure { Logx.w("bg-agent: notify failed: ${it.message}") }
    }

    /** Terminal summary stays in the shade after the service is gone. */
    private fun postFinal(rec: BackgroundAgentStore.TaskRecord) {
        runCatching {
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.notify(AgentNotifications.NOTIFICATION_ID, AgentNotifications.build(this, rec))
        }
    }

    private fun scheduleStop() {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
            { runCatching { stopForeground(STOP_FOREGROUND_DETACH); stopSelf() } },
            250
        )
    }

    // ------------------------------------------------------------- wake lock

    private fun acquireWakeLock() {
        runCatching {
            val pm = getSystemService(POWER_SERVICE) as? PowerManager ?: return
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cometx:agent").apply {
                setReferenceCounted(false)
                acquire(WAKELOCK_TIMEOUT_MS)
            }
        }
    }

    private fun renewWakeLock() {
        runCatching {
            wakeLock?.takeIf { it.isHeld }?.acquire(WAKELOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    companion object {
        const val ACTION_START = "cometx.agent.START"
        const val ACTION_STOP = "cometx.agent.STOP"
        const val ACTION_CONFIRM = "cometx.agent.CONFIRM"
        const val ACTION_REPLY = "cometx.agent.REPLY"
        const val EXTRA_GOAL = "cometx.agent.GOAL"
        const val EXTRA_APPROVE = "cometx.agent.APPROVE"

        const val NOTIFY_THROTTLE_MS = 800L
        const val WAKELOCK_TIMEOUT_MS = 5L * 60 * 1000

        /** Notification replies can arrive long after the prompt (BG contract). */
        const val GATE_TIMEOUT_MS = 45L * 60 * 1000

        /**
         * Launch a background agent task. Callers must hold foreground state
         * (the agent panel is on screen) so the FGS start is always legal.
         */
        fun start(context: Context, goal: String) {
            val it = Intent(context, AgentTaskService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_GOAL, goal)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, it)
        }

        fun stop(context: Context) {
            val it = Intent(context, AgentTaskService::class.java).apply { action = ACTION_STOP }
            runCatching { context.startService(it) }
                .onFailure { Logx.w("bg-agent: stop delivery failed (service down?)") }
        }
    }
}

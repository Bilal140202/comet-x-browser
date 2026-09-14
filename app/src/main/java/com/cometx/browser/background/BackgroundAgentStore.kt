package com.cometx.browser.background

import com.cometx.browser.util.Logx
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * BackgroundAgentStore (v1.8.0) — single source of truth for the ONE active
 * background agent task, shared by the foreground service, the notifications
 * and the in-app agent panel (monitoring view).
 *
 * Design notes:
 *  - one task at a time (the service refuses duplicates) — predictable for the
 *    user, and the notification shade stays clean
 *  - every mutation is persisted to disk (atomic tmp+rename) BEFORE listeners
 *    fire, so process death / reboot always leaves an accurate record behind
 *  - [reconcileInterrupted] runs at app start: a record still in an active
 *    state means the process died (reboot, system kill, swipe-from-recents)
 *    while a task ran. It is marked INTERRUPTED — never silently resumed,
 *    never a crash, never a dialog.
 *  - pure state machine + org.json only → Robolectric-testable
 */
class BackgroundAgentStore(private val persistDir: File) {

    data class TaskRecord(
        val id: String,
        val goal: String,
        val state: String,
        /** Step text / question / confirm reason / failure reason — one line. */
        val detail: String,
        val stepUsed: Int,
        val stepBudget: Int,
        val startedAtMs: Long,
        val endedAtMs: Long,
        /** Last ~30 log lines for the in-app monitoring view. */
        val logs: List<String>
    ) {
        val isActive: Boolean get() = ACTIVE_STATES.contains(state)
    }

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(TaskRecord?) -> Unit>()

    @Volatile
    var current: TaskRecord? = null
        private set

    // ------------------------------------------------------------- mutations

    fun start(goal: String, nowMs: Long = System.currentTimeMillis()): TaskRecord {
        val rec = TaskRecord(
            id = "bg-" + java.util.UUID.randomUUID().toString().take(8),
            goal = goal,
            state = STATE_RUNNING,
            detail = "starting…",
            stepUsed = 0,
            stepBudget = 0,
            startedAtMs = nowMs,
            endedAtMs = 0L,
            logs = mutableListOf("✦ Background task: $goal")
        )
        current = rec
        persistAndNotify()
        return rec
    }

    /** Apply a mutation to the current record (no-op when nothing is active/known). */
    fun update(mutate: (TaskRecord) -> TaskRecord) {
        val cur = current ?: return
        val next = mutate(cur)
        if (next == cur) return
        current = next
        persistAndNotify()
    }

    fun setState(state: String, detail: String) = update { it.copy(state = state, detail = detail) }

    fun setProgress(stepUsed: Int, stepBudget: Int, detail: String) =
        update { it.copy(stepUsed = stepUsed, stepBudget = stepBudget, detail = detail) }

    fun addLog(line: String) = update {
        val logs = (it.logs + line).takeLast(MAX_LOG_LINES)
        it.copy(logs = logs)
    }

    /**
     * Terminal transition with stamping. Safe to call repeatedly (stop() after
     * completion, onDestroy racing a FAILED transition) — first writer wins.
     */
    fun finish(state: String, detail: String, nowMs: Long = System.currentTimeMillis()) {
        val cur = current ?: return
        if (!cur.isActive && cur.endedAtMs != 0L) return
        current = cur.copy(state = state, detail = detail, endedAtMs = nowMs)
        persistAndNotify()
    }

    fun clear() {
        current = null
        persistAndNotify()
    }

    // ------------------------------------------------------------- listeners

    fun addListener(listener: (TaskRecord?) -> Unit) {
        listeners.add(listener)
        listener(current)
    }

    fun removeListener(listener: (TaskRecord?) -> Unit) {
        listeners.remove(listener)
    }

    private fun persistAndNotify() {
        runCatching { persist() }
            .onFailure { Logx.w("bg-agent: persist failed: ${it.message}") }
        val snapshot = current
        for (l in listeners) runCatching { l(snapshot) }
    }

    // ------------------------------------------------------------ persistence

    private fun stateFile(): File = File(persistDir.apply { mkdirs() }, FILE_NAME)

    private fun persist() {
        val cur = current ?: run {
            stateFile().delete(); return
        }
        val root = JSONObject()
            .put("id", cur.id)
            .put("goal", cur.goal)
            .put("state", cur.state)
            .put("detail", cur.detail)
            .put("stepUsed", cur.stepUsed)
            .put("stepBudget", cur.stepBudget)
            .put("startedAtMs", cur.startedAtMs)
            .put("endedAtMs", cur.endedAtMs)
            .put("logs", JSONArray().apply { for (l in cur.logs) put(l) })
        val tmp = File(stateFile().absolutePath + ".tmp")
        tmp.writeText(root.toString())
        if (!tmp.renameTo(stateFile())) {
            stateFile().delete()
            tmp.renameTo(stateFile())
        }
    }

    /** Rebuild in-memory state from disk (service restart within same process boot). */
    fun load() {
        runCatching {
            val f = stateFile()
            if (!f.exists()) return
            val root = JSONObject(f.readText())
            current = TaskRecord(
                id = root.optString("id"),
                goal = root.optString("goal"),
                state = root.optString("state"),
                detail = root.optString("detail"),
                stepUsed = root.optInt("stepUsed"),
                stepBudget = root.optInt("stepBudget"),
                startedAtMs = root.optLong("startedAtMs"),
                endedAtMs = root.optLong("endedAtMs"),
                logs = root.optJSONArray("logs")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optString(it) }
                } ?: emptyList()
            )
        }.onFailure { Logx.w("bg-agent: load failed: ${it.message}") }
    }

    /**
     * App-start reconciliation: any record still in an ACTIVE state was
     * orphaned by process death (reboot / system kill). Mark it INTERRUPTED —
     * the user can re-run the task from the panel; nothing auto-starts at boot.
     */
    fun reconcileInterrupted(nowMs: Long = System.currentTimeMillis()) {
        load()
        val cur = current ?: return
        if (cur.isActive) {
            finish(
                STATE_INTERRUPTED,
                "Task was interrupted (device restarted or system stopped the app). Re-run it from the agent panel.",
                nowMs
            )
        }
    }

    companion object {
        const val FILE_NAME = "background_agent_state.json"
        const val MAX_LOG_LINES = 30

        const val STATE_RUNNING = "RUNNING"
        const val STATE_WAITING_NETWORK = "WAITING_NETWORK"
        const val STATE_AWAITING_CONFIRM = "AWAITING_CONFIRM"
        const val STATE_AWAITING_USER = "AWAITING_USER"
        const val STATE_AWAITING_CHALLENGE = "AWAITING_CHALLENGE"
        const val STATE_COMPLETED = "COMPLETED"
        const val STATE_FAILED = "FAILED"
        const val STATE_CANCELLED = "CANCELLED"
        const val STATE_INTERRUPTED = "INTERRUPTED"

        /** States that mean "a live engine may still be driving somewhere". */
        val ACTIVE_STATES = setOf(
            STATE_RUNNING, STATE_WAITING_NETWORK, STATE_AWAITING_CONFIRM,
            STATE_AWAITING_USER, STATE_AWAITING_CHALLENGE
        )
    }
}

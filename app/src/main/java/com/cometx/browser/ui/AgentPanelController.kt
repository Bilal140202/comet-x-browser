package com.cometx.browser.ui

import android.animation.ValueAnimator
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.database.ContentObserver
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.cometx.browser.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.cometx.browser.ai.ModelRouter
import com.cometx.browser.ai.SettingsRepository
import com.cometx.browser.engine.AgentEngine
import com.cometx.browser.security.SafetyPolicy
import com.cometx.browser.skills.RecordedSkill
import com.cometx.browser.skills.SkillInterview
import com.cometx.browser.skills.SkillPlayer
import com.cometx.browser.skills.SkillRecorder
import com.cometx.browser.skills.SkillRegistry
import com.cometx.browser.skills.UserSkillStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.resume

/** M3 emphasized easing (0.2, 0, 0, 1) — motion system §5. */
private val EMPHASIZED = PathInterpolator(0.2f, 0f, 0f, 1f)
private val ACCELERATE = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)

/**
 * AgentPanelController — the agent UI: goal input, skills, live log, control
 * buttons, confirmation dialogs, ask-user answers, challenge banner sync.
 * Implements AgentEngine.Listener (called from the engine coroutine).
 *
 * Phase 3 additions:
 *  - Skill recorder controls (record → stop → review → save)
 *  - "Your skills" chips: tap to replay, long-press for details/edit/export/delete
 *  - /grill-me interview mode with draft review-edit-iterate loop
 */
class AgentPanelController(
    private val activity: Activity,
    private val engine: AgentEngine,
    private val settings: SettingsRepository,
    private val skills: SkillRegistry,
    private val recorder: SkillRecorder,
    private val userStore: UserSkillStore,
    private val interview: SkillInterview,
    private val playerFactory: () -> SkillPlayer,
    private val webViewProvider: () -> android.webkit.WebView?,
    private val currentUrlProvider: () -> String
) : AgentEngine.Listener {

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var panel: LinearLayout
    private var askBar: LinearLayout
    private var askBarText: TextView
    private var statusDot: View
    private var statusText: TextView
    private var stepText: TextView
    private var goalInput: EditText
    private var skillChips: LinearLayout
    private var userSkillChips: LinearLayout
    private var userSkillsScroll: HorizontalScrollView
    private var userSkillsCaption: TextView
    private var btnRun: Button
    private var btnRecord: Button
    private var btnGrillMe: Button
    private var btnTakeControl: Button
    private var btnResume: Button
    private var btnStop: Button
    private var answerRow: LinearLayout
    private var answerInput: EditText
    private var logList: ListView
    private var statsText: TextView
    private val logLines = mutableListOf<Pair<String, Boolean>>()
    private var logAdapter: CometLogAdapter? = null
    private var selectedSkillId: String? = null

    // ---- motion system state ----
    private var pulse: ValueAnimator? = null
    private val motionObserver = object : ContentObserver(null) {
        override fun onChange(selfChange: Boolean) {
            if (!animationsOn()) stopPulse()
        }
    }

    /** True while /grill-me owns the answer bar. */
    private var interviewActive = false

    init {
        panel = activity.findViewById(R.id.agentPanel)
        askBar = activity.findViewById(R.id.askBar)
        askBarText = activity.findViewById(R.id.askBarText)
        statusDot = activity.findViewById(R.id.statusDot)
        statusText = activity.findViewById(R.id.statusText)
        stepText = activity.findViewById(R.id.stepText)
        goalInput = activity.findViewById(R.id.goalInput)
        skillChips = activity.findViewById(R.id.skillChips)
        userSkillChips = activity.findViewById(R.id.userSkillChips)
        userSkillsScroll = activity.findViewById(R.id.userSkillsScroll)
        userSkillsCaption = activity.findViewById(R.id.userSkillsCaption)
        btnRun = activity.findViewById(R.id.btnRun)
        btnRecord = activity.findViewById(R.id.btnRecord)
        btnGrillMe = activity.findViewById(R.id.btnGrillMe)
        btnTakeControl = activity.findViewById(R.id.btnTakeControl)
        btnResume = activity.findViewById(R.id.btnResume)
        btnStop = activity.findViewById(R.id.btnStop)
        answerRow = activity.findViewById(R.id.answerRow)
        answerInput = activity.findViewById(R.id.answerInput)
        logList = activity.findViewById(R.id.logList)
        statsText = activity.findViewById(R.id.statsText)

        logAdapter = CometLogAdapter(activity, R.layout.item_log, R.id.logLine, logLines.map { it.first }.toMutableList())
        logList.adapter = logAdapter

        activity.findViewById<Button>(R.id.btnOpenAgent).setOnClickListener { expand() }
        activity.findViewById<Button>(R.id.btnPanelClose).setOnClickListener { collapse() }
        askBar.setOnClickListener { expand() }

        btnRun.setOnClickListener { runFromInput() }
        btnGrillMe.setOnClickListener { startGrillMe("") }
        btnRecord.setOnClickListener { onRecordButton() }
        btnTakeControl.setOnClickListener {
            engine.takeControl("you have control")
            Toast.makeText(activity, "You have control. Resume when ready.", Toast.LENGTH_LONG).show()
        }
        btnResume.setOnClickListener { engine.resume(if (answerInput.text.isNotBlank()) answerInput.text.toString() else null) }
        btnStop.setOnClickListener {
            if (interviewActive) {
                interview.cancel()
                interviewActive = false
                log("■ Interview ended", isError = false)
                refreshButtons()
            } else {
                engine.stop()
            }
        }
        activity.findViewById<Button>(R.id.btnAnswerSend).setOnClickListener {
            val a = answerInput.text.toString()
            answerInput.setText("")
            if (interviewActive) {
                answerRow.visibility = View.GONE
                interview.onUserAnswer(a)
                interview.pump(uiScope)
            } else {
                answerRow.visibility = View.GONE
                engine.resume(a.ifBlank { null })
            }
        }

        buildSkillChips()
        refreshUserSkillChips()

        // cold-start: the engine emits nothing until the first event — show IDLE
        applyAgentState(AgentEngine.State.IDLE, "")

        // reduced-motion: live-cancel the status pulse when the user disables
        // system animations (infinite animator at duration-scale 0 = frame spin)
        activity.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, motionObserver
        )
    }

    /** Called from MainActivity.onDestroy — releases the motion ContentObserver. */
    fun dispose() {
        try { activity.contentResolver.unregisterContentObserver(motionObserver) } catch (_: Exception) {}
        stopPulse()
    }

    // --------------------------------------------------- motion system (§5)

    private fun animationsOn(): Boolean =
        try {
            Settings.Global.getFloat(
                activity.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f
            ) > 0f
        } catch (_: Exception) {
            true
        }

    private fun startPulse() {
        if (pulse != null || !animationsOn()) return
        pulse = ValueAnimator.ofFloat(1f, 0.45f, 1f).apply {
            duration = 1200
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { animator -> statusDot.alpha = animator.animatedValue as Float }
            start()
        }
    }

    private fun stopPulse() {
        pulse?.cancel()
        pulse = null
        statusDot.alpha = 1f
    }

    // last applied status — re-applied on expand() so a RUNNING run keeps
    // its chip + pulse after the panel is reopened
    private var lastDotColor: Int? = null
    private var lastLabel: String? = null
    private var lastPulse: Boolean = false

    private fun dp(n: Int): Int = (n * activity.resources.displayMetrics.density).toInt()

    fun isVisible(): Boolean = panel.visibility == View.VISIBLE

    /**
     * State-first expand/collapse: visibility flips synchronously (AppSmokeTest
     * asserts immediately after click), then the enter transition plays.
     * Panel exit is intentionally not animated — the ask-bar fade-in carries it.
     */
    fun expand() {
        panel.visibility = View.VISIBLE
        askBar.visibility = View.GONE
        // re-apply last known status so pulse/chip survive collapse→expand
        if (lastLabel != null && lastDotColor != null) {
            applyStatus(lastDotColor!!, lastLabel!!, lastPulse)
        }
        val from = if (panel.height > 0) panel.height * 0.35f else 120f
        panel.translationY = from
        panel.alpha = 0f
        panel.animate().translationY(0f).alpha(1f)
            .setDuration(280).setInterpolator(EMPHASIZED)
            .setListener(null).start()
    }

    fun collapse() {
        panel.visibility = View.GONE
        stopPulse()
        askBar.visibility = View.VISIBLE
        askBar.alpha = 0f
        askBar.animate().alpha(1f)
            .setDuration(200).setInterpolator(ACCELERATE)
            .setListener(null).start()
    }

    // ------------------------------------------------------- declarative chips

    private fun buildSkillChips() {
        skillChips.removeAllViews()
        val margin = (8 * activity.resources.displayMetrics.density).toInt()
        for (skill in skills.skills) {
            val chip = TextView(activity)
            chip.text = "${skill.icon} ${skill.name}"
            chip.setPadding(dp(12), dp(6), dp(12), dp(6))
            chip.textSize = 12f
            chip.setBackgroundResource(R.drawable.bg_chip)
            chip.setTextColor(activity.getColor(R.color.text_primary))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = margin
            chip.layoutParams = lp
            chip.setOnClickListener {
                selectedSkillId = if (selectedSkillId == skill.id) null else skill.id
                highlightChips()
            }
            chip.tag = skill.id
            skillChips.addView(chip)
        }
        highlightChips()
    }

    private fun highlightChips() {
        for (i in 0 until skillChips.childCount) {
            val chip = skillChips.getChildAt(i) as TextView
            val active = chip.tag == selectedSkillId
            chip.isSelected = active // drives the bg_chip selected state
            chip.setTextColor(
                activity.getColor(if (active) R.color.on_primary_container else R.color.text_primary)
            )
        }
    }

    // ---------------------------------------------------------- user skills

    fun refreshUserSkillChips() {
        val list = userStore.list()
        activity.runOnUiThread {
            userSkillChips.removeAllViews()
            val margin = (8 * activity.resources.displayMetrics.density).toInt()
            val visible = list.isNotEmpty()
            userSkillsScroll.visibility = if (visible) View.VISIBLE else View.GONE
            userSkillsCaption.visibility = if (visible) View.VISIBLE else View.GONE
            for (skill in list) {
                val chip = TextView(activity)
                chip.text = "▶ ${skill.name} (${skill.steps.size})"
                chip.setPadding(dp(12), dp(6), dp(12), dp(6))
                chip.textSize = 12f
                chip.setBackgroundResource(R.drawable.bg_chip)
                chip.setTextColor(activity.getColor(R.color.accent_bright))
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.marginEnd = margin
                chip.layoutParams = lp
                chip.setOnClickListener { showUserSkillMenu(skill) }
                chip.setOnLongClickListener { showUserSkillMenu(skill); true }
                userSkillChips.addView(chip)
            }
        }
    }

    private fun showUserSkillMenu(skill: RecordedSkill) {
        val options = arrayOf("Run now", "Details", "Edit JSON", "Export", "Delete")
        MaterialAlertDialogBuilder(activity)
            .setTitle(skill.summaryLine())
            .setItems(options) { _, which ->
                when (which) {
                    0 -> runUserSkill(skill)
                    1 -> showUserSkillDetails(skill)
                    2 -> editUserSkillJson(skill)
                    3 -> exportUserSkill(skill)
                    4 -> confirmDeleteUserSkill(skill)
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun runUserSkill(skill: RecordedSkill) {
        if (engine.state == AgentEngine.State.RUNNING) {
            Toast.makeText(activity, "Agent is running — stop it first", Toast.LENGTH_SHORT).show()
            return
        }
        if (recorder.state == SkillRecorder.State.RECORDING) {
            Toast.makeText(activity, "Stop recording first", Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle("Run skill")
            .setMessage("${skill.summaryLine()}\n\nSensitive fields (if any) will be asked for; high-risk steps still require confirmation.")
            .setPositiveButton("Run") { _, _ ->
                uiScope.launch {
                    expand()
                    log("▶ Replaying '${skill.name}' (${skill.steps.size} steps)")
                    val report = playerFactory().run(skill)
                    if (report.ok) userStore.markRun(skill.id)
                    log(if (report.ok) "✓ Replay finished" else "✗ Replay stopped", isError = !report.ok)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showUserSkillDetails(skill: RecordedSkill) {
        val sb = StringBuilder()
        sb.appendLine(skill.description.ifBlank { "(no description)" })
        if (skill.startUrl.isNotBlank()) sb.appendLine("Starts at: ${skill.startUrl}")
        sb.appendLine()
        skill.steps.forEachIndexed { i, s ->
            sb.appendLine("${i + 1}. ${playerStepDescription(s)}")
        }
        if (skill.verification.isNotBlank()) { sb.appendLine(); sb.appendLine("Success check: ${skill.verification}") }
        MaterialAlertDialogBuilder(activity)
            .setTitle(skill.name)
            .setMessage(sb.toString())
            .setPositiveButton("OK", null)
            .show()
    }

    private fun playerStepDescription(s: RecordedSkill.Step): String {
        // Keep descriptions consistent with the player's own wording.
        return playerFactory().describeStep(s)
    }

    private fun editUserSkillJson(skill: RecordedSkill) {
        val input = EditText(activity)
        input.setText(skill.toJson().toString(2))
        input.textSize = 11f
        input.setSingleLine(false)
        input.minLines = 6
        val scroll = android.widget.ScrollView(activity)
        scroll.addView(input)
        MaterialAlertDialogBuilder(activity)
            .setTitle("Edit skill JSON")
            .setView(scroll)
            .setPositiveButton("Save") { _, _ ->
                val saved = userStore.saveFromJsonText(skill.id, input.text.toString())
                if (saved != null) {
                    refreshUserSkillChips()
                    Toast.makeText(activity, "Skill updated", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(activity, "Invalid JSON — not saved", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun exportUserSkill(skill: RecordedSkill) {
        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("cometx-skill", skill.toJson().toString(2)))
        Toast.makeText(activity, "Skill JSON copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun confirmDeleteUserSkill(skill: RecordedSkill) {
        MaterialAlertDialogBuilder(activity)
            .setTitle("Delete skill?")
            .setMessage("'${skill.name}' will be removed from this device.")
            .setPositiveButton("Delete") { _, _ ->
                userStore.delete(skill.id)
                refreshUserSkillChips()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------- recorder

    private fun onRecordButton() {
        if (recorder.state == SkillRecorder.State.RECORDING) {
            uiScope.launch { stopRecordingFlow() }
        } else {
            MaterialAlertDialogBuilder(activity)
                .setTitle("Record a skill")
                .setMessage(
                    "Perform the task by hand now — your clicks, typing, selections and scrolling are captured step by step.\n\n" +
                        "• Passwords / payment / OTP fields are NEVER stored — you'll type them at replay.\n" +
                        "• You'll review everything before it's saved."
                )
                .setPositiveButton("Start recording") { _, _ ->
                    val web = webViewProvider()
                    if (web == null) {
                        Toast.makeText(activity, "Open a page first", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    recorder.start(web, currentUrlProvider())
                    expand()
                    log("⏺ Recording started — use the browser normally; press Stop & Save when done.")
                    refreshButtons()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private suspend fun stopRecordingFlow() {
        val nameInput = EditText(activity)
        nameInput.hint = "Skill name"
        nameInput.setSingleLine(true)
        val descInput = EditText(activity)
        descInput.hint = "What does it do? (optional)"
        descInput.setSingleLine(true)
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(nameInput)
            addView(descInput)
        }
        val name = suspendCancellableCoroutine { cont ->
            MaterialAlertDialogBuilder(activity)
                .setTitle("Save recorded skill")
                .setView(box)
                .setPositiveButton("Review") { _, _ -> cont.resume(nameInput.text.toString().ifBlank { "" }) }
                .setNegativeButton("Discard") { _, _ -> cont.resume(null) }
                .setOnCancelListener { cont.resume(null) }
                .show()
        }
        if (name == null) {
            recorder.cancel()
            log("⏺ Recording discarded")
            refreshButtons()
            return
        }
        val skill = recorder.stop(name, descInput.text.toString())
        refreshButtons()
        if (skill == null) {
            Toast.makeText(activity, "No actions captured", Toast.LENGTH_SHORT).show()
            return
        }
        log("⏺ Captured ${skill.steps.size} steps — review and save")
        showRecordedReview(skill)
    }

    /** Review → (optionally edit JSON) → save. The user always has the final word. */
    private fun showRecordedReview(skill: RecordedSkill) {
        val summary = skill.steps.mapIndexed { i, s -> "${i + 1}. ${playerStepDescription(s)}" }
            .joinToString("\n")
            .ifBlank { "(empty)" }
        val editable = EditText(activity)
        editable.setText(skill.toJson().toString(2))
        editable.textSize = 10f
        editable.setSingleLine(false)
        val scroll = android.widget.ScrollView(activity)
        scroll.addView(editable)
        MaterialAlertDialogBuilder(activity)
            .setTitle("Review: ${skill.name}")
            .setMessage(summary.take(1200))
            .setView(scroll)
            .setPositiveButton("Save") { _, _ ->
                val saved = userStore.saveFromJsonText(skill.id, editable.text.toString())
                if (saved != null) {
                    refreshUserSkillChips()
                    log("✓ Skill '${saved.name}' saved — find it under YOUR SKILLS")
                    Toast.makeText(activity, "Skill saved", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(activity, "Edited JSON invalid — original captured steps saved", Toast.LENGTH_LONG).show()
                    userStore.save(skill)
                    refreshUserSkillChips()
                }
            }
            .setNegativeButton("Discard", null)
            .show()
    }

    // ------------------------------------------------------------ /grill-me

    private fun startGrillMe(seed: String) {
        if (interviewActive) {
            Toast.makeText(activity, "Interview already running", Toast.LENGTH_SHORT).show()
            return
        }
        val goalText = if (seed.isNotBlank()) seed else goalInput.text.toString().trim()
        val seedPart = goalText.removePrefix("/grill-me").trim()
        interviewActive = true
        expand()
        interview.start(seedPart)
        interview.pump(uiScope)
        refreshButtons()
    }

    // ------------------------------------------------------- run / input

    private fun runFromInput() {
        val goal = goalInput.text.toString().trim()
        if (goal.isEmpty()) {
            Toast.makeText(activity, "Describe the task first", Toast.LENGTH_SHORT).show()
            return
        }
        if (goal.startsWith("/grill-me", ignoreCase = true)) {
            startGrillMe(goal)
            return
        }
        if (recorder.state == SkillRecorder.State.RECORDING) {
            Toast.makeText(activity, "Recording in progress — stop & save first", Toast.LENGTH_SHORT).show()
            return
        }
        val skill = selectedSkillId?.let { skills.byId(it) } ?: skills.match(goal)
        engine.run(CoroutineScope(kotlinx.coroutines.Dispatchers.Main.immediate), goal, skill)
    }

    // ------------------------------------------------- engine listener (UI thread)

    override fun onStateChanged(state: AgentEngine.State, message: String) {
        activity.runOnUiThread {
            applyAgentState(state, message)
            stepText.text = ""
            if (state == AgentEngine.State.RUNNING) statsText.visibility = View.GONE
            refreshButtons(engineState = state)
        }
    }

    /** Run summary line (v1.5.0): "8 / 24 steps · ~3.1k tok · 42s · completed". */
    override fun onRunStats(stats: AgentEngine.RunResult) {
        activity.runOnUiThread {
            val secs = stats.durationMs / 1000.0
            val tok = if (stats.estTokens >= 1000)
                String.format(java.util.Locale.US, "~%.1fk", stats.estTokens / 1000.0)
            else "~${stats.estTokens}"
            val dur = if (secs >= 100) "${(secs / 10).toInt() * 10}s" else String.format(java.util.Locale.US, "%.0fs", secs)
            statsText.text = "${stats.stepsUsed} / ${stats.stepBudget} steps · $tok tok · $dur · ${stats.outcome}"
            statsText.visibility = View.VISIBLE
        }
    }

    /**
     * Single owner of agent-status styling — called by the engine map AND the
     * /grill-me interview listener so the two writers can never drift.
     * Dot colors: IDLE/CANCELLED neutral · RUNNING primary(+pulse) ·
     * AWAITING_* warning · COMPLETED success · FAILED danger.
     */
    private fun applyAgentState(state: AgentEngine.State, message: String) {
        val (dotColor, label) = when (state) {
            AgentEngine.State.IDLE -> R.color.on_surface_variant to "✦ What should I do?"
            AgentEngine.State.RUNNING -> R.color.primary to "✦ Agent working — $message"
            AgentEngine.State.AWAITING_CONFIRM -> R.color.warning to "⚠ Waiting for your confirmation"
            AgentEngine.State.AWAITING_USER -> R.color.warning to "⏸ Paused — $message"
            AgentEngine.State.COMPLETED -> R.color.success to "✓ Completed"
            AgentEngine.State.FAILED -> R.color.danger to "✗ Failed — $message"
            AgentEngine.State.CANCELLED -> R.color.on_surface_variant to "■ Stopped"
        }
        applyStatus(activity.getColor(dotColor), label, pulsing = state == AgentEngine.State.RUNNING)
    }

    private fun applyStatus(color: Int, label: String, pulsing: Boolean) {
        lastDotColor = color
        lastLabel = label
        lastPulse = pulsing
        statusDot.backgroundTintList = ColorStateList.valueOf(color)
        statusText.text = label
        if (pulsing) startPulse() else stopPulse()
    }

    private fun refreshButtons(engineState: AgentEngine.State? = null) {
        val st = engineState ?: engine.state
        val running = st == AgentEngine.State.RUNNING
        val paused = st == AgentEngine.State.AWAITING_USER || st == AgentEngine.State.AWAITING_CONFIRM
        val recording = recorder.state == SkillRecorder.State.RECORDING
        btnRun.visibility = if (running || paused) View.GONE else View.VISIBLE
        btnTakeControl.visibility = if (running) View.VISIBLE else View.GONE
        btnResume.visibility = if (paused) View.VISIBLE else View.GONE
        btnStop.visibility = if (running || paused || interviewActive) View.VISIBLE else View.GONE
        btnRecord.text = if (recording) "Stop & Save" else "Record"
        (btnRecord as? com.google.android.material.button.MaterialButton)
            ?.setIconResource(if (recording) R.drawable.ic_stop else R.drawable.ic_record)
        btnRecord.visibility = if (running || paused) View.GONE else View.VISIBLE
        btnGrillMe.visibility = if (running || paused) View.GONE else View.VISIBLE
        if (recording) {
            askBarText.text = "⏺ REC — using the browser records a skill · tap to open"
            askBarText.setTextColor(activity.getColor(R.color.danger))
        } else if (!interviewActive) {
            askBarText.text = activity.getString(R.string.ask_agent)
            askBarText.setTextColor(activity.getColor(R.color.text_secondary))
        }
    }

    override fun onLog(line: String, isError: Boolean) {
        log(line, isError)
    }

    private fun log(line: String, isError: Boolean = false) {
        activity.runOnUiThread {
            logLines.add(line to isError)
            if (logLines.size > 200) logLines.removeAt(0)
            logAdapter?.clear()
            logAdapter?.addAll(logLines.map { it.first })
            logAdapter?.notifyDataSetChanged()
            syncStepCounter(line)
        }
    }

    /** Step counter in the header: "→ [3/8] …" and "step N" progress lines. */
    private fun syncStepCounter(line: String) {
        val bracket = Regex("→\\s*\\[(\\d+)/(\\d+)\\]").find(line)
        if (bracket != null) {
            stepText.text = "${bracket.groupValues[1]} / ${bracket.groupValues[2]}"
            return
        }
        val plain = Regex("(?i)\\bstep (\\d+)\\b").find(line)
        if (plain != null) {
            stepText.text = "Step ${plain.groupValues[1]}"
        } else if (line.startsWith("✓") || line.startsWith("✗") || line.startsWith("■")) {
            stepText.text = ""
        }
    }

    /**
     * Step-row styling: leading-glyph state colors + the stored isError flag
     * (previously discarded). Parser stays trivial — the glyph IS the state.
     */
    private inner class CometLogAdapter(
        context: Context,
        resource: Int,
        textViewResourceId: Int,
        objects: MutableList<String>
    ) : ArrayAdapter<String>(context, resource, textViewResourceId, objects) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = super.getView(position, convertView, parent) as TextView
            val error = logLines.getOrNull(position)?.second == true
            val glyphColor = when (view.text.firstOrNull()) {
                '✓', '▶' -> R.color.success
                '✗' -> R.color.danger
                '⚠' -> R.color.warning
                '⏺' -> R.color.danger
                '●', '✦', '✍' -> R.color.primary
                '→', '■' -> R.color.on_surface_variant
                else -> if (view.text.startsWith("🎙") || view.text.startsWith("✍")) R.color.primary
                else if (error) R.color.danger else R.color.on_surface
            }
            view.setTextColor(activity.getColor(if (error) R.color.danger else glyphColor))
            return view
        }
    }

    override fun onConfirmRequired(action: JSONObject, reason: String) {
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) {
                engine.confirm(false) // auto-deny: never leave the gate hanging
                return@runOnUiThread
            }
            val kind = action.optString("action")
            val detail = when (kind) {
                "type" -> "Type text into ${action.optString("ref")}"
                "click" -> "Click ${action.optString("ref")}"
                "navigate" -> "Navigate to ${action.optString("url").take(120)}"
                "download" -> "Download ${action.optString("url", action.optString("ref")).take(120)}"
                else -> kind
            }
            MaterialAlertDialogBuilder(activity)
                .setTitle("Confirm action")
                .setMessage("$detail\n\nReason: $reason\n\nAllow the agent to perform this action?")
                .setPositiveButton("Allow") { _, _ -> engine.confirm(true) }
                .setNegativeButton("Deny") { _, _ -> engine.confirm(false) }
                .setOnCancelListener { engine.confirm(false) }
                .show()
        }
    }

    override fun onAskUser(question: String) {
        activity.runOnUiThread {
            expand()
            revealAnswerRow()
            answerInput.hint = question.take(60)
            answerInput.requestFocus()
        }
    }

    private fun revealAnswerRow() {
        answerRow.visibility = View.VISIBLE
        answerRow.alpha = 0f
        answerRow.translationY = dp(8).toFloat()
        answerRow.animate().alpha(1f).translationY(0f)
            .setDuration(200).setInterpolator(EMPHASIZED)
            .setListener(null).start()
    }

    override fun onChallengeDetected(detail: String) {
        activity.runOnUiThread {
            val banner = activity.findViewById<LinearLayout>(R.id.challengeBanner)
            banner.visibility = View.VISIBLE
            banner.alpha = 0f
            banner.translationY = -24f
            banner.animate().alpha(1f).translationY(0f)
                .setDuration(200).setInterpolator(EMPHASIZED)
                .setListener(null).start()
            activity.findViewById<TextView>(R.id.challengeText).text = detail
            expand()
        }
    }

    // --------------------------------------------- interview listener (inner)

    val interviewListener = object : SkillInterview.Listener {
        override fun onQuestion(question: String) {
            activity.runOnUiThread {
                expand()
                log("🎙 $question")
                revealAnswerRow()
                answerInput.hint = question.take(60)
                answerInput.requestFocus()
                applyStatus(activity.getColor(R.color.primary), "🎙 Interview — answer below", pulsing = false)
            }
        }

        override fun onLog(line: String) {
            log(line)
        }

        override fun onDraftReady(skill: RecordedSkill, jsonText: String) {
            activity.runOnUiThread {
                log("✍ Draft ready: ${skill.summaryLine()} — review it below")
                showDraftReview(skill, jsonText)
            }
        }

        override fun onError(message: String) {
            activity.runOnUiThread {
                log("⚠ $message", isError = true)
                answerRow.visibility = View.VISIBLE
                answerInput.requestFocus()
            }
        }

        override fun onEnded() {
            activity.runOnUiThread {
                interviewActive = false
                answerRow.visibility = View.GONE
                refreshButtons()
            }
        }
    }

    /** Review-edit-iterate loop for /grill-me drafts. */
    private fun showDraftReview(skill: RecordedSkill, jsonText: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        val summary = skill.steps.mapIndexed { i, s -> "${i + 1}. ${playerStepDescription(s)}" }
            .joinToString("\n").take(900)
        val editable = EditText(activity)
        editable.setText(jsonText)
        editable.textSize = 10f
        editable.setSingleLine(false)
        val scroll = android.widget.ScrollView(activity)
        scroll.addView(editable)
        MaterialAlertDialogBuilder(activity)
            .setTitle("Review your skill: ${skill.name}")
            .setMessage("$summary\n\nEdit the JSON if you like, then Save — or send feedback to revise it.")
            .setView(scroll)
            .setPositiveButton("Save") { _, _ ->
                val saved = userStore.saveFromJsonText(skill.id, editable.text.toString())
                if (saved != null) {
                    refreshUserSkillChips()
                    log("✓ Skill '${saved.name}' saved")
                    Toast.makeText(activity, "Skill saved — tap its chip to run", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(activity, "JSON invalid — not saved. Revise and retry.", Toast.LENGTH_LONG).show()
                }
                interview.finishReview()
            }
            .setNeutralButton("Revise") { _, _ ->
                askRevisionFeedback()
            }
            .setNegativeButton("Discard") { _, _ ->
                interview.finishReview()
                log("■ Draft discarded")
            }
            .setOnCancelListener { interview.finishReview() }
            .show()
    }

    private fun askRevisionFeedback() {
        val input = EditText(activity)
        input.hint = "e.g. open the results in a new tab first; use my saved address"
        MaterialAlertDialogBuilder(activity)
            .setTitle("What should change?")
            .setView(input)
            .setPositiveButton("Revise") { _, _ ->
                val feedback = input.text.toString().trim()
                if (feedback.isNotEmpty()) {
                    interview.revise(feedback)
                    interview.pump(uiScope)
                }
            }
            .setNegativeButton("Discard") { _, _ -> interview.finishReview() }
            .show()
    }

    // --------------------------------------------- skill player listener (inner)

    val playerListener = object : SkillPlayer.Listener {
        override fun onStepStarted(index: Int, total: Int, description: String) {
            log("→ [$index/$total] $description")
        }

        override fun onStepResult(index: Int, ok: Boolean, message: String) {
            if (!ok) log("✗ step $index failed: $message", isError = true)
        }

        override fun onFinished(success: Boolean, summary: String) {
            log(if (success) "✓ $summary" else "✗ $summary", isError = !success)
        }

        override suspend fun askSensitiveValue(fieldDescription: String): String? =
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    if (activity.isFinishing || activity.isDestroyed) { cont.resume(null); return@suspendCancellableCoroutine }
                    val input = EditText(activity)
                    input.hint = "Value (never stored)"
                    input.transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
                    MaterialAlertDialogBuilder(activity)
                        .setTitle("Private field")
                        .setMessage("This skill fills: $fieldDescription\n\nType the value now — it is used once and never saved.")
                        .setView(input)
                        .setPositiveButton("Fill") { _, _ -> if (cont.isActive) cont.resume(input.text.toString()) }
                        .setNegativeButton("Skip") { _, _ -> if (cont.isActive) cont.resume(null as String?) }
                        .setOnCancelListener { if (cont.isActive) cont.resume(null as String?) }
                        .show()
                }
            }

        override suspend fun confirmStep(message: String): Boolean =
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    if (activity.isFinishing || activity.isDestroyed) { cont.resume(false); return@suspendCancellableCoroutine }
                    MaterialAlertDialogBuilder(activity)
                        .setTitle("Confirm replay step")
                        .setMessage(message)
                        .setPositiveButton("Allow") { _, _ -> if (cont.isActive) cont.resume(true) }
                        .setNegativeButton("Deny") { _, _ -> if (cont.isActive) cont.resume(false) }
                        .setOnCancelListener { if (cont.isActive) cont.resume(false) }
                        .show()
                }
            }
    }

    // --------------------------------------------------- recorder listener (inner)

    val recorderListener = object : SkillRecorder.Listener {
        private var stepCount = 0
        override fun onStepCountChanged(count: Int) {
            stepCount = count
            activity.runOnUiThread {
                if (recorder.state == SkillRecorder.State.RECORDING) {
                    askBarText.text = "⏺ REC — $count captured · tap to open"
                }
            }
        }

        override fun onRecordError(message: String) {
            activity.runOnUiThread {
                Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
            }
        }
    }
}

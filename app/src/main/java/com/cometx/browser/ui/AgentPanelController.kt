package com.cometx.browser.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.cometx.browser.R
import com.cometx.browser.ai.SettingsRepository
import com.cometx.browser.engine.AgentEngine
import com.cometx.browser.security.SafetyPolicy
import com.cometx.browser.skills.SkillRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject

/**
 * AgentPanelController — the agent UI: goal input, skills, live log, control
 * buttons, confirmation dialogs, ask-user answers, challenge banner sync.
 * Implements AgentEngine.Listener (called from the engine coroutine).
 */
class AgentPanelController(
    private val activity: Activity,
    private val engine: AgentEngine,
    private val settings: SettingsRepository,
    private val skills: SkillRegistry
) : AgentEngine.Listener {

    private var panel: LinearLayout
    private var askBar: LinearLayout
    private var statusDot: View
    private var statusText: TextView
    private var stepText: TextView
    private var goalInput: EditText
    private var skillChips: LinearLayout
    private var btnRun: Button
    private var btnTakeControl: Button
    private var btnResume: Button
    private var btnStop: Button
    private var answerRow: LinearLayout
    private var answerInput: EditText
    private var logList: ListView
    private val logLines = mutableListOf<Pair<String, Boolean>>()
    private var logAdapter: ArrayAdapter<String>? = null
    private var selectedSkillId: String? = null

    init {
        panel = activity.findViewById(R.id.agentPanel)
        askBar = activity.findViewById(R.id.askBar)
        statusDot = activity.findViewById(R.id.statusDot)
        statusText = activity.findViewById(R.id.statusText)
        stepText = activity.findViewById(R.id.stepText)
        goalInput = activity.findViewById(R.id.goalInput)
        skillChips = activity.findViewById(R.id.skillChips)
        btnRun = activity.findViewById(R.id.btnRun)
        btnTakeControl = activity.findViewById(R.id.btnTakeControl)
        btnResume = activity.findViewById(R.id.btnResume)
        btnStop = activity.findViewById(R.id.btnStop)
        answerRow = activity.findViewById(R.id.answerRow)
        answerInput = activity.findViewById(R.id.answerInput)
        logList = activity.findViewById(R.id.logList)

        logAdapter = ArrayAdapter(activity, R.layout.item_log, R.id.logLine, logLines.map { it.first }.toMutableList())
        logList.adapter = logAdapter

        activity.findViewById<Button>(R.id.btnOpenAgent).setOnClickListener { expand() }
        activity.findViewById<Button>(R.id.btnPanelClose).setOnClickListener { collapse() }
        askBar.setOnClickListener { expand() }

        btnRun.setOnClickListener { runFromInput() }
        btnTakeControl.setOnClickListener {
            engine.takeControl("you have control")
            Toast.makeText(activity, "You have control. Resume when ready.", Toast.LENGTH_LONG).show()
        }
        btnResume.setOnClickListener { engine.resume(if (answerInput.text.isNotBlank()) answerInput.text.toString() else null) }
        btnStop.setOnClickListener { engine.stop() }
        activity.findViewById<Button>(R.id.btnAnswerSend).setOnClickListener {
            val a = answerInput.text.toString()
            answerInput.setText("")
            answerRow.visibility = View.GONE
            engine.resume(a.ifBlank { null })
        }

        buildSkillChips()
    }

    fun isVisible(): Boolean = panel.visibility == View.VISIBLE
    fun expand() { panel.visibility = View.VISIBLE; askBar.visibility = View.GONE }
    fun collapse() { panel.visibility = View.GONE; askBar.visibility = View.VISIBLE }

    private fun buildSkillChips() {
        skillChips.removeAllViews()
        val margin = (8 * activity.resources.displayMetrics.density).toInt()
        for (skill in skills.skills) {
            val chip = TextView(activity)
            chip.text = "${skill.icon} ${skill.name}"
            chip.setPadding(20, 10, 20, 10)
            chip.textSize = 12f
            chip.setBackgroundResource(R.drawable.bg_chip)
            chip.setTextColor(activity.getColor(R.color.text_primary))
            (chip.layoutParams as? LinearLayout.LayoutParams)?.let { }
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
            chip.setTextColor(activity.getColor(if (active) R.color.accent_bright else R.color.text_primary))
        }
    }

    private fun runFromInput() {
        val goal = goalInput.text.toString().trim()
        if (goal.isEmpty()) {
            Toast.makeText(activity, "Describe the task first", Toast.LENGTH_SHORT).show()
            return
        }
        val skill = selectedSkillId?.let { skills.byId(it) } ?: skills.match(goal)
        engine.run(CoroutineScope(kotlinx.coroutines.Dispatchers.Main.immediate), goal, skill)
    }

    // ------------------------------------------------- engine listener (UI thread)

    override fun onStateChanged(state: AgentEngine.State, message: String) {
        activity.runOnUiThread {
            val (dotColor, label) = when (state) {
                AgentEngine.State.IDLE -> R.color.text_secondary to "✦ What should I do?"
                AgentEngine.State.RUNNING -> R.color.accent_bright to "✦ Agent working — $message"
                AgentEngine.State.AWAITING_CONFIRM -> R.color.warning to "⚠ Waiting for your confirmation"
                AgentEngine.State.AWAITING_USER -> R.color.warning to "⏸ Paused — $message"
                AgentEngine.State.COMPLETED -> R.color.success to "✓ Completed"
                AgentEngine.State.FAILED -> R.color.danger to "✗ Failed — $message"
                AgentEngine.State.CANCELLED -> R.color.text_secondary to "■ Stopped"
            }
            statusDot.setBackgroundColor(activity.getColor(dotColor))
            statusText.text = label
            val running = state == AgentEngine.State.RUNNING
            val paused = state == AgentEngine.State.AWAITING_USER || state == AgentEngine.State.AWAITING_CONFIRM
            btnRun.visibility = if (running || paused) View.GONE else View.VISIBLE
            btnTakeControl.visibility = if (running) View.VISIBLE else View.GONE
            btnResume.visibility = if (paused) View.VISIBLE else View.GONE
            btnStop.visibility = if (running || paused) View.VISIBLE else View.GONE
            answerRow.visibility = if (state == AgentEngine.State.AWAITING_USER) View.VISIBLE else View.GONE
            stepText.text = ""
        }
    }

    override fun onLog(line: String, isError: Boolean) {
        activity.runOnUiThread {
            logLines.add(line to isError)
            if (logLines.size > 200) logLines.removeAt(0)
            logAdapter?.clear()
            logAdapter?.addAll(logLines.map { it.first })
            logAdapter?.notifyDataSetChanged()
        }
    }

    override fun onConfirmRequired(action: JSONObject, reason: String) {
        activity.runOnUiThread {
            val kind = action.optString("action")
            val detail = when (kind) {
                "type" -> "Type text into ${action.optString("ref")}"
                "click" -> "Click ${action.optString("ref")}"
                "navigate" -> "Navigate to ${action.optString("url").take(120)}"
                "download" -> "Download ${action.optString("url", action.optString("ref")).take(120)}"
                else -> kind
            }
            AlertDialog.Builder(activity)
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
            answerRow.visibility = View.VISIBLE
            answerInput.hint = question.take(60)
            answerInput.requestFocus()
        }
    }

    override fun onChallengeDetected(detail: String) {
        activity.runOnUiThread {
            activity.findViewById<LinearLayout>(R.id.challengeBanner).visibility = View.VISIBLE
            activity.findViewById<TextView>(R.id.challengeText).text = detail
            expand()
        }
    }
}

package com.cometx.browser.engine

import android.webkit.WebView
import com.cometx.browser.ai.AgentProtocol
import com.cometx.browser.ai.ChatMessage
import com.cometx.browser.ai.ModelRouter
import com.cometx.browser.ai.SettingsRepository
import com.cometx.browser.automation.ActionExecutor
import com.cometx.browser.memory.MemoryStore
import com.cometx.browser.perception.ChallengeDetector
import com.cometx.browser.perception.ChallengeResult
import com.cometx.browser.perception.DomExtractor
import com.cometx.browser.perception.PageObservation
import com.cometx.browser.perception.Screenshotter
import com.cometx.browser.perception.VisionPolicy
import com.cometx.browser.security.ActionValidator
import com.cometx.browser.security.PromptInjectionDetector
import com.cometx.browser.security.SafetyPolicy
import com.cometx.browser.skills.Skill
import com.cometx.browser.util.Logx
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * AgentEngine — the observe → understand → act → verify loop (brief §12).
 *
 * Safety boundary (brief §28): the model output is PROPOSAL ONLY. Every action
 * passes ActionParser → ActionValidator → SafetyPolicy → (human confirm) →
 * ActionExecutor. The model never receives raw engine capabilities.
 *
 * States: IDLE → RUNNING → {AWAITING_CONFIRM | AWAITING_USER} → RUNNING →
 *         COMPLETED | FAILED | CANCELLED
 * Human takeover (brief §35) is available in every state.
 */
class AgentEngine(
    private val router: ModelRouter,
    private val settings: SettingsRepository,
    private val memory: MemoryStore,
    private val visionPolicy: VisionPolicy,
    private val sink: AgentSink
) {

    enum class State { IDLE, RUNNING, AWAITING_CONFIRM, AWAITING_USER, COMPLETED, FAILED, CANCELLED }

    /** UI-facing event stream (single listener pattern keeps wiring explicit). */
    interface Listener {
        fun onStateChanged(state: State, message: String)
        fun onLog(line: String, isError: Boolean = false)
        fun onConfirmRequired(action: JSONObject, reason: String)
        fun onAskUser(question: String)
        fun onChallengeDetected(detail: String)

        /** Run-level summary (v1.5.0), emitted exactly once per run at its terminal state. */
        fun onRunStats(stats: RunResult) {}
    }

    /** One line of honest telemetry for the result card ("simple stats"). */
    data class RunResult(
        val stepsUsed: Int,
        val stepBudget: Int,
        val estTokens: Int,
        val durationMs: Long,
        val screenshots: Int,
        val outcome: String
    )

    @Volatile var state: State = State.IDLE
        private set

    private var job: Job? = null
    private var listener: Listener? = null

    // pending gates — resume/confirm may arrive BEFORE the engine reaches the
    // gate (UI thread vs engine coroutine), so arrivals are recorded and
    // consumed order-independently (red-team fix: race condition elimination)
    @Volatile private var takeoverRequested = false
    @Volatile private var pendingUserAnswer: String? = null
    @Volatile private var pendingAnswerArrived = false
    @Volatile private var pendingConfirmDecision: Boolean? = null
    @Volatile private var pendingConfirmArrived = false
    @Volatile private var userResumeDeferred: kotlinx.coroutines.CompletableDeferred<String?>? = null
    @Volatile private var confirmDeferred: kotlinx.coroutines.CompletableDeferred<Boolean>? = null
    private val gateLock = Any()

    private var goal = ""
    private var skill: Skill? = null
    private var session: MemoryStore.Session? = null
    private var forceVisionNext = false
    private var lastActionFailed = false
    private var observationCompressed = false

    /** Set once a model rejects images — vision stays off for the rest of the run. */
    private var visionDisabledForRun = false

    /**
     * Run-scoped telemetry accumulator (v1.5.0 simple stats). Token counts
     * are ESTIMATES (chars/4 over prompt + response text) — providers do not
     * surface wire usage today; the "~" on the result card says so.
     */
    private class RunAccumulator {
        var stepsUsed = 0
        var stepBudget = 0
        var estTokens = 0L
        var screenshots = 0
        val startedAt = System.currentTimeMillis()
    }
    private var acc: RunAccumulator? = null
    private var statsEmitted = false

    /** §19: shrink an oversized observation (elements + page text). */
    private fun compress(obs: PageObservation): PageObservation = obs.copy(
        elements = obs.elements.take(40),
        textSample = obs.textSample.take(4000)
    )

    fun bind(listener: Listener) {
        this.listener = listener
    }

    fun run(scope: CoroutineScope, goal: String, skill: Skill?) {
        if (state == State.RUNNING) return
        this.goal = goal
        this.skill = skill
        this.session = MemoryStore.Session(goal, System.currentTimeMillis())
        this.forceVisionNext = false
        this.lastActionFailed = false
        this.observationCompressed = false
        this.visionDisabledForRun = false
        this.acc = RunAccumulator()
        this.statsEmitted = false
        setState(State.RUNNING, "Agent starting")
        listener?.onLog("✦ Goal: $goal")
        skill?.let { listener?.onLog("Skill: ${it.name}") }
        job = scope.launch { loop() }
    }

    /** Human requests pause & take control (brief §35). Takes effect at the next engine gate. */
    fun takeControl(reason: String) {
        if (state == State.RUNNING) {
            takeoverRequested = true
            setState(State.AWAITING_USER, reason)
            listener?.onLog("⏸ Human takeover: $reason")
        }
    }

    /** Resume after takeover / challenge / ask_user answer. Order-safe. */
    fun resume(userAnswer: String?) {
        if (state == State.AWAITING_USER || state == State.AWAITING_CONFIRM) {
            synchronized(gateLock) {
                pendingUserAnswer = userAnswer
                pendingAnswerArrived = true
            }
            userResumeDeferred?.complete(userAnswer)
        }
    }

    fun confirm(actionApproved: Boolean) {
        synchronized(gateLock) {
            pendingConfirmDecision = actionApproved
            pendingConfirmArrived = true
        }
        confirmDeferred?.complete(actionApproved)
    }

    fun stop() {
        job?.cancel()
        job = null
        synchronized(gateLock) {
            pendingAnswerArrived = false
            pendingConfirmArrived = false
        }
        confirmDeferred?.complete(false)
        userResumeDeferred?.complete(null)
        setState(State.CANCELLED, "Agent stopped by user")
        listener?.onLog("■ Agent stopped")
        memory.addRecentTask(goal, "cancelled")
        emitRunStats("cancelled")
    }

    // ------------------------------------------------------------------ loop

    private suspend fun loop() {
        // Phase 3: adaptive step budget — auto-tuned to the task, extends
        // itself while the task progresses, structurally capped at 60.
        val budget = StepBudget.initialFor(settings.maxSteps(), goal)
        val history = mutableListOf<JSONObject>()
        val sigWindow = ArrayDeque<String>()
        var userAnswer: String? = null
        var lastUrl = ""
        var loadingStreak = 0

        try {
            while (state == State.RUNNING || state == State.AWAITING_USER) {
                // STEP BUDGET GATE: extend while the task visibly progresses,
                // stop when it is exhausted (or the run is thrashing).
                if (!budget.hasRemaining()) {
                    if (budget.shouldExtend()) {
                        budget.extend()
                        listener?.onLog("⤴ Step budget extended to ${budget.budget} — task is still progressing")
                    } else {
                        listener?.onLog("Step budget exhausted (${budget.describe()})", isError = true)
                        fail("reached the step limit before completing the task")
                        return
                    }
                }
                // TOP-OF-LOOP takeover gate: pausing here guarantees the engine
                // re-observes (including anything the user did) before acting.
                if (takeoverRequested) {
                    takeoverRequested = false
                    val answer = awaitUserGate()
                    if (state != State.AWAITING_USER) return // timed out or stopped
                    setState(State.RUNNING, "resumed after takeover")
                    if (answer != null) userAnswer = answer
                    continue
                }
                budget.consume()
                acc?.let { it.stepsUsed = budget.used; it.stepBudget = budget.budget }
                listener?.onLog("— Step ${budget.describe()}")

                // 1) OBSERVE -------------------------------------------------
                val obs = sink.observe()
                if (obs == null) {
                    // Loading retries are refunded: they are not decisions.
                    budget.refund()
                    loadingStreak++
                    if (loadingStreak >= 6) {
                        fail("page never became readable (loading loop)")
                        return
                    }
                    listener?.onLog("Page not readable (loading?) — waiting", isError = true)
                    kotlinx.coroutines.delay(1500)
                    continue
                }
                loadingStreak = 0

                // 2) UNDERSTAND: injection scan + challenge detection --------
                val combinedText = obs.textSample + "\n" + obs.elements.joinToString("\n") { it.describe() }
                val injections = PromptInjectionDetector.detect(combinedText)
                val obsFlagged = obs.copy(
                    injectionSignals = injections.map { it.patternId }
                )
                if (injections.isNotEmpty()) {
                    listener?.onLog("⚠ Untrusted-content signals: ${injections.joinToString { it.patternId }}", isError = true)
                }

                val challenge = ChallengeDetector.evaluate(obsFlagged.url, obsFlagged.textSample, obsFlagged.elements.joinToString("\n") { it.describe() })
                var obsFinal = obsFlagged.copy(challenge = challenge)

                if (challenge.type != ChallengeResult.NONE) {
                    listener?.onLog("🔒 Human verification detected: ${challenge.detail}")
                    listener?.onChallengeDetected(challenge.detail)
                    setState(State.AWAITING_USER, "verification required")
                    listener?.onLog("Take control to complete the challenge, then resume.")
                    val answer = awaitUserGate()
                    if (state != State.AWAITING_USER) return // timed out → engine already failed
                    setState(State.RUNNING, "resumed after verification")
                    if (answer != null) userAnswer = answer
                    budget.refund() // human gates never consume the agent's budget
                    continue // re-observe the (possibly solved) page
                }

                // 3) VISION (policy-gated) -----------------------------------
                var visionB64: String? = null
                var marksOnScreen = 0
                val wantVision = !visionDisabledForRun && (forceVisionNext ||
                    visionPolicy.shouldCapture(obsFinal, lastActionFailed, agentRequestedVision = false, stepIndex = budget.used))
                if (wantVision) {
                    listener?.onLog("👁 Capturing screenshot for vision model…")
                    acc?.screenshots = (acc?.screenshots ?: 0) + 1
                    // v1.5.0 Set-of-Marks: badges drawn from the SAME obsFinal
                    // the model receives (legend and picture can never drift,
                    // even after §19 compression). Off or failed → plain shot.
                    val shot = if (settings.somOverlay()) {
                        try { sink.screenshotAnnotatedBase64(obsFinal) } catch (_: Exception) { null }
                    } else null
                    val b64: String?
                    if (shot != null && shot.marks > 0) {
                        b64 = shot.base64
                        marksOnScreen = shot.marks
                        listener?.onLog("🔖 $marksOnScreen marks drawn — badge N = ref eN")
                    } else {
                        b64 = (shot?.base64) ?: sink.screenshotBase64()
                    }
                    if (b64 != null) {
                        visionB64 = b64
                        listener?.onLog("   screenshot ${(visionB64?.length ?: 0) * 3 / 4 / 1024} KB")
                    } else {
                        listener?.onLog("   screenshot unavailable", isError = true)
                    }
                    forceVisionNext = false
                }

                // §20: never feed pixels to a model that cannot see them.
                // If the agent model lacks vision, a SEPARATE vision model
                // describes the screenshot as text; without one, DOM/a11y only.
                var visionDescription: String? = null
                val agentModelSees = try { router.resolve(ModelRouter.Role.AGENT)?.model?.supports(com.cometx.browser.ai.Capability.VISION) == true } catch (_: Exception) { false }
                if (visionB64 != null && !agentModelSees) {
                    visionDescription = try { router.describeScreenshot(visionB64) } catch (_: Exception) { null }
                    if (visionDescription != null) {
                        listener?.onLog("👁 Vision model described the screenshot (agent model is text-only)")
                    } else {
                        listener?.onLog("👁 No vision model available — continuing with DOM/accessibility perception")
                    }
                    visionB64 = null
                }

                // 4) PLAN (LLM — protocol negotiated per model, Phase 2 §5) ---
                var stepPromptChars = 0L
                fun buildMessages(protocol: AgentProtocol): List<ChatMessage> {
                    val messages = mutableListOf<ChatMessage>()
                    val systemText = AgentPrompt.system(
                                goal, skill, memory, budget.budget,
                                injectionWarning = injections.isNotEmpty(),
                                challengeNote = if (challenge.type != ChallengeResult.NONE) challenge.detail else null,
                                protocol = protocol,
                                marksEnabled = settings.somOverlay()
                            )
                    messages.add(ChatMessage(role = "system", text = systemText))
                    val compressedHistory = if (observationCompressed) history.takeLast(4) else history
                    val stepMsg = AgentPrompt.stepMessage(
                        obsFinal, compressedHistory, visionB64, userAnswer, visionDescription,
                        marksLegend = AgentPrompt.marksLegend(marksOnScreen).takeIf { visionB64 != null }
                    )
                    messages.add(stepMsg)
                    stepPromptChars = systemText.length.toLong() + (stepMsg.text?.length ?: 0)
                    return messages
                }

                val turn = try {
                    router.agentStep(
                        ModelRouter.AgentRequest(ModelRouter.Role.AGENT) { protocol -> buildMessages(protocol) }
                    )
                } catch (e: com.cometx.browser.ai.ContextTooLargeException) {
                    // §19: observation too large → compress and retry ONCE
                    if (observationCompressed) {
                        listener?.onLog("Observation still too large for the model — stopping", isError = true)
                        fail("page too large for the selected model")
                        return
                    }
                    observationCompressed = true
                    listener?.onLog("⚠ Observation exceeded model context — compressing and retrying")
                    obsFinal = compress(obsFinal)
                    continue
                } catch (e: com.cometx.browser.ai.VisionUnsupportedException) {
                    // The landed candidate cannot see: retry this step text-only,
                    // and don't feed pixels to anyone else this run either.
                    visionDisabledForRun = true
                    forceVisionNext = false
                    listener?.onLog("👁 Model cannot read images — continuing with DOM/accessibility perception", isError = true)
                    continue
                } catch (e: Exception) {
                    listener?.onLog("Model error: ${e.message}", isError = true)
                    fail("model call failed: ${e.message}")
                    return
                }
                for (event in turn.events) listener?.onLog("· $event")
                if (observationCompressed) listener?.onLog("· compressed observation in use for this task")
                val action = turn.decision.toActionJson()
                acc?.let {
                    val respChars = action.toString().length + turn.events.sumOf { e -> e.length }
                    it.estTokens += (stepPromptChars + respChars) / 4
                }
                userAnswer = null
                val note = action.optString("note", "")
                if (note.isNotBlank()) listener?.onLog("· $note")
                // Phase 3: keep the model aware of its remaining room.
                if (budget.remaining() <= 3) {
                    listener?.onLog("· budget note: ${budget.remaining()} step(s) left before budget review")
                }

                // 6) TERMINAL ACTIONS ----------------------------------------
                when (action.optString("action")) {
                    "done" -> {
                        val summary = action.optString("summary", "Task completed")
                        listener?.onLog("✓ $summary")
                        setState(State.COMPLETED, summary)
                        session?.add(budget.used, "done", summary, "")
                        memory.addRecentTask(goal, "completed")
                        emitRunStats("completed")
                        return
                    }
                    "fail" -> {
                        val reason = action.optString("reason", "unknown")
                        listener?.onLog("✗ $reason", isError = true)
                        fail(reason)
                        return
                    }
                    "ask_user" -> {
                        val q = action.optString("question", "Need your input")
                        listener?.onLog("❓ $q")
                        setState(State.AWAITING_USER, q)
                        listener?.onAskUser(q)
                        val answer = awaitUserGate()
                        if (state != State.AWAITING_USER) return // timed out → engine already failed
                        setState(State.RUNNING, "resumed")
                        if (answer != null) userAnswer = answer
                        history.add(JSONObject().put("step", budget.used).put("action", "ask_user").put("result", "answered"))
                        budget.refund() // Q&A rounds never consume the budget
                        continue
                    }
                    "screenshot" -> { forceVisionNext = true; history.add(JSONObject().put("step", budget.used).put("action", "screenshot")); continue }
                    "request_vision" -> { forceVisionNext = true; history.add(JSONObject().put("step", budget.used).put("action", "request_vision")); continue }
                }

                // 7) VALIDATE -------------------------------------------------
                val vObs = ActionValidator.Observation(
                    obsFinal.url, obsFinal.viewportW, obsFinal.viewportH,
                    obsFinal.elements.map { ActionValidator.ElementRef(it.ref, it.tag, it.type, it.w.toDouble(), it.h.toDouble()) },
                    obsFinal.tabs.size
                )
                val verdict = ActionValidator.validate(action, vObs)
                if (verdict !is ActionValidator.Verdict.Ok) {
                    val reason = (verdict as ActionValidator.Verdict.Reject).reason
                    listener?.onLog("⚠ Action rejected: $reason", isError = true)
                    history.add(JSONObject().put("step", budget.used).put("action", action.optString("action")).put("result", "rejected: $reason"))
                    lastActionFailed = true
                    continue // give the model its own error as feedback
                }

                // 8) SAFETY POLICY (+ human confirmation gate) ----------------
                val target = action.optString("ref", "")
                val targetEl = obsFinal.elements.firstOrNull { it.ref == target }
                val assessment = SafetyPolicy.assess(
                    action, obsFinal.url,
                    targetTextIfKnown = targetEl?.describe(),
                    isPasswordTarget = targetEl?.isPassword() == true
                )
                if (assessment.risk == SafetyPolicy.Risk.BLOCK) {
                    listener?.onLog("⛔ Blocked: ${assessment.reason}", isError = true)
                    history.add(JSONObject().put("step", budget.used).put("action", action.optString("action")).put("result", "blocked: ${assessment.reason}"))
                    lastActionFailed = true
                    continue
                }
                if (assessment.risk == SafetyPolicy.Risk.CONFIRM && settings.confirmHighRisk()) {
                    // Takeover pressed while the confirmation was being raised:
                    // the user is in charge, the planned action is dropped.
                    if (takeoverRequested) {
                        budget.refund()
                        listener?.onLog("⏸ Takeover accepted — planned action cancelled")
                        continue
                    }
                    listener?.onLog("⚠ Confirmation required: ${assessment.reason}")
                    setState(State.AWAITING_CONFIRM, assessment.reason)
                    listener?.onConfirmRequired(action, assessment.reason)
                    val approved = awaitConfirmGate()
                    if (state != State.AWAITING_CONFIRM) return // stopped
                    if (approved == null) { // timed out — no zombie state machines
                        fail("confirmation timed out — agent stopped")
                        return
                    }
                    setState(State.RUNNING, "resumed")
                    if (!approved) {
                        listener?.onLog("Action denied by user")
                        history.add(JSONObject().put("step", budget.used).put("action", action.optString("action")).put("result", "denied by user"))
                        continue
                    }
                }

                // 9) EXECUTE ---------------------------------------------------
                // Takeover pressed while planning ran: drop the action, never
                // execute after the user believes they have control.
                if (takeoverRequested) {
                    budget.refund()
                    listener?.onLog("⏸ Takeover accepted — planned action cancelled")
                    continue
                }
                val result = sink.execute(action)
                listener?.onLog(if (result.ok) "→ ${result.message}" else "✗ ${result.message}", isError = !result.ok)
                lastActionFailed = !result.ok
                session?.add(budget.used, action.optString("action"), result.summary(), note)
                // Extraction results (find_text / find_element / extract / copy)
                // reach the model as structured data — otherwise they are lost.
                val historyEntry = JSONObject().put("step", budget.used)
                    .put("action", action.optString("action"))
                    .put("result", if (result.ok) "ok: ${result.message.take(120)}" else "failed: ${result.message.take(120)}")
                if (result.ok && result.data != null) {
                    val dataStr = result.data.toString()
                    if (dataStr.length > 4 && dataStr != "{}") historyEntry.put("data", dataStr.take(1500))
                }
                history.add(historyEntry)
                if (action.optString("action") == "remember") {
                    memory.remember(action.optString("key_name"), action.optString("fact"))
                }

                // 10) LOOP PROTECTION + PROGRESS MODEL -------------------------
                val sig = actionSignature(action, obsFinal.url)
                sigWindow.addLast(sig)
                while (sigWindow.size > 4) sigWindow.removeFirst()
                val repeated = sigWindow.size == 4 && sigWindow.toSet().size <= 2
                if (repeated) {
                    listener?.onLog("↻ Repetition detected — requesting replan", isError = true)
                    history.add(JSONObject().put("step", budget.used).put("warning", "you are repeating the same actions; choose a DIFFERENT approach or fail"))
                    sigWindow.clear()
                }

                // Feed the adaptive budget (Phase 3): the extension decision is
                // made at the top of the next iteration.
                budget.record(
                    actionSuccess = result.ok,
                    urlChanged = lastUrl.isNotEmpty() && obsFinal.url != lastUrl,
                    repeated = repeated
                )
                lastUrl = obsFinal.url

                kotlinx.coroutines.delay(150) // let rendering settle
            }
        } catch (ce: CancellationException) {
            // stop() already handled state
        } catch (e: Exception) {
            Logx.e("agent loop crash", e)
            listener?.onLog("Engine error: ${e.message}", isError = true)
            fail("engine error: ${e.message}")
        }
    }

    private fun actionSignature(a: JSONObject, url: String): String {
        val kind = a.optString("action")
        val target = a.optString("ref", "") + a.optString("url", "") + a.optString("text", "").take(20) + a.optInt("x", 0).toString() + a.optInt("y", 0).toString()
        return "$kind|$target|${url.substringBefore('?').take(80)}"
    }

    /**
     * Waits for the human gate. Order-safe: if resume() already fired, the
     * recorded answer is consumed immediately; otherwise a deferred is created
     * (under lock, with a re-check) and awaited with a hard timeout.
     */
    private suspend fun awaitUserGate(): String? {
        var d: kotlinx.coroutines.CompletableDeferred<String?>?
        synchronized(gateLock) {
            if (pendingAnswerArrived) {
                pendingAnswerArrived = false
                val a = pendingUserAnswer
                pendingUserAnswer = null
                return a
            }
            d = kotlinx.coroutines.CompletableDeferred()
            userResumeDeferred = d
            if (pendingAnswerArrived) {          // arrived between check and creation
                pendingAnswerArrived = false
                d!!.complete(pendingUserAnswer)
                pendingUserAnswer = null
            }
        }
        val answer = kotlinx.coroutines.withTimeoutOrNull(PAUSE_TIMEOUT_MS) { d!!.await() }
        if (d?.isCompleted != true) fail("paused too long (over ${PAUSE_TIMEOUT_MS / 60000} min) — agent stopped")
        userResumeDeferred = null
        return answer
    }

    /** @return true = approved, false = denied, null = timed out (caller must stop). */
    private suspend fun awaitConfirmGate(): Boolean? {
        var d: kotlinx.coroutines.CompletableDeferred<Boolean>?
        synchronized(gateLock) {
            if (pendingConfirmArrived) {
                pendingConfirmArrived = false
                val a = pendingConfirmDecision
                pendingConfirmDecision = null
                return a
            }
            d = kotlinx.coroutines.CompletableDeferred()
            confirmDeferred = d
            if (pendingConfirmArrived) {
                pendingConfirmArrived = false
                d!!.complete(pendingConfirmDecision ?: false)
                pendingConfirmDecision = null
            }
        }
        val approved = kotlinx.coroutines.withTimeoutOrNull(PAUSE_TIMEOUT_MS) { d!!.await() }
        confirmDeferred = null
        return approved
    }

    private companion object {
        /** Hard cap on human gates so the engine can never hang forever. */
        const val PAUSE_TIMEOUT_MS = 15L * 60 * 1000
    }

    private fun setState(s: State, msg: String) {
        state = s
        listener?.onStateChanged(s, msg)
    }

    private fun fail(reason: String) {
        session?.let { memory.addRecentTask(goal, "failed") }
        setState(State.FAILED, reason)
        emitRunStats("failed")
    }

    /** Exactly-once terminal summary (stop() after completion must not re-emit). */
    private fun emitRunStats(outcome: String) {
        val a = acc ?: return
        if (statsEmitted) return
        statsEmitted = true
        listener?.onRunStats(
            RunResult(
                stepsUsed = a.stepsUsed,
                stepBudget = a.stepBudget,
                estTokens = a.estTokens.toInt(),
                durationMs = System.currentTimeMillis() - a.startedAt,
                screenshots = a.screenshots,
                outcome = outcome
            )
        )
    }
}

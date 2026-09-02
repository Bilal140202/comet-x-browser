package com.cometx.browser.engine

import android.webkit.WebView
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
    }

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
    }

    // ------------------------------------------------------------------ loop

    private suspend fun loop() {
        var step = 0
        val maxSteps = settings.maxSteps()
        val history = mutableListOf<JSONObject>()
        val sigWindow = ArrayDeque<String>()
        var userAnswer: String? = null

        try {
            while (step < maxSteps && (state == State.RUNNING || state == State.AWAITING_USER)) {
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
                step++
                listener?.onLog("— Step $step/$maxSteps")

                // 1) OBSERVE -------------------------------------------------
                val obs = sink.observe()
                if (obs == null) {
                    listener?.onLog("Page not readable (loading?) — waiting", isError = true)
                    kotlinx.coroutines.delay(1500)
                    continue
                }

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
                val obsFinal = obsFlagged.copy(challenge = challenge)

                if (challenge.type != ChallengeResult.NONE) {
                    listener?.onLog("🔒 Human verification detected: ${challenge.detail}")
                    listener?.onChallengeDetected(challenge.detail)
                    setState(State.AWAITING_USER, "verification required")
                    listener?.onLog("Take control to complete the challenge, then resume.")
                    val answer = awaitUserGate()
                    if (state != State.AWAITING_USER) return // timed out → engine already failed
                    setState(State.RUNNING, "resumed after verification")
                    if (answer != null) userAnswer = answer
                    continue // re-observe the (possibly solved) page
                }

                // 3) VISION (policy-gated) -----------------------------------
                var visionB64: String? = null
                val wantVision = forceVisionNext ||
                    visionPolicy.shouldCapture(obsFinal, lastActionFailed, agentRequestedVision = false, stepIndex = step)
                if (wantVision && step <= maxSteps) {
                    listener?.onLog("👁 Capturing screenshot for vision model…")
                    val b64 = sink.screenshotBase64()
                    if (b64 != null) {
                        visionB64 = b64
                        listener?.onLog("   screenshot ${(visionB64?.length ?: 0) * 3 / 4 / 1024} KB")
                    } else {
                        listener?.onLog("   screenshot unavailable", isError = true)
                    }
                    forceVisionNext = false
                }

                // 4) PLAN (LLM) ----------------------------------------------
                val messages = mutableListOf<ChatMessage>()
                messages.add(
                    ChatMessage(
                        role = "system",
                        text = AgentPrompt.system(
                            goal, skill, memory, maxSteps,
                            injectionWarning = injections.isNotEmpty(),
                            challengeNote = if (challenge.type != ChallengeResult.NONE) challenge.detail else null
                        )
                    )
                )
                messages.add(AgentPrompt.stepMessage(obsFinal, history, visionB64, userAnswer))
                userAnswer = null

                val raw = try {
                    router.chatWithFallback(ModelRouter.Role.REASONING, messages)
                } catch (e: Exception) {
                    listener?.onLog("Model error: ${e.message}", isError = true)
                    fail("model call failed: ${e.message}")
                    return
                }

                // 5) PARSE (+ one repair retry) ------------------------------
                var action = ActionParser.parse(raw) ?: ActionParser.salvage(raw)
                if (action == null) {
                    listener?.onLog("Model returned non-JSON; requesting repair")
                    val repair = try {
                        router.chatWithFallback(
                            ModelRouter.Role.REASONING,
                            messages + ChatMessage("assistant", raw.take(800)) + ChatMessage("user", AgentPrompt.repair())
                        )
                    } catch (e: Exception) { null }
                    action = repair?.let { ActionParser.parse(it) ?: ActionParser.salvage(it) }
                    if (action == null) {
                        history.add(JSONObject().put("step", step).put("error", "unparseable model output twice"))
                        listener?.onLog("Unparseable model output — stopping", isError = true)
                        fail("model repeatedly returned invalid JSON")
                        return
                    }
                }
                val note = action.optString("note", "")
                if (note.isNotBlank()) listener?.onLog("· $note")

                // 6) TERMINAL ACTIONS ----------------------------------------
                when (action.optString("action")) {
                    "done" -> {
                        val summary = action.optString("summary", "Task completed")
                        listener?.onLog("✓ $summary")
                        setState(State.COMPLETED, summary)
                        session?.add(step, "done", summary, "")
                        memory.addRecentTask(goal, "completed")
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
                        history.add(JSONObject().put("step", step).put("action", "ask_user").put("result", "answered"))
                        continue
                    }
                    "screenshot" -> { forceVisionNext = true; history.add(JSONObject().put("step", step).put("action", "screenshot")); continue }
                    "request_vision" -> { forceVisionNext = true; history.add(JSONObject().put("step", step).put("action", "request_vision")); continue }
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
                    history.add(JSONObject().put("step", step).put("action", action.optString("action")).put("result", "rejected: $reason"))
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
                    history.add(JSONObject().put("step", step).put("action", action.optString("action")).put("result", "blocked: ${assessment.reason}"))
                    lastActionFailed = true
                    continue
                }
                if (assessment.risk == SafetyPolicy.Risk.CONFIRM && settings.confirmHighRisk()) {
                    listener?.onLog("⚠ Confirmation required: ${assessment.reason}")
                    setState(State.AWAITING_CONFIRM, assessment.reason)
                    listener?.onConfirmRequired(action, assessment.reason)
                    val approved = awaitConfirmGate()
                    if (state != State.AWAITING_CONFIRM) return // timed out or stopped
                    setState(State.RUNNING, "resumed")
                    if (!approved) {
                        listener?.onLog("Action denied by user")
                        history.add(JSONObject().put("step", step).put("action", action.optString("action")).put("result", "denied by user"))
                        continue
                    }
                }

                // 9) EXECUTE ---------------------------------------------------
                val result = sink.execute(action)
                listener?.onLog(if (result.ok) "→ ${result.message}" else "✗ ${result.message}", isError = !result.ok)
                lastActionFailed = !result.ok
                session?.add(step, action.optString("action"), result.summary(), note)
                history.add(
                    JSONObject().put("step", step)
                        .put("action", action.optString("action"))
                        .put("result", if (result.ok) "ok: ${result.message.take(120)}" else "failed: ${result.message.take(120)}")
                )
                if (action.optString("action") == "remember") {
                    memory.remember(action.optString("key_name"), action.optString("fact"))
                }

                // 10) LOOP PROTECTION ------------------------------------------
                val sig = actionSignature(action, obsFinal.url)
                sigWindow.addLast(sig)
                while (sigWindow.size > 4) sigWindow.removeFirst()
                if (sigWindow.size == 4 && sigWindow.toSet().size <= 2) {
                    listener?.onLog("↻ Repetition detected — requesting replan", isError = true)
                    history.add(JSONObject().put("step", step).put("warning", "you are repeating the same actions; choose a DIFFERENT approach or fail"))
                    sigWindow.clear()
                }

                kotlinx.coroutines.delay(150) // let rendering settle
            }

            if (state == State.RUNNING) {
                if (step >= maxSteps) {
                    listener?.onLog("Step budget exhausted", isError = true)
                    fail("reached the step limit before completing the task")
                }
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

    private suspend fun awaitConfirmGate(): Boolean {
        var d: kotlinx.coroutines.CompletableDeferred<Boolean>?
        synchronized(gateLock) {
            if (pendingConfirmArrived) {
                pendingConfirmArrived = false
                val a = pendingConfirmDecision ?: false
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
        val approved = kotlinx.coroutines.withTimeoutOrNull(PAUSE_TIMEOUT_MS) { d!!.await() } ?: false
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
    }
}

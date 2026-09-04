package com.cometx.browser.skills

import com.cometx.browser.ai.ChatMessage
import com.cometx.browser.ai.JsonFixtures
import com.cometx.browser.ai.ModelRouter
import com.cometx.browser.util.Logx
import kotlinx.coroutines.launch

/**
 * SkillInterview — the /grill-me built-in assistant (Phase 3).
 *
 * Before any skill is written, the agent INTERVIEWS the user with targeted,
 * step-by-step questions: outcome, start point, exact steps, run-to-run
 * inputs, sensitive fields, verification, edge cases, hard limits. Only when
 * the picture is complete (or the user says "write it") does it produce the
 * full skill JSON — which the user then reviews, edits and iterates on before
 * anything is saved.
 *
 * Resilience: question PHRASING is AI, but the topic STATE MACHINE is local —
 * if the provider fails mid-interview the built-in question bank takes over,
 * so the interview can never dead-end.
 */
class SkillInterview(
    private val router: ModelRouter,
    private val listener: Listener
) {

    interface Listener {
        fun onQuestion(question: String)
        fun onLog(line: String)
        fun onDraftReady(skill: RecordedSkill, jsonText: String)
        fun onError(message: String)
        fun onEnded()
    }

    enum class Phase { IDLE, ASKING, GENERATING, REVIEWING }

    companion object {
        /** Topic order = interview order. One question each. */
        val TOPICS = listOf("OUTCOME", "START", "STEPS", "INPUTS", "SENSITIVE", "VERIFY", "EDGES", "LIMITS")

        /** Fallback bank — the interview works even with no provider reachable. */
        val FALLBACK_QUESTIONS = mapOf(
            "OUTCOME" to "What exactly should this skill accomplish, end to end?",
            "START" to "Where does it start — which site or URL — and does it need you to be logged in first?",
            "STEPS" to "Walk me through the exact steps: what to open, click and fill, in what order?",
            "INPUTS" to "Which values change from run to run — search terms, item names, quantities?",
            "SENSITIVE" to "Does it touch passwords, payment or one-time-code fields? (Those are never stored — you'd type them at replay.)",
            "VERIFY" to "How do we know it succeeded — what should be on screen at the end?",
            "EDGES" to "What could go wrong mid-way (pop-ups, slow loads, sold-out items) and what should it do then?",
            "LIMITS" to "Where must it stop — anything it must never do (place an order, send a message, delete something)?"
        )

        /** Hard stop — the interview can never run away. */
        const val MAX_ROUNDS = 12

        private val SHORTCUTS = listOf("write it", "done", "that's all", "thats all", "enough", "go ahead", "write the skill")
    }

    var phase: Phase = Phase.IDLE
        private set

    private val transcript = mutableListOf<Pair<String, String>>()   // (topic, Q) → answer
    private val asked = LinkedHashSet<String>()
    private var rounds = 0
    private var seedGoal = ""

    fun start(seed: String) {
        phase = Phase.ASKING
        transcript.clear()
        asked.clear()
        rounds = 0
        seedGoal = seed.trim()
        listener.onLog("🎙 /grill-me engaged — I'll interview you, then write the skill draft. Answer each question; say 'write it' to skip ahead.")
        askNext()
    }
    fun onUserAnswer(answerRaw: String) {
        if (phase != Phase.ASKING && phase != Phase.REVIEWING) return
        val answer = answerRaw.trim()
        if (answer.isEmpty()) return
        rounds++

        if (phase == Phase.REVIEWING) {
            // User typed revision feedback in the review loop.
            revise(answer)
            return
        }

        val lastTopic = asked.lastOrNull()
        if (lastTopic != null && transcript.isNotEmpty()) {
            transcript[transcript.size - 1] = transcript.last().copy(second = answer)
        }

        if (SHORTCUTS.any { answer.lowercase().contains(it) } || rounds >= MAX_ROUNDS) {
            if (transcript.isEmpty()) {
                listener.onError("I need at least the outcome before I can write anything.")
                return
            }
            phase = Phase.GENERATING
            listener.onLog("🎙 Interview complete (${transcript.size} topics covered) — writing the skill draft…")
            return
        }
        askNext()
    }

    fun cancel() {
        phase = Phase.IDLE
    }

    /** Review loop: regenerate the draft from user feedback. */
    fun revise(feedback: String) {
        phase = Phase.GENERATING
        listener.onLog("🎙 Revising the draft: $feedback")
        pendingPrompt = buildString {
            appendLine("INTERVIEW TRANSCRIPT (topic: question → answer):")
            transcript.forEach { (q, a) -> appendLine("- $q → $a") }
            val prior = lastDraftJson
            if (prior != null) {
                appendLine()
                appendLine("PREVIOUS DRAFT:")
                appendLine(prior.take(4000))
            }
            appendLine()
            append("REVISION REQUEST: $feedback")
        }
    }

    private var lastDraftJson: String? = null
    private var pendingPrompt: String? = null

    // ------------------------------------------------------------- internals

    /**
     * Advances the state machine's network work in the caller's scope.
     * The panel calls this after every user answer (and after start()).
     */
    fun pump(scope: kotlinx.coroutines.CoroutineScope) {
        when (phase) {
            Phase.ASKING -> scope.launch { askAsync() }
            Phase.GENERATING -> scope.launch { generateAsync() }
            else -> {}
        }
    }

    private fun askNext() {
        val remaining = TOPICS.filter { it !in asked }
        if (remaining.isEmpty()) {
            phase = Phase.GENERATING
            listener.onLog("🎙 Interview complete (${transcript.size} topics covered) — writing the skill draft…")
            return
        }
        asked.add(remaining.first())
    }

    /**
     * Async question fetch. Falls back to the built-in bank on any failure.
     */
    suspend fun askAsync() {
        if (phase != Phase.ASKING) return
        val topic = asked.lastOrNull() ?: return
        val question: String = try {
            val system = buildString {
                appendLine("You are /grill-me, the skill interviewer inside Comet-X browser.")
                appendLine("Ask the user EXACTLY ONE targeted question for the topic below. Under 40 words. No numbering, no preamble — the question text only.")
                appendLine("Build on what they already told you. Cover edge cases in your question when relevant.")
                appendLine("TOPIC: $topic")
                if (seedGoal.isNotBlank()) appendLine("USER'S INITIAL IDEA: $seedGoal")
                if (transcript.isNotEmpty()) {
                    appendLine("SO FAR:")
                    transcript.forEach { (q, a) -> appendLine("- $q → $a") }
                }
            }
            val raw = router.chatWithFallback(
                ModelRouter.Role.FAST,
                listOf(ChatMessage("system", system), ChatMessage("user", "Ask the $topic question now.")),
                temperature = 0.3,
                maxTokens = 120
            )
            cleanQuestion(raw) ?: FALLBACK_QUESTIONS[topic]!!
        } catch (e: Exception) {
            Logx.w("grill-me phrasing fallback: ${e.message}")
            FALLBACK_QUESTIONS[topic]!!
        }
        transcript.add(question to "")
        listener.onQuestion(question)
    }

    suspend fun generateAsync() {
        if (phase != Phase.GENERATING) return
        val userPrompt = pendingPrompt ?: buildString {
            appendLine("INTERVIEW TRANSCRIPT (question → answer):")
            transcript.forEach { (q, a) -> appendLine("- $q → $a") }
        }
        pendingPrompt = null
        val system = buildString {
            appendLine("You convert an interview into a Comet-X skill JSON file. Output ONLY the JSON object — no markdown fences, no commentary.")
            appendLine("FORMAT (all keys required unless noted):")
            appendLine("""{"id":"grill-<short-unique>","name":"<short name>","description":"<one line>","start_url":"<https url or empty>","source":"grillme","steps":[...],"verification":"<how to tell success>","failure_handling":"<what to do when things fail>"}""")
            appendLine("Each step is ONE of:")
            appendLine("""{"action":"navigate","url":"https://..."}""")
            appendLine("""{"action":"click","target":{"text":"<button text>","aria":"<aria-label>","name":"<name attr>","tag":"button"}}""")
            appendLine("""{"action":"type","target":{"name":"<field name>","aria":"<label>","tag":"input"},"text":"<value>","submit":true|false}""")
            appendLine("""{"action":"select","target":{...},"option":"<option text>"}""")
            appendLine("""{"action":"scroll","direction":"down|up","amount":600}""")
            appendLine("""{"action":"wait","ms":2000}""")
            appendLine("""{"action":"back"}""")
            appendLine("RULES: at most 30 steps; target objects only include fields the user actually mentioned; use {\"action\":\"type\",\"sensitive\":true} with EMPTY text for password/payment/OTP fields; unknown specifics become generic but sensible steps; never invent credentials.")
            if (lastDraftJson != null) appendLine("This is a REVISION: apply the requested changes to the previous draft while keeping everything else.")
            appendLine()
            append(userPrompt)
        }
        try {
            val raw = router.chatWithFallback(
                ModelRouter.Role.AGENT,
                listOf(ChatMessage("system", system), ChatMessage("user", "Write the skill JSON now.")),
                temperature = 0.2,
                maxTokens = 2400
            )
            val obj = JsonFixtures.firstJsonObject(raw)
                ?: run {
                    // one repair round
                    val repair = router.chatWithFallback(
                        ModelRouter.Role.AGENT,
                        listOf(
                            ChatMessage("system", system),
                            ChatMessage("user", "Write the skill JSON now."),
                            ChatMessage("assistant", raw.take(1500)),
                            ChatMessage("user", "That was not one valid JSON object. Output ONLY the JSON object.")
                        ),
                        temperature = 0.0,
                        maxTokens = 2400
                    )
                    JsonFixtures.firstJsonObject(repair)
                }
            if (obj == null) {
                listener.onError("The model's draft was not valid JSON. Say 'write it' to try again.")
                phase = Phase.ASKING
                return
            }
            val skill = RecordedSkill.fromJson(obj)
            if (skill == null || skill.steps.isEmpty()) {
                listener.onError("The draft had no usable steps. Try revising your answers, e.g. 'make the steps more concrete'.")
                phase = Phase.ASKING
                return
            }
            val jsonText = obj.toString(2)
            lastDraftJson = jsonText
            phase = Phase.REVIEWING
            listener.onDraftReady(skill, jsonText)
        } catch (e: Exception) {
            Logx.e("grill-me generation failed", e)
            listener.onError("Draft generation failed: ${e.message?.take(120)}. Say 'write it' to retry.")
            phase = Phase.ASKING
        }
    }

    /** Called after the user saved or discarded the review dialog. */
    fun finishReview() {
        phase = Phase.IDLE
        lastDraftJson = null
        pendingPrompt = null
        listener.onEnded()
    }

    private fun cleanQuestion(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        if (t.length > 400) return null
        // strip common wrapping artifacts
        return t.removeSurrounding("\"").trim().ifBlank { null }
    }
}

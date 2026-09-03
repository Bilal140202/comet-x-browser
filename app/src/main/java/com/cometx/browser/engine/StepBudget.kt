package com.cometx.browser.engine

/**
 * StepBudget — adaptive step management (Phase 3).
 *
 * The agent no longer runs on a fixed step count it can silently exhaust.
 * Instead the budget is auto-tuned to the task and EXTENDS ITSELF while the
 * task demonstrably progresses — and never exceeds a hard ceiling:
 *
 *   1. INITIAL  = user preference (Settings "Max steps", default 24) plus a
 *      small complexity bump for long, multi-phase goals. Auto-set, no UI.
 *   2. EXTEND   = when the budget runs out but the task is visibly moving
 *      (successful actions, navigation through stages, little repetition),
 *      the budget grows by max(6, initial/3) steps — at most 3 extensions.
 *   3. CEILING  = ABSOLUTE_MAX (60). No path can ever exceed it: the user's
 *      "steps must not grow unbounded" rule is enforced structurally.
 *   4. DENY     = no extension for thrashing runs (repetition loops, strings
 *      of consecutive failures) — those are terminated instead of funded.
 *
 * Human gates (challenges, ask_user, takeover) and page-loading retries are
 * REFUNDED: only real agent decisions consume budget.
 */
class StepBudget private constructor(
    rawInitial: Int,
    private val hardMax: Int,
    goal: String
) {

    companion object {
        /** Absolute ceiling — extensions can never push the budget past this. */
        const val ABSOLUTE_MAX = 60

        /** Floor for any budget, user-set or auto-tuned. */
        const val ABSOLUTE_MIN = 4

        /** Maximum number of automatic extensions per task. */
        const val MAX_EXTENSIONS = 3

        /** A task must look at least this alive to earn more steps. */
        private const val EXTEND_THRESHOLD = 0.30

        /** Consecutive failed actions that disqualify an extension. */
        private const val FAILURE_RUN_LIMIT = 3

        /**
         * Complexity signal: long goals or goals with multiple phases
         * ("then", "after that", "and finally"…) get a larger starting budget.
         */
        fun complexityBonus(goal: String): Int {
            val g = goal.lowercase()
            if (g.isBlank()) return 0
            var score = 0
            if (g.length > 120) score += 3
            else if (g.length > 60) score += 2
            val phases = Regex("(\\bthen\\b|\\bafter that\\b|\\bafterwards\\b|\\bnext\\b|\\bfinally\\b|\\band then\\b)").findAll(g).count()
            score += (phases * 2).coerceAtMost(6)
            return score.coerceAtMost(6)
        }

        /** Auto-set the starting budget from the user preference + goal shape. */
        fun initialFor(userPref: Int, goal: String, hardMax: Int = ABSOLUTE_MAX): StepBudget {
            val base = userPref.coerceIn(ABSOLUTE_MIN, hardMax)
            val auto = (base + complexityBonus(goal)).coerceIn(ABSOLUTE_MIN, hardMax)
            return StepBudget(auto, hardMax, goal)
        }
    }

    val initial: Int = rawInitial.coerceIn(ABSOLUTE_MIN, hardMax)

    var budget: Int
        private set

    /** Steps that performed a real agent decision (excludes refunded gates). */
    var used: Int
        private set

    /** How many extensions were granted so far. */
    var extensions: Int
        private set

    private var successes = 0
    private var failures = 0
    private var navigations = 0
    private var repetitions = 0
    private var consecutiveFailures = 0

    init {
        budget = initial
        used = 0
        extensions = 0
    }

    fun hasRemaining(): Boolean = used < budget

    fun remaining(): Int = (budget - used).coerceAtLeast(0)

    /** Marks one step as consumed by a real decision. */
    fun consume() {
        used++
    }

    /** Human gate / page-load retry: the step did no model work — give it back. */
    fun refund() {
        if (used > 0) used--
    }

    /** Feed the outcome of an executed step into the progress model. */
    fun record(actionSuccess: Boolean, urlChanged: Boolean, repeated: Boolean) {
        if (actionSuccess) {
            successes++
            consecutiveFailures = 0
        } else {
            failures++
            consecutiveFailures++
        }
        if (urlChanged) navigations++
        if (repeated) repetitions++
    }

    /**
     * Heuristic 0..1 "is this task going anywhere" score from the recorded
     * outcomes: success ratio weighted highest, stage navigation helps,
     * repetition penalizes.
     */
    fun progressScore(): Double {
        val decisions = successes + failures
        if (decisions == 0) return 1.0
        val successRatio = successes.toDouble() / decisions
        val repetitionPenalty = (repetitions.toDouble() * 2 / decisions).coerceAtMost(1.0)
        val navBonus = if (navigations > 0) 0.15 else 0.0
        return (successRatio * 0.6 + navBonus + 0.25 * (1.0 - repetitionPenalty)).coerceIn(0.0, 1.0)
    }

    /**
     * True when the exhausted budget DESERVES an extension: the task shows
     * real progress, is not thrashing, and the ceiling has not been reached.
     */
    fun shouldExtend(): Boolean {
        if (extensions >= MAX_EXTENSIONS) return false
        if (budget >= hardMax) return false
        if (consecutiveFailures >= FAILURE_RUN_LIMIT) return false
        if (repetitions >= 3) return false
        return progressScore() >= EXTEND_THRESHOLD
    }

    /**
     * Grants one extension. Returns false when the hard ceiling or the
     * extension limit was reached (the caller must then stop the task).
     */
    fun extend(): Boolean {
        if (!shouldExtend()) return false
        val amount = maxOf(6, initial / 3)
        budget = minOf(hardMax, budget + amount)
        extensions++
        return true
    }

    /** Compact display for logs: "12/24", "30/32 (+8 ⤴)". */
    fun describe(): String =
        if (extensions == 0) "$used/$budget" else "$used/$budget (+$extensions⤴)"

    /** Amount the next extension would add (for logging/UX). */
    fun nextExtensionAmount(): Int = maxOf(6, initial / 3)
}

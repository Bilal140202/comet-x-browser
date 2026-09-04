package com.cometx.browser.skills

import org.json.JSONArray
import org.json.JSONObject

/**
 * RecordedSkill — a user-recorded procedure (Phase 3).
 *
 * The user taps Record, performs the task by hand in the browser, taps Stop —
 * every meaningful interaction becomes one [Step] here. Replay re-executes the
 * steps exactly, with three robustness layers:
 *
 *   1. TARGET SELECTOR CHAIN — a click is re-findable even after a site
 *      re-renders: id → name → aria-label → text → placeholder → CSS path.
 *      Coordinates are the last resort only.
 *   2. SENSITIVE MASKING — password / payment / OTP fields are never stored.
 *      The step keeps `sensitive = true` and asks the user at replay time.
 *   3. AI ASSIST — if every selector misses (site changed), the model may
 *      re-locate the element from the live DOM (opt-out in Settings).
 *
 * The JSON format is shared with the /grill-me interview so both producers
 * (recorder, interviewer) and the player speak one language.
 */
data class RecordedSkill(
    val id: String,
    val name: String,
    val description: String,
    val startUrl: String,
    val steps: List<Step>,
    val verification: String = "",
    val failureHandling: String = "",
    val source: String = SOURCE_RECORDER,   // "recorder" | "grillme"
    val createdAt: Long = System.currentTimeMillis(),
    val lastRunAt: Long = 0,
    val runCount: Int = 0
) {

    /** One recorded interaction. Field names mirror AgentDecision action JSON. */
    data class Step(
        val action: String,              // navigate|click|type|select|press_key|scroll|wait|back
        val url: String = "",            // navigate
        val target: Target? = null,      // click / type / select
        val text: String = "",           // type value / press_key key
        val option: String = "",         // select option
        val direction: String = "",      // scroll
        val amount: Int = 0,             // scroll px
        val ms: Int = 0,                 // wait
        val sensitive: Boolean = false,  // value masked, ask at replay
        val submit: Boolean = false      // type ended with Enter/submit
    )

    /**
     * Resolution chain for re-finding an element. Empty strings mean
     * "not captured". [cssPath] is a short structural path; [x]/[y] are the
     * viewport coordinates at record time (fallback only).
     */
    data class Target(
        val id: String = "",
        val name: String = "",
        val ariaLabel: String = "",
        val text: String = "",
        val placeholder: String = "",
        val tag: String = "",
        val cssPath: String = "",
        val x: Int = 0,
        val y: Int = 0
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id).put("name", name).put("aria", ariaLabel)
            .put("text", text).put("ph", placeholder).put("tag", tag)
            .put("css", cssPath).put("x", x).put("y", y)

        /** Number of distinct strategies available (coords don't count). */
        fun selectorCount(): Int =
            listOf(id, name, ariaLabel, text, placeholder, cssPath).count { it.isNotBlank() }

        companion object {
            fun fromJson(o: JSONObject): Target = Target(
                id = o.optString("id"),
                name = o.optString("name"),
                ariaLabel = o.optString("aria"),
                text = o.optString("text"),
                placeholder = o.optString("ph"),
                tag = o.optString("tag"),
                cssPath = o.optString("css"),
                x = o.optInt("x"),
                y = o.optInt("y")
            )
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("format", "cometx.skill.v1")
        .put("id", id)
        .put("name", name)
        .put("description", description)
        .put("start_url", startUrl)
        .put("source", source)
        .put("created_at", createdAt)
        .put("last_run_at", lastRunAt)
        .put("run_count", runCount)
        .put("verification", verification)
        .put("failure_handling", failureHandling)
        .put("steps", JSONArray().also { arr -> steps.forEach { arr.put(stepJson(it)) } })

    private fun stepJson(s: Step): JSONObject = JSONObject().also { o ->
        o.put("action", s.action)
        if (s.url.isNotBlank()) o.put("url", s.url)
        if (s.target != null) o.put("target", s.target.toJson())
        if (s.text.isNotBlank()) o.put("text", s.text)
        if (s.option.isNotBlank()) o.put("option", s.option)
        if (s.direction.isNotBlank()) o.put("direction", s.direction)
        if (s.amount != 0) o.put("amount", s.amount)
        if (s.ms != 0) o.put("ms", s.ms)
        if (s.sensitive) o.put("sensitive", true)
        if (s.submit) o.put("submit", true)
    }

    /** Human-readable one-line summary (dialogs, chips). */
    fun summaryLine(): String = "$name — ${steps.size} step(s)" +
        (if (description.isNotBlank()) " · $description" else "")

    companion object {
        const val SOURCE_RECORDER = "recorder"
        const val SOURCE_GRILLME = "grillme"

        val PLAYABLE_ACTIONS = setOf("navigate", "click", "type", "select", "press_key", "scroll", "wait", "back")

        fun fromJson(o: JSONObject): RecordedSkill? {
            val id = o.optString("id").ifBlank { return null }
            val name = o.optString("name").ifBlank { "Unnamed skill" }
            val steps = mutableListOf<Step>()
            val arr = o.optJSONArray("steps") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val action = s.optString("action").lowercase().trim()
                if (action !in PLAYABLE_ACTIONS) continue  // never replay an unknown verb
                steps.add(
                    Step(
                        action = action,
                        url = s.optString("url"),
                        target = s.optJSONObject("target")?.let { Target.fromJson(it) },
                        text = s.optString("text"),
                        option = s.optString("option"),
                        direction = s.optString("direction"),
                        amount = s.optInt("amount"),
                        ms = s.optInt("ms"),
                        sensitive = s.optBoolean("sensitive", false),
                        submit = s.optBoolean("submit", false)
                    )
                )
            }
            return RecordedSkill(
                id = id,
                name = name,
                description = o.optString("description"),
                startUrl = o.optString("start_url"),
                steps = steps,
                verification = o.optString("verification"),
                failureHandling = o.optString("failure_handling"),
                source = o.optString("source", SOURCE_RECORDER).ifBlank { SOURCE_RECORDER },
                createdAt = o.optLong("created_at", System.currentTimeMillis()),
                lastRunAt = o.optLong("last_run_at", 0),
                runCount = o.optInt("run_count", 0)
            )
        }

        fun parse(text: String): RecordedSkill? =
            runCatching { fromJson(JSONObject(text)) }.getOrNull()

        /** Defensive normalisation applied to everything the recorder/interview captures. */
        fun sanitizeUrl(url: String): String {
            val u = url.trim()
            return if (u.startsWith("http://") || u.startsWith("https://")) u.take(2048) else ""
        }
    }
}

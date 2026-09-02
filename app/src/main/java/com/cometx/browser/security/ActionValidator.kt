package com.cometx.browser.security

import org.json.JSONObject

/**
 * ActionValidator — the structural gate between model output and the engine.
 * Validates the parsed action against the protocol schema and the current
 * observation before anything executes. Pure Kotlin — unit tested on the JVM.
 */
object ActionValidator {

    /** Observation element as exposed to validation (subset of PageObservation.Element). */
    data class ElementRef(val ref: String, val tag: String, val inputType: String?, val rectW: Double, val rectH: Double)

    data class Observation(
        val url: String,
        val viewportW: Int,
        val viewportH: Int,
        val elements: List<ElementRef>,
        val tabCount: Int
    )

    sealed class Verdict {
        object Ok : Verdict()
        data class Reject(val reason: String) : Verdict()
    }

    private val KNOWN_ACTIONS = setOf(
        "navigate", "back", "forward", "reload",
        "click", "click_at", "type", "press_key", "scroll", "select",
        "wait", "find_text", "find_element", "extract", "screenshot",
        "open_tab", "close_tab", "switch_tab", "download",
        "copy", "paste", "zoom",
        "done", "fail", "ask_user", "remember", "request_vision"
    )

    private val ALLOWED_KEYS = setOf(
        "action", "note", "url", "ref", "x", "y", "text", "submit", "key",
        "direction", "amount", "option", "ms", "what", "index", "level",
        "summary", "reason", "question", "key_name", "fact", "target", "selector"
    )

    private val ALLOWED_KEY_ACTIONS = setOf("Enter", "Tab", "Escape", "ArrowDown", "ArrowUp", "ArrowLeft", "ArrowRight", "PageDown", "PageUp", "Home", "End", "Backspace", "Delete")
    private val ALLOWED_SCROLL = setOf("up", "down", "top", "bottom")

    private val REF_ACTIONS = setOf("click", "type", "select", "paste", "download")

    fun validate(a: JSONObject, obs: Observation?): Verdict {
        val action = a.optString("action", "")
        if (action !in KNOWN_ACTIONS) return Verdict.Reject("unknown action '$action'")

        for (key in a.keys()) {
            if (key !in ALLOWED_KEYS) return Verdict.Reject("unknown field '$key' for action $action")
        }

        when (action) {
            "navigate" -> {
                checkUrl(a.optString("url", ""))?.let { return it }
            }
            "open_tab" -> {
                val url = a.optString("url", "")
                if (url.isNotBlank()) checkUrl(url)?.let { return it }
            }
            "download" -> {
                val url = a.optString("url", "")
                if (url.isNotBlank()) checkUrl(url)?.let { return it }
            }
            "click", "type", "select", "paste", "download" -> {
                val ref = a.optString("ref", "")
                if (ref.isBlank()) return Verdict.Reject("$action requires a ref")
                obs?.let { o ->
                    if (o.elements.none { it.ref == ref }) return Verdict.Reject("ref '$ref' not present in current observation")
                }
            }
            "click_at" -> {
                val x = a.optDouble("x", Double.NaN)
                val y = a.optDouble("y", Double.NaN)
                if (x.isNaN() || y.isNaN()) return Verdict.Reject("click_at requires numeric x,y")
                obs?.let { o ->
                    if (x < 0 || y < 0 || x > o.viewportW || y > o.viewportH)
                        return Verdict.Reject("click_at ($x,$y) outside viewport ${o.viewportW}x${o.viewportH}")
                }
            }
            "type" -> {
                val text = a.optString("text", "")
                if (text.length > 4000) return Verdict.Reject("text too long (max 4000)")
            }
            "press_key" -> {
                val key = a.optString("key", "")
                if (key !in ALLOWED_KEY_ACTIONS) return Verdict.Reject("unsupported key '$key'")
            }
            "scroll" -> {
                val dir = a.optString("direction", "down")
                if (dir !in ALLOWED_SCROLL) return Verdict.Reject("scroll direction must be one of $ALLOWED_SCROLL")
                val amount = a.optInt("amount", 600)
                if (amount < 10 || amount > 5000) return Verdict.Reject("scroll amount out of range (10..5000)")
            }
            "wait" -> {
                val ms = a.optInt("ms", 800)
                if (ms < 100 || ms > 15_000) return Verdict.Reject("wait ms out of range (100..15000)")
            }
            "find_text" -> if (a.optString("text", "").isBlank()) return Verdict.Reject("find_text requires text")
            "find_element" -> if (a.optString("description", a.optString("text", "")).isBlank())
                return Verdict.Reject("find_element requires a description")
            "extract" -> {
                val what = a.optString("what", "text")
                if (what !in setOf("text", "links", "tables", "all")) return Verdict.Reject("extract what must be text|links|tables|all")
            }
            "switch_tab", "close_tab" -> {
                obs?.let { o ->
                    val idx = a.optInt("index", 0)
                    if (idx < 0 || idx >= o.tabCount) return Verdict.Reject("tab index $idx out of range (0..${o.tabCount - 1})")
                }
            }
            "zoom" -> {
                val level = a.optString("level", "")
                if (level.isNotEmpty() && level !in setOf("in", "out", "reset") && (a.optInt("level", -1) !in 30..300))
                    return Verdict.Reject("zoom level must be in|out|reset or 30..300")
            }
            "done" -> if (a.optString("summary", "").isBlank()) return Verdict.Reject("done requires a summary")
            "fail" -> if (a.optString("reason", "").isBlank()) return Verdict.Reject("fail requires a reason")
            "ask_user" -> if (a.optString("question", "").isBlank()) return Verdict.Reject("ask_user requires a question")
            "remember" -> {
                if (a.optString("key_name", "").isBlank()) return Verdict.Reject("remember requires key_name")
                if (a.optString("fact", "").length > 500) return Verdict.Reject("remember fact too long (max 500)")
            }
        }

        if (obs != null && isRefAction(action)) {
            // Guard against interacting with invisible or non-rendered targets.
            val el = obs.elements.firstOrNull { it.ref == a.optString("ref", "") }
            if (el != null && el.rectW <= 1 && el.rectH <= 1)
                return Verdict.Reject("target element has no visible size")
        }
        return Verdict.Ok
    }

    private fun isRefAction(action: String) = action in REF_ACTIONS

    /** Shared URL policy for every action that carries a url field. */
    private fun checkUrl(url: String): Verdict? {
        val lower = url.trim().lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://"))
            return Verdict.Reject("requires an http(s) URL, got '${url.take(80)}'")
        if (url.length > 2048) return Verdict.Reject("URL too long")
        if (PromptInjectionDetector.suspiciousUrl(url)) return Verdict.Reject("URL carries credential-shaped parameters")
        return null
    }
}

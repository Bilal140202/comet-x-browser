package com.cometx.browser.ai

import org.json.JSONObject

/**
 * AgentDecision — the canonical, protocol-independent result of one model turn
 * (Phase 2 §7/§8). Whether the model spoke JSON Schema, JSON mode, a tool call,
 * or a tagged-text block, the interpreter layer reduces it to this record and
 * the agent engine NEVER sees the raw wire format.
 *
 * [toActionJson] maps onto the action vocabulary already consumed by
 * ActionValidator / SafetyPolicy / ActionExecutor, so the entire validated
 * execution pipeline is unchanged.
 */
data class AgentDecision(
    val action: String,                       // canonical lowercase verb (click, type, done …)
    val target: String? = null,               // element ref, e.g. "e7"
    val value: String? = null,                // text / url / option / question / summary / reason …
    val reason: String? = null,               // model's "note": why this action
    val confidence: Double? = null,
    val extras: Map<String, String> = emptyMap(), // action-specific fields (x, y, key, direction …)
    val done: Boolean = false
) {

    /** Terminal completion marker convenience. */
    val isTerminal: Boolean get() = done || action in TERMINAL_ACTIONS

    /** Field lookup used by interpreters when mapping protocol keys → canonical fields. */
    operator fun get(field: String): String? = when (field) {
        "ref", "target" -> target
        "value" -> value
        "note", "reason" -> reason
        else -> extras[field]
    }

    /**
     * Render as the action JSON the downstream pipeline (validator → safety →
     * executor) already understands. Unknown extras are passed through verbatim
     * so new model fields never need a decision-model change.
     */
    fun toActionJson(): JSONObject {
        val o = JSONObject()
        o.put("action", action)
        target?.takeIf { it.isNotBlank() }?.let { o.put("ref", it) }
        reason?.takeIf { it.isNotBlank() }?.let { o.put("note", it) }
        confidence?.let { o.put("confidence", it) }
        for ((k, v) in extras) {
            if (k == "action" || k == "ref" || k == "note" || o.has(k)) continue
            o.put(k, v)
        }
        // value maps per-action so executors keep their expected field names
        if (!value.isNullOrBlank()) {
            val v: String = value
            when (action) {
                "type" -> if (!o.has("text")) o.put("text", v)
                "navigate", "open_tab", "download" -> if (!o.has("url")) o.put("url", v)
                "select" -> if (!o.has("option")) o.put("option", v)
                "find_text" -> if (!o.has("text")) o.put("text", v)
                "find_element" -> if (!o.has("description")) o.put("description", v)
                "remember" -> if (!o.has("fact")) o.put("fact", v)
                "done" -> if (!o.has("summary")) o.put("summary", v)
                "fail" -> if (!o.has("reason")) o.put("reason", v)
                "ask_user" -> if (!o.has("question")) o.put("question", v)
                "press_key" -> if (!o.has("key")) o.put("key", v)
                "zoom" -> if (!o.has("level")) o.put("level", v)
                "wait" -> if (v.toIntOrNull() != null && !o.has("ms")) o.put("ms", v.toInt())
                else -> {}
            }
        }
        return o
    }

    companion object {
        val TERMINAL_ACTIONS = setOf("done", "fail", "ask_user")

        /** Normalize a model-verb: trims, lowercases, maps synonyms. */
        fun normalizeAction(raw: String): String? {
            val v = raw.trim().lowercase().replace('-', '_').replace(' ', '_')
            if (v.isBlank()) return null
            val canonical = when (v) {
                "goto", "go_to", "open", "visit" -> "navigate"
                "tap" -> "click"
                "input", "fill", "enter_text", "write" -> "type"
                "key", "press" -> "press_key"
                "scroll_down", "scroll_up", "scroll_top", "scroll_bottom" -> "scroll"
                "stop", "finish", "complete", "complete_task", "task_done" -> "done"
                "abort", "give_up", "error" -> "fail"
                "question", "ask", "clarify" -> "ask_user"
                "capture", "take_screenshot" -> "screenshot"
                "new_tab" -> "open_tab"
                "select_tab" -> "switch_tab"
                else -> v
            }
            return canonical.takeIf { it in KNOWN_ACTIONS }
        }

        /** Every action the engine can legally execute (mirrors AgentPrompt schema). */
        val KNOWN_ACTIONS = setOf(
            "navigate", "back", "forward", "reload", "click", "click_at", "type", "press_key",
            "scroll", "select", "wait", "find_text", "find_element", "extract", "screenshot",
            "request_vision", "open_tab", "close_tab", "switch_tab", "download", "copy", "paste",
            "zoom", "remember", "done", "fail", "ask_user"
        )
    }
}

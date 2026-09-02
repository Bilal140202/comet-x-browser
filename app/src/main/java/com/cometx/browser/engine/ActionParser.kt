package com.cometx.browser.engine

import org.json.JSONObject

/**
 * ActionParser — robust extraction of the action JSON from LLM output.
 * Handles: raw JSON, markdown fences, leading/trailing prose, code blocks.
 * Pure Kotlin — unit tested on the JVM.
 */
object ActionParser {

    /**
     * @return parsed action object or null if nothing parseable found
     */
    fun parse(raw: String): JSONObject? {
        if (raw.isBlank()) return null
        var text = raw.trim()

        // 1) strip markdown fences
        text = text.replace(Regex("""```(?:json)?""", RegexOption.IGNORE_CASE), "").trim()

        // 2) direct parse
        try {
            return normalize(JSONObject(text))
        } catch (_: Exception) { /* fall through to salvage */ }

        // 3) salvage balanced {...} from prose-wrapped output
        return salvage(text)
    }

    private fun normalize(o: JSONObject): JSONObject? {
        // Tolerate {"action": {...}} nesting from over-eager models
        val actionVal = o.opt("action")
        if (actionVal is JSONObject) return actionVal
        if (o.optString("action", "").isNotBlank()) return o
        // Tolerate {"name": "..."} style
        val name = o.optString("name", "").lowercase()
        if (name.isNotBlank()) {
            o.put("action", name)
            o.remove("name")
            return o
        }
        // Tolerate {"tool": "..."}
        val tool = o.optString("tool", "").lowercase()
        if (tool.isNotBlank()) {
            o.put("action", tool)
            o.remove("tool")
            return o
        }
        return null
    }

    /**
     * Salvage path: find the first balanced {...} block in free text.
     * (Used when direct parse fails — models sometimes prefix commentary.)
     */
    fun salvage(raw: String): JSONObject? {
        val text = raw.replace(Regex("""```(?:json)?""", RegexOption.IGNORE_CASE), "")
        var depth = 0
        var start = -1
        var inString = false
        var escaped = false
        for (i in text.indices) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> { if (depth == 0) start = i; depth++ }
                '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        val candidate = text.substring(start, i + 1)
                        try {
                            return normalize(JSONObject(candidate))
                        } catch (_: Exception) { start = -1 }
                    }
                }
            }
        }
        return null
    }
}

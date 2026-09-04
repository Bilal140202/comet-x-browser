package com.cometx.browser.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * ModelResponseInterpreter (Phase 2 §7): turns ONE raw model answer into an
 * [AgentDecision], regardless of which wire protocol produced it.
 *
 * Implementations:
 *  - [JsonInterpreter]        JSON Schema / JSON mode outputs
 *  - [ToolCallInterpreter]    OpenAI-style tool_calls envelope
 *  - [TaggedTextInterpreter]  KEY=VALUE block protocol (<agent> fenced or bare)
 *  - [PlainTextInterpreter]   tolerant last resort — still structured lines,
 *                             NEVER free prose ("do not execute arbitrary
 *                             natural-language output as commands", §9)
 */
interface ModelResponseInterpreter {
    /** @return a decision, or null when the raw output does not fit this protocol. */
    fun interpret(raw: String): AgentDecision?
}

object ResponseInterpreters {

    fun forProtocol(p: AgentProtocol): ModelResponseInterpreter = when (p) {
        AgentProtocol.JSON_SCHEMA, AgentProtocol.JSON_OBJECT -> JsonInterpreter()
        AgentProtocol.TOOL_CALLING -> ToolCallInterpreter()
        AgentProtocol.TAGGED_TEXT -> TaggedTextInterpreter()
        AgentProtocol.PLAIN_TEXT -> PlainTextInterpreter()
    }

    /** JSON path: strict parse → salvage balanced braces → normalize shapes. */
    class JsonInterpreter : ModelResponseInterpreter {
        override fun interpret(raw: String): AgentDecision? {
            val o = JsonFixtures.firstJsonObject(raw) ?: return null
            return decisionFromJson(o)
        }
    }

    /** Tool-call path: reads choices[0].message.tool_calls[0]{function{name,arguments}}. */
    class ToolCallInterpreter : ModelResponseInterpreter {
        override fun interpret(raw: String): AgentDecision? {
            val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
            val msg = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message") ?: return root.toDecision()
            val calls = msg.optJSONArray("tool_calls") ?: return msg.toDecision()
            for (i in 0 until calls.length()) {
                val call = calls.optJSONObject(i) ?: continue
                val fn = call.optJSONObject("function") ?: continue
                val argsRaw = fn.optString("arguments", "{}")
                val args = runCatching { JSONObject(argsRaw) }.getOrNull()
                    ?: JsonFixtures.firstJsonObject(argsRaw)
                    ?: continue
                return decisionFromJson(args, fallbackAction = fn.optString("name"))
            }
            return null
        }

        /** Bare {"name": ..., "arguments": {...}} envelope (some routers). */
        private fun JSONObject.toDecision(): AgentDecision? {
            val fn = optJSONObject("function") ?: return null
            val args = runCatching { JSONObject(fn.optString("arguments", "{}")) }.getOrNull() ?: JSONObject()
            return decisionFromJson(args, fallbackAction = fn.optString("name"))
        }
    }

    /**
     * Tagged-text protocol (§9). Accepts:
     *   <agent> ACTION=CLICK \n REF=e7 \n NOTE=… </agent>
     *   ACTION: CLICK / REF: e7   (bare lines)
     * Tolerates whitespace, capitalization, quotes, harmless prose around the
     * block. REJECTS unknown actions and ambiguous shapes (returns null → the
     * router asks the model to repair; nothing ambiguous is ever executed).
     */
    class TaggedTextInterpreter(private val lenient: Boolean = false) : ModelResponseInterpreter {

        override fun interpret(raw: String): AgentDecision? {
            val text = raw.trim()
            if (text.isEmpty()) return null

            // 1) fenced <agent>…</agent> block wins if present
            val fenced = Regex("""<agent>(.*?)</agent>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                .find(text)?.groupValues?.get(1)

            // 2) otherwise: a fenced JSON block or bare {…} → delegate to JSON
            if (fenced == null) {
                if (text.contains('{') && text.contains('}')) {
                    JsonInterpreter().interpret(text)?.let { return it }
                }
                val body = text
                val fields = parseKeyValueBody(body) ?: return null
                return decisionFromFields(fields, lenient)
            }
            val fields = parseKeyValueBody(fenced) ?: return null
            return decisionFromFields(fields, lenient)
        }

        /** Parses ACTION=… / ACTION: … lines; ignores prose lines. */
        private fun parseKeyValueBody(body: String): Map<String, String>? {
            val out = LinkedHashMap<String, String>()
            for (rawLine in body.lines()) {
                val line = rawLine.trim().trimEnd(',', ';')
                if (line.isEmpty() || line.startsWith("```")) continue
                val m = Regex("""^([A-Za-z_][A-Za-z0-9_ ]{0,24})\s*[:=]\s*(.*)$""").find(line) ?: continue
                val key = m.groupValues[1].trim().lowercase().replace(' ', '_')
                var value = m.groupValues[2].trim()
                if ((value.startsWith("\"") && value.endsWith("\"") && value.length >= 2) ||
                    (value.startsWith("'") && value.endsWith("'") && value.length >= 2)) {
                    value = value.substring(1, value.length - 1).trim()
                }
                if (key in KNOWN_KEYS && value.isNotEmpty()) out[key] = value
            }
            return out.takeIf { it.containsKey("action") }
        }

        companion object {
            val KNOWN_KEYS = setOf(
                "action", "ref", "target", "element", "value", "text", "url", "note", "reason",
                "summary", "question", "key", "direction", "amount", "ms", "option", "what",
                "description", "index", "level", "key_name", "fact", "x", "y", "submit",
                "confidence"
            )
        }
    }

    /** Last-resort: tagged parser with prose-line tolerance. Still refuses free prose. */
    class PlainTextInterpreter : ModelResponseInterpreter {
        private val tagged = TaggedTextInterpreter(lenient = true)
        private val json = JsonInterpreter()
        override fun interpret(raw: String): AgentDecision? =
            json.interpret(raw) ?: tagged.interpret(raw)
    }

    // ------------------------------------------------------------- helpers

    /** Build a decision from a JSON action object, tolerating shape drift. */
    fun decisionFromJson(o: JSONObject, fallbackAction: String? = null): AgentDecision? {
        // tolerate {"action": {…}} nesting from over-eager models
        val obj = when (val a = o.opt("action")) {
            is JSONObject -> {
                val inner = a
                // keep outer extras (note etc.) that the inner lacks
                for (k in o.keys()) if (!inner.has(k)) inner.put(k, o.opt(k))
                inner
            }
            is String -> o
            else -> o
        }
        var verb = obj.optString("action", "").ifBlank {
            obj.optString("name", "").ifBlank { obj.optString("tool", "") }
        }
        if (verb.isBlank() && fallbackAction != null) verb = fallbackAction
        // strip function-name style: browser_click → click
        verb = verb.trim().lowercase().removePrefix("browser_").removePrefix("agent_")
        val action = AgentDecision.normalizeAction(verb) ?: return null

        val extras = LinkedHashMap<String, String>()
        for (k in obj.keys()) {
            if (k in setOf("action", "name", "tool", "ref", "target", "note", "reason",
                    "summary", "question", "confidence", "value", "text", "url", "option",
                    "fact", "key", "level")) continue
            val v = obj.opt(k)
            val s = when (v) {
                is String -> v
                is Number, is Boolean -> v.toString()
                null -> continue
                else -> continue   // nested structures are not actionable extras
            }
            if (s.isNotBlank()) extras[k] = s
        }

        fun str(vararg keys: String): String? {
            for (k in keys) {
                val v = obj.opt(k)
                when (v) {
                    is String -> if (v.isNotBlank()) return v
                    is Number, is Boolean -> return v.toString()
                }
            }
            return null
        }

        return AgentDecision(
            action = action,
            target = str("ref", "target", "element"),
            value = str("value", "text", "url", "option", "question", "summary", "reason", "fact", "key", "level")
                ?: extras["ms"]?.let { it },
            reason = str("note", "reason")?.takeIf { it != str("summary") },
            confidence = if (obj.has("confidence")) obj.optDouble("confidence") else null,
            extras = extras,
            done = action == "done"
        )
    }

    /**
     * Build a decision from KEY=VALUE fields of the tagged protocol. All
     * recognizable keys flow into canonical fields; the rest ride in extras so
     * [AgentDecision.toActionJson] can pass them through.
     */
    fun decisionFromFields(fields: Map<String, String>, lenient: Boolean): AgentDecision? {
        val verb = fields["action"] ?: return null
        val action = AgentDecision.normalizeAction(verb) ?: return null

        fun pick(vararg keys: String): String? = keys.firstNotNullOfOrNull { fields[it] }

        val target = pick("ref", "target", "element")
        val value = pick("value", "url", "option", "question", "summary", "reason", "fact",
            "key", "level", "text")
        val reason = pick("note", "reason")
        val consumed = setOf("action", "ref", "target", "element", "value", "url", "option",
            "question", "summary", "reason", "fact", "key", "level", "text", "note")
        val extras = fields.filterKeys { it !in consumed }

        return AgentDecision(
            action = action,
            target = target,
            value = value,
            reason = reason,
            confidence = pick("confidence")?.toDoubleOrNull(),
            extras = extras,
            done = action == "done"
        )
    }
}

/** JSON salvage helpers shared by interpreters. */
object JsonFixtures {

    /** First balanced {…} object in free text (fences/prose tolerated). */
    fun firstJsonObject(raw: String): JSONObject? {
        val text = raw.replace(Regex("""```(?:json)?""", RegexOption.IGNORE_CASE), "")
        runCatching { return JSONObject(text.trim()) }
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
                        runCatching { return JSONObject(candidate) }
                        start = -1
                    }
                }
            }
        }
        return null
    }
}

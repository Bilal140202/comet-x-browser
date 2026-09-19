package com.cometx.browser.ai.web

import com.cometx.browser.ai.ChatMessage
import com.cometx.browser.util.Json
import org.json.JSONArray
import org.json.JSONObject

/**
 * WebLlmProtocol (v2.1.0) — the JSON wire contract between Kotlin and the
 * Transformers.js runtime page (assets/webllm/runtime.js). Kept in lockstep
 * with the JS file by contract tests in WebLlmProtocolTest.
 *
 * JS → native events (posted as one JSON string through the bridge):
 *   { t:"boot" }                                     page module up
 *   { t:"log", msg }                                 diagnostic line
 *   { t:"status", phase, model?, pct?, loaded?, total?, file? }
 *   { t:"ready", model }                             pipeline resident
 *   { t:"stream", sid, text }                        incremental decode chunk
 *   { t:"done", sid, text }                          final completion
 *   { t:"error", sid?, msg }                         load or generate failure
 *
 * native → JS commands (evaluated as window.__cometxAI.handle(<json>)):
 *   { op:"load", model, dtype }
 *   { op:"generate", sid, messages:[{role,content}], maxTokens, temperature }
 *   { op:"interrupt", sid }
 *
 * Parsing is TOTAL: any malformed or unknown payload parses to null/Log —
 * a hostile or broken page must never crash the provider (P-SEC posture).
 */
sealed class WebEvent {
    /** The runtime page finished importing and is accepting commands. */
    object Boot : WebEvent()

    data class Log(val msg: String) : WebEvent()

    /** Load progress / lifecycle status line from the runtime. */
    data class Status(
        val phase: String,
        val model: String? = null,
        val pct: Double = 0.0,
        val loadedBytes: Long = 0L,
        val totalBytes: Long = 0L,
        val file: String? = null,
    ) : WebEvent()

    data class Ready(val model: String?) : WebEvent()

    /** One incremental decode chunk for generation [sid]. */
    data class Stream(val sid: Long, val text: String) : WebEvent()

    /** Final, authoritative completion text for generation [sid]. */
    data class Done(val sid: Long, val text: String) : WebEvent()

    /** Failure; [sid] null → the failure belongs to the load phase. */
    data class Failed(val sid: Long?, val msg: String) : WebEvent()
}

object WebLlmProtocol {

    /** Unknown/malformed input → null. Never throws. */
    fun parseEvent(raw: String?): WebEvent? {
        if (raw.isNullOrBlank()) return null
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        return runCatching { parseObject(obj) }.getOrNull()
    }

    private fun parseObject(o: JSONObject): WebEvent? = when (o.optString("t")) {
        "boot" -> WebEvent.Boot
        "log" -> WebEvent.Log(o.optString("msg"))
        "status" -> WebEvent.Status(
            phase = o.optString("phase"),
            model = o.optStringOrNull("model"),
            pct = if (o.has("pct")) o.optDouble("pct") else 0.0,
            loadedBytes = o.optLong("loaded"),
            totalBytes = o.optLong("total"),
            file = o.optStringOrNull("file"),
        )
        "ready" -> WebEvent.Ready(o.optStringOrNull("model"))
        "stream" -> WebEvent.Stream(o.optLong("sid"), o.optString("text"))
        "done" -> WebEvent.Done(o.optLong("sid"), o.optString("text"))
        "error" -> WebEvent.Failed(
            sid = if (o.has("sid")) o.optLong("sid") else null,
            msg = o.optString("msg"),
        )
        else -> null
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        val v = optString(key, "")
        return v.takeIf { it.isNotEmpty() }
    }

    // ------------------------------------------------------------ commands

    fun loadCommand(model: String, dtype: String): String =
        Json.obj("op", "load", "model", model, "dtype", dtype).toString()

    fun generateCommand(
        sid: Long,
        messages: List<ChatMessage>,
        maxTokens: Int,
        temperature: Double,
    ): String {
        val arr = JSONArray()
        for (m in messages) {
            arr.put(Json.obj("role", m.role, "content", m.text ?: ""))
        }
        return Json.obj(
            "op", "generate",
            "sid", sid,
            "messages", arr,
            "maxTokens", maxTokens,
            "temperature", temperature,
        ).toString()
    }

    fun interruptCommand(sid: Long): String =
        Json.obj("op", "interrupt", "sid", sid).toString()
}

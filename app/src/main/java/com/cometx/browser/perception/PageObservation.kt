package com.cometx.browser.perception

import org.json.JSONArray
import org.json.JSONObject

/**
 * PageObservation — the agent's structured view of the browser state.
 * Built from DOM extraction + page metadata; optionally augmented by vision.
 * Designed to be compact (token budget matters) and stable (refs persist for
 * the duration of one observation-generation).
 */
data class PageObservation(
    val url: String,
    val title: String,
    val viewportW: Int,
    val viewportH: Int,
    val scrollY: Int,
    val scrollMax: Int,
    val elements: List<Element>,
    val forms: List<Form>,
    val tabs: List<TabInfo>,
    val activeTabIndex: Int,
    val textSample: String,
    val injectionSignals: List<String> = emptyList(),
    val challenge: ChallengeResult? = null,
    val vision: VisionDescription? = null,
    val lastActionResult: String? = null
) {
    data class Element(
        val ref: String,
        val tag: String,
        val type: String?,        // input type
        val role: String?,        // ARIA role
        val label: String?,       // aria-label / alt / title
        val name: String?,        // name attribute
        val placeholder: String?,
        val text: String?,        // visible text (trimmed)
        val value: String?,       // input value (passwords masked)
        val href: String?,
        val x: Int, val y: Int, val w: Int, val h: Int,
        val disabled: Boolean,
        val required: Boolean
    ) {
        fun isPassword(): Boolean = (type ?: "").equals("password", true)
        fun describe(): String = buildString {
            append(tag)
            type?.let { append("[$it]") }
            role?.let { append("(role=$it)") }
            listOfNotNull(label, placeholder, text, value, name).map { it.trim() }.firstOrNull { it.isNotEmpty() }?.let {
                append(" \"${it.take(70)}\"")
            }
        }
    }

    data class Form(val index: Int, val action: String?, val method: String?, val fields: Int, val hasPassword: Boolean)

    data class TabInfo(val index: Int, val title: String, val url: String, val isActive: Boolean)

    data class VisionDescription(val summary: String, val note: String?)

    fun toCompactJson(): JSONObject {
        val elArr = JSONArray()
        for (e in elements) {
            elArr.put(JSONObject().apply {
                put("ref", e.ref)
                put("tag", e.tag)
                e.type?.let { put("type", it) }
                e.role?.let { put("role", it) }
                e.label?.takeIf { it.isNotBlank() }?.let { put("label", it) }
                e.placeholder?.takeIf { it.isNotBlank() }?.let { put("ph", it) }
                e.text?.takeIf { it.isNotBlank() }?.let { put("text", it) }
                e.value?.takeIf { it.isNotBlank() }?.let { put("value", it) }
                e.href?.takeIf { it.isNotBlank() }?.let { put("href", it.take(90)) }
                put("x", e.x); put("y", e.y); put("w", e.w); put("h", e.h)
                if (e.disabled) put("disabled", true)
                if (e.required) put("required", true)
            })
        }
        val tabArr = JSONArray()
        for (t in tabs) {
            tabArr.put(JSONObject().apply {
                put("i", t.index)
                put("title", t.title.take(50))
                put("active", t.isActive)
            })
        }
        return JSONObject().apply {
            put("url", url)
            put("title", title.take(120))
            put("viewport", "$viewportW x $viewportH")
            put("scroll", JSONObject().put("y", scrollY).put("max", scrollMax))
            put("elements", elArr)
            put("tabs", tabArr)
            put("page_text_sample", textSample.take(600))
            if (forms.isNotEmpty()) {
                val fArr = JSONArray()
                for (f in forms) fArr.put(JSONObject().apply {
                    put("index", f.index)
                    f.action?.let { put("action", it.take(80)) }
                    f.method?.let { put("method", it) }
                    put("fields", f.fields)
                    if (f.hasPassword) put("has_password", true)
                })
                put("forms", fArr)
            }
            if (injectionSignals.isNotEmpty()) put("injection_signals", injectionSignals)
            challenge?.let { put("challenge", JSONObject().put("type", it.type).put("detail", it.detail)) }
            vision?.let { put("vision", JSONObject().put("summary", it.summary.take(900)).let { v -> it.note?.let { n -> v.put("note", n.take(300)) }; v }) }
            lastActionResult?.let { put("last_action_result", it) }
        }
    }
}

/** Result of ChallengeDetector evaluation. */
data class ChallengeResult(val type: String, val detail: String) {
    companion object {
        const val NONE = "none"
        const val CAPTCHA = "captcha"
        const val CLOUDFLARE = "cloudflare_challenge"
        const val MFA = "mfa_screen"
        const val RATE_LIMIT = "rate_limit"
        const val SUSPECTED = "suspected_verification"
    }
}

package com.cometx.browser.security

import org.json.JSONObject

/**
 * SafetyPolicy — decides whether a proposed action is consequential enough
 * that a human must confirm it first (brief §15). Pure Kotlin — unit tested.
 *
 * The model proposes; this policy decides. It never executes anything.
 */
object SafetyPolicy {

    enum class Risk { NONE, CONFIRM, BLOCK }

    data class Assessment(val risk: Risk, val reason: String)

    private val PAYMENT_URL = Regex("""(?i)(checkout|payment|pay|billing|order[^a-z]|cart|invoice|subscription|purchase)""")
    private val AUTH_URL = Regex("""(?i)(login|signin|sign-in|signup|register|password|account/settings|security)""")
    private val MAIL_URL = Regex("""(?i)(mail\.|outlook\.|gmail\.|inbox\.|compose)""")
    private val SOCIAL_URL = Regex("""(?i)(twitter|x\.com|facebook|instagram|linkedin|reddit|telegram|whatsapp|discord)""")
    private val EXECUTABLE_EXT = Regex("""(?i)\.(exe|msi|bat|cmd|sh|jar|apk|dmg|app|deb|rpm|pkg)(\?|$|\s)""")

    private val SEND_TEXT = Regex("""(?i)\b(send|post|publish|tweet|share|submit|reply|transmit)\b""")
    private val DELETE_TEXT = Regex("""(?i)\b(delete|remove|erase|drop|permanently|clear all|empty trash)\b""")
    private val AGREEMENT_TEXT = Regex("""(?i)\b(i agree|accept (the )?(terms|agreement|policy)|agree (and|&)? (continue|submit)|place (my )?order|confirm (purchase|payment|order)|pay now|buy now|subscribe)\b""")
    private val PASSWORD_TEXT = Regex("""(?i)\b(new password|confirm password|current password|old password|change password)\b""")

    /**
     * @param action the parsed action JSON
     * @param pageUrl current page URL
     * @param targetTextIfKnown text/label of the target element when the action references one
     * @param isPasswordTarget true when the target input is type=password
     */
    fun assess(
        action: JSONObject,
        pageUrl: String,
        targetTextIfKnown: String? = null,
        isPasswordTarget: Boolean = false
    ): Assessment {
        val kind = action.optString("action", "")
        val text = targetTextIfKnown ?: ""

        // Hard blocks: credential-shaped destinations or programmatic clipboard exfil attempts.
        if (kind == "navigate") {
            val url = action.optString("url", "")
            if (PromptInjectionDetector.suspiciousUrl(url)) return Assessment(Risk.BLOCK, "URL carries credential-shaped parameters")
        }
        val textKind = kind == "type" || kind == "paste"
        if (isPasswordTarget && textKind) {
            return Assessment(Risk.CONFIRM, "typing/pasting into a password field — verify this is what you want")
        }

        // Purchases / financial
        if (PAYMENT_URL.containsMatchIn(pageUrl)) {
            if (kind == "click" && AGREEMENT_TEXT.containsMatchIn(text)) return Assessment(Risk.CONFIRM, "purchase/agreement confirmation on a payment page")
            if (textKind && text.isNotEmpty()) return Assessment(Risk.CONFIRM, "typing/pasting on a payment/checkout page")
            if (kind == "navigate") return Assessment(Risk.CONFIRM, "navigating away from a payment/checkout flow")
        }

        // Auth-sensitive typing (passwords, security questions, password changes)
        if (textKind && AUTH_URL.containsMatchIn(pageUrl) && PASSWORD_TEXT.containsMatchIn(text))
            return Assessment(Risk.CONFIRM, "password-change style field detected")

        // Deletion
        if (kind == "click" && DELETE_TEXT.containsMatchIn(text))
            return Assessment(Risk.CONFIRM, "possible delete/remove action")

        // Sending messages / publishing
        if ((kind == "click" || kind == "type") && (MAIL_URL.containsMatchIn(pageUrl) || SOCIAL_URL.containsMatchIn(pageUrl))) {
            if (kind == "click" && SEND_TEXT.containsMatchIn(text)) return Assessment(Risk.CONFIRM, "sending/publishing on a mail/social site")
        }

        // Agreements / legal significance
        if (kind == "click" && AGREEMENT_TEXT.containsMatchIn(text))
            return Assessment(Risk.CONFIRM, "accepting terms / placing order style action")

        // Downloads of executables
        if (kind == "download") {
            val url = action.optString("url", "") + " " + text
            if (EXECUTABLE_EXT.containsMatchIn(url)) return Assessment(Risk.CONFIRM, "executable file download")
        }

        // Memory persistence is a consequential action (injection-persistence vector)
        if (kind == "remember") return Assessment(Risk.CONFIRM, "agent wants to save a note to long-term memory")

        return Assessment(Risk.NONE, "")
    }
}

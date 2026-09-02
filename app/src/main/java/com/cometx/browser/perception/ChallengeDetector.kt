package com.cometx.browser.perception

/**
 * ChallengeDetector — recognizes human-verification surfaces so the agent can
 * PAUSE and hand control to the user. This module exists to ENABLE human
 * takeover, never to bypass protections (no solver logic, no token theft).
 * Pure Kotlin — unit tested on the JVM.
 */
object ChallengeDetector {

    data class Eval(val result: ChallengeResult)

    private val URL_RULES = listOf(
        Triple(Regex("""(?i)(recaptcha|hcaptcha|turnstile|captcha)"""), ChallengeResult.CAPTCHA, "URL references a captcha service"),
        Triple(Regex("""(?i)(cf[-_]chl|challenge-platform|cdn-cgi/challenge|just-a-moment)"""), ChallengeResult.CLOUDFLARE, "Cloudflare challenge interstitial"),
        Triple(Regex("""(?i)(/mfa|/2fa|/otp|/verification[-_]?code|signin/v2/challenge)"""), ChallengeResult.MFA, "MFA / 2FA challenge screen"),
        Triple(Regex("""(?i)(rate[-_]?limit|too[-_]?many[-_]?requests|unusual[-_]?traffic)"""), ChallengeResult.RATE_LIMIT, "Rate-limit / unusual-traffic screen")
    )

    private val DOM_RULES = listOf(
        Triple(Regex("""(?i)<iframe[^>]+src="[^"]*(recaptcha|hcaptcha|turnstile|challenges\.cloudflare)"""), ChallengeResult.CAPTCHA, "embedded challenge iframe"),
        Triple(Regex("""(?i)class="[^"]*(g-recaptcha|h-captcha|cf-turnstile|captcha-container)"""), ChallengeResult.CAPTCHA, "challenge widget markup"),
        Triple(Regex("""(?i)(verify (you are|that you're) (a )?human|are you a robot|not a robot|prove you.?re (a )?human|press ?& ?hold|i'?m not a robot)"""), ChallengeResult.CAPTCHA, "human-verification prompt text"),
        Triple(Regex("""(?i)(security check|checking your browser|please complete the security check|verify to continue)"""), ChallengeResult.CLOUDFLARE, "security-check interstitial text"),
        Triple(Regex("""(?i)(enter (the )?(code|otp|verification code)|two[- ]factor|2[- ]factor|authentication code|one[- ]time (code|password))"""), ChallengeResult.MFA, "one-time-code entry prompt"),
        Triple(Regex("""(?i)(unusual traffic|requests per (hour|day)|temporarily blocked|rate limit exceeded)"""), ChallengeResult.RATE_LIMIT, "traffic-limit message")
    )

    /**
     * @param url current page URL
     * @param domSample compact textual sample of page DOM/text (titles, labels, snippets)
     * @param elementTexts concatenated descriptions of interactive elements
     */
    fun evaluate(url: String, domSample: String, elementTexts: String): ChallengeResult {
        val hayUrl = url
        for ((rx, type, detail) in URL_RULES) {
            if (rx.containsMatchIn(hayUrl)) return ChallengeResult(type, detail)
        }
        val hayDom = (domSample + "\n" + elementTexts).take(4000)
        for ((rx, type, detail) in DOM_RULES) {
            if (rx.containsMatchIn(hayDom)) return ChallengeResult(type, detail)
        }
        return ChallengeResult(ChallengeResult.NONE, "")
    }

    /** True when the detected challenge requires a human (all of them do, by design). */
    fun requiresHuman(result: ChallengeResult): Boolean = result.type != ChallengeResult.NONE
}

package com.cometx.browser

import com.cometx.browser.perception.ChallengeDetector
import com.cometx.browser.perception.ChallengeResult
import org.junit.Assert.assertEquals
import org.junit.Test

class ChallengeDetectorTest {

    @Test fun `recaptcha url detected`() {
        val r = ChallengeDetector.evaluate("https://accounts.google.com/signin/v2/challenge/recaptcha", "", "")
        assertEquals(ChallengeResult.CAPTCHA, r.type)
    }

    @Test fun `cloudflare interstitial detected from dom`() {
        val r = ChallengeDetector.evaluate("https://example.com/", "Checking your browser before accessing. Please complete the security check", "")
        assertEquals(ChallengeResult.CLOUDFLARE, r.type)
    }

    @Test fun `hcaptcha iframe markup detected`() {
        val r = ChallengeDetector.evaluate("https://example.com/login", """<iframe src="https://newassets.hcaptcha.com/captcha/v1/"></iframe>""", "")
        assertEquals(ChallengeResult.CAPTCHA, r.type)
    }

    @Test fun `mfa prompt detected`() {
        val r = ChallengeDetector.evaluate("https://mail.example.com/", "Enter the verification code sent to your phone. Two-factor authentication", "")
        assertEquals(ChallengeResult.MFA, r.type)
    }

    @Test fun `not-a-robot text detected`() {
        val r = ChallengeDetector.evaluate("https://example.com/", "", "Verify you are human — check the box I'm not a robot")
        assertEquals(ChallengeResult.CAPTCHA, r.type)
    }

    @Test fun `normal page is none`() {
        val r = ChallengeDetector.evaluate("https://example.com/hotels", "Find the cheapest hotel for Friday. Prices from 40 USD.", "Search button Submit")
        assertEquals(ChallengeResult.NONE, r.type)
    }

    @Test fun `rate limit screen detected`() {
        val r = ChallengeDetector.evaluate("https://shop.example.com/", "Rate limit exceeded. Unusual traffic from your network. Try again later.", "")
        assertEquals(ChallengeResult.RATE_LIMIT, r.type)
    }

    @Test fun `requiresHuman true only for real challenges`() {
        assertEquals(false, ChallengeDetector.requiresHuman(ChallengeResult(ChallengeResult.NONE, "")))
        assertEquals(true, ChallengeDetector.requiresHuman(ChallengeResult(ChallengeResult.CAPTCHA, "")))
    }
}

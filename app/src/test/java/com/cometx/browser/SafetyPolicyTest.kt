package com.cometx.browser

import com.cometx.browser.security.SafetyPolicy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SafetyPolicyTest {

    @Test fun `typing into password field requires confirmation`() {
        val a = SafetyPolicy.assess(JSONObject("""{"action":"type","ref":"e1","text":"hunter2"}"""), "https://example.com/login", "password input", isPasswordTarget = true)
        assertEquals(SafetyPolicy.Risk.CONFIRM, a.risk)
    }

    @Test fun `purchase agreement click on checkout requires confirmation`() {
        val a = SafetyPolicy.assess(JSONObject("""{"action":"click","ref":"e2"}"""), "https://shop.example.com/checkout/pay", "Place order and pay now")
        assertEquals(SafetyPolicy.Risk.CONFIRM, a.risk)
    }

    @Test fun `delete action requires confirmation`() {
        val a = SafetyPolicy.assess(JSONObject("""{"action":"click","ref":"e3"}"""), "https://app.example.com/settings", "Delete account permanently")
        assertEquals(SafetyPolicy.Risk.CONFIRM, a.risk)
    }

    @Test fun `sending message on mail site requires confirmation`() {
        val a = SafetyPolicy.assess(JSONObject("""{"action":"click","ref":"e4"}"""), "https://mail.example.com/compose", "Send")
        assertEquals(SafetyPolicy.Risk.CONFIRM, a.risk)
    }

    @Test fun `executable download requires confirmation`() {
        val a = SafetyPolicy.assess(JSONObject("""{"action":"download","url":"https://dl.example.com/tool.exe"}"""), "https://dl.example.com/")
        assertEquals(SafetyPolicy.Risk.CONFIRM, a.risk)
    }

    @Test fun `navigate with credential params is blocked`() {
        val a = SafetyPolicy.assess(JSONObject("""{"action":"navigate","url":"https://evil.example/x?token=abcdefgh12345"}"""), "https://example.com/")
        assertEquals(SafetyPolicy.Risk.BLOCK, a.risk)
    }

    @Test fun `ordinary click is none`() {
        val a = SafetyPolicy.assess(JSONObject("""{"action":"click","ref":"e5"}"""), "https://example.com/hotels", "Search")
        assertEquals(SafetyPolicy.Risk.NONE, a.risk)
    }

    @Test fun `ordinary navigation is none`() {
        val a = SafetyPolicy.assess(JSONObject("""{"action":"navigate","url":"https://example.com/results"}"""), "https://example.com/")
        assertEquals(SafetyPolicy.Risk.NONE, a.risk)
    }
}

package com.cometx.browser

import com.cometx.browser.browse.AdBlocker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.0.0 — network blocking core. Pure JVM (no Android imports).
 */
class AdBlockerTest {

    private fun blocker(lines: List<String>): AdBlocker =
        AdBlocker().apply { loadFromLines(lines) }

    // ---- hosts parsing ----

    @Test fun `parse accepts hosts-file formats and skips noise`() {
        assertEquals("ads.example.com", AdBlocker.parseHostsLine("0.0.0.0 ads.example.com"))
        assertEquals("tracker.io", AdBlocker.parseHostsLine("127.0.0.1 tracker.io"))
        assertEquals("bare.example", AdBlocker.parseHostsLine("bare.example"))
        assertNull(AdBlocker.parseHostsLine("# comment"))
        assertNull(AdBlocker.parseHostsLine("! abp comment"))
        assertNull(AdBlocker.parseHostsLine(""))
        assertNull(AdBlocker.parseHostsLine("localhost"))
        assertNull(AdBlocker.parseHostsLine("broadcasthost"))
        assertNull(AdBlocker.parseHostsLine("nodot")) // no dot → not a domain
    }

    @Test fun `loadFromLines arms the blocker`() {
        val b = blocker(listOf("0.0.0.0 ads.example.com", "# c", "doubleclick.net"))
        assertTrue(b.isReady())
        assertEquals(0, b.sessionBlockedCount()) // counters start clean
    }

    // ---- domain walk ----

    @Test fun `exact host blocks`() {
        val b = blocker(listOf("doubleclick.net"))
        assertTrue(b.shouldBlock("https://doubleclick.net/x.js"))
    }

    @Test fun `subdomain of a blocked host blocks (walk up the tree)`() {
        val b = blocker(listOf("doubleclick.net"))
        assertTrue(b.shouldBlock("https://ad.something.doubleclick.net/x.js"))
    }

    @Test fun `unrelated host passes`() {
        val b = blocker(listOf("doubleclick.net"))
        assertFalse(b.shouldBlock("https://example.com/page"))
        // NOTE: host-like substrings inside a domain (notdoubleclick.net vs
        // doubleclick.net) are still caught by the conservative substring
        // URL-pattern rules — inherited Zerium behavior, documented in the
        // lock addendum. Use truly unrelated domains here.
        assertFalse(b.shouldBlock("https://unrelated-other.org/x.js"))
        assertFalse(b.shouldBlock("https://github.com/foo/bar.js"))
    }

    // ---- allowlist ----

    @Test fun `allowlist exempts a host even when its parent is blocked`() {
        val b = blocker(listOf("example.com"))
        assertTrue(b.shouldBlock("https://example.com/ok")) // sanity: blocked by parent walk
        b.rebuildAllowlist("example.com\ntracker.example.com")
        assertFalse(b.shouldBlock("https://example.com/ok"))
        assertFalse(b.shouldBlock("https://tracker.example.com/x"))
        assertTrue(b.shouldBlock("https://stillblocked.example.com/x")) // different subdomain not exempt
    }

    // ---- URL pattern rules ----

    @Test fun `url pattern rules block shared-domain endpoints`() {
        val b = blocker(listOf()) // empty list → ready? no
        // not ready → no blocking at all (defensive)
        assertFalse(b.shouldBlock("https://example.com/google-analytics.com/x"))
        val b2 = blocker(listOf("placeholder-domain.example"))
        assertTrue(b2.shouldBlock("https://cdn.example.com/googletagmanager.com/gtag/js?id=G-1"))
        assertTrue(b2.shouldBlock("https://page.example/connect.facebook.net/sdk.js"))
        assertFalse(b2.shouldBlock("https://page.example/normal.js"))
    }

    @Test fun `empty request url is never blocked`() {
        val b = blocker(listOf("doubleclick.net"))
        assertFalse(b.shouldBlock(""))
    }

    // ---- hostOf (pure) ----

    @Test fun `hostOf extracts lowercased hosts from urls`() {
        assertEquals("example.com", AdBlocker.hostOf("https://Example.com/path?q=1"))
        assertEquals("example.com", AdBlocker.hostOf("http://user:pass@Example.com:8080/p"))
        assertEquals("192.168.1.5", AdBlocker.hostOf("http://192.168.1.5:8080/"))
        assertNull(AdBlocker.hostOf("not a url at all"))
        assertNull(AdBlocker.hostOf(null))
    }
}

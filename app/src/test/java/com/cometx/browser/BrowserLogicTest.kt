package com.cometx.browser

import com.cometx.browser.browse.CosmeticFilter
import com.cometx.browser.browse.FilterUpdater
import com.cometx.browser.browse.HttpsFirst
import com.cometx.browser.browse.StartPage
import com.cometx.browser.browse.StartPageLogic
import com.cometx.browser.browse.TranslateSupport
import com.cometx.browser.browse.YouTubeFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.0.0 — pure logic for HTTPS-first upgrades, translate.goog round trips,
 * start-page tiles, cosmetic-script building and filter-update scheduling.
 */
class BrowserLogicTest {

    // ---- HttpsFirst ----

    @Test fun `http pages upgrade to https`() {
        assertEquals("https://example.com/page", HttpsFirst.upgraded("http://example.com/page"))
        assertEquals("https://example.com", HttpsFirst.upgraded("http://example.com"))
        assertEquals("https://example.com:8443/x?q=1#f", HttpsFirst.upgraded("http://example.com:8443/x?q=1#f"))
    }

    @Test fun `local hosts are never upgraded`() {
        assertNull(HttpsFirst.upgraded("http://localhost:8081/test/index.html"))
        assertNull(HttpsFirst.upgraded("http://127.0.0.1:8081/x"))
        assertNull(HttpsFirst.upgraded("http://192.168.1.5/"))
        assertNull(HttpsFirst.upgraded("http://10.0.0.2/"))
        assertNull(HttpsFirst.upgraded("http://172.16.0.1/"))
        assertNull(HttpsFirst.upgraded("http://172.31.9.9/"))
        assertNull(HttpsFirst.upgraded("http://myserver.local/"))
        assertNull(HttpsFirst.upgraded("http://nas.lan/"))
    }

    @Test fun `https and non-http urls never re-upgrade`() {
        assertNull(HttpsFirst.upgraded("https://example.com"))
        assertNull(HttpsFirst.upgraded("ftp://example.com"))
        assertNull(HttpsFirst.upgraded(null))
        assertTrue(HttpsFirst.isUpgradableHost("example.com"))
        assertFalse(HttpsFirst.isUpgradableHost("localhost"))
        assertFalse(HttpsFirst.isUpgradableHost(null))
    }

    // ---- TranslateSupport ----

    @Test fun `translate url rewrites host and appends params`() {
        val out = TranslateSupport.translateUrl("https://en.example.com/page?a=1", "de")
        assertEquals(
            "https://en-example-com.translate.goog/page?a=1&_x_tr_sl=auto&_x_tr_tl=de&_x_tr_hl=de&_x_tr_pto=ajax,elem",
            out
        )
    }

    @Test fun `translate handles path-only urls and rejects junk`() {
        assertEquals(
            "https://example-org.translate.goog?_x_tr_sl=auto&_x_tr_tl=en&_x_tr_hl=en&_x_tr_pto=ajax,elem",
            TranslateSupport.translateUrl("https://example.org", "en")
        )
        assertNull(TranslateSupport.translateUrl("about:blank", "en"))
    }

    @Test fun `original-from-translate-url inverts host and strips params`() {
        val original = TranslateSupport.originalFromTranslateUrl(
            "https://en-example-com.translate.goog/page?a=1&_x_tr_sl=auto&_x_tr_tl=de&_x_tr_pto=ajax,elem"
        )
        assertEquals("https://en.example.com/page?a=1", original)
    }

    @Test fun `isTranslated detects proxy pages`() {
        assertTrue(TranslateSupport.isTranslated("https://en-example-com.translate.goog/x"))
        assertTrue(TranslateSupport.isTranslated("https://translate.goog/x"))
        assertFalse(TranslateSupport.isTranslated("https://example.com"))
        assertFalse(TranslateSupport.isTranslated(null))
    }

    // ---- StartPage ----

    @Test fun `start-page sentinel detection`() {
        assertTrue(StartPage.isStartPage(StartPage.HOME_URL))
        assertTrue(StartPage.isStartPage("data:text/html,..."))
        assertTrue(StartPage.isStartPage(""))
        assertFalse(StartPage.isStartPage("https://example.com"))
    }

    @Test fun `custom tiles win over automatic tiles`() {
        val custom = listOf(StartPageLogic.Tile("Kagi", "https://kagi.com", "", ""))
        val tiles = StartPageLogic.resolveTiles(custom, emptyList())
        assertEquals(1, tiles.size)
        assertEquals("Kagi", tiles[0].name)
        assertEquals("K", tiles[0].icon)
    }

    @Test fun `auto tiles blend most-visited with defaults and dedupe`() {
        val top = listOf(
            "https://github.com" to "GitHub",
            "https://news.ycombinator.com/item?id=1" to "HN thread",
            "https://www.google.com/search?q=x" to "search (excluded)"
        )
        val tiles = StartPageLogic.autoTiles(top)
        assertTrue(tiles.any { it.url == "https://github.com" })
        assertTrue(tiles.any { it.url == "https://news.ycombinator.com" })
        // search-result pages are excluded
        assertTrue(tiles.none { it.url.contains("/search") })
        // defaults fill the remaining slots up to TILE_COUNT
        assertEquals(StartPageLogic.TILE_COUNT, tiles.size)
        assertTrue(tiles.any { it.url == "https://www.reddit.com" })
    }

    @Test fun `search-result detection covers the big engines`() {
        assertTrue(StartPageLogic.isSearchResult("https://google.com/search?q=x", "google.com"))
        assertTrue(StartPageLogic.isSearchResult("https://duckduckgo.com/?q=x", "duckduckgo.com"))
        assertFalse(StartPageLogic.isSearchResult("https://example.com/page", "example.com"))
    }

    @Test fun `hostKey strips www and monogram capitalizes`() {
        assertEquals("github.com", StartPageLogic.hostKey("https://www.github.com/x"))
        assertEquals("C", StartPageLogic.monogram("comet"))
        assertEquals("?", StartPageLogic.monogram(""))
    }

    // ---- CosmeticFilter script ----

    @Test fun `cosmetic script batches selectors and is self-contained`() {
        val selectors = (1..150).map { ".ad-unit-$it" }
        val script = CosmeticFilter.buildScript(selectors)
        assertNotNull(script)
        assertTrue(script.contains("var BATCH=60"))
        assertTrue(script.contains("MutationObserver"))
        assertTrue(script.contains("data-cometx-hidden"))
        assertTrue(script.contains(".ad-unit-150"))
        // the fallback retry branch must be syntactically valid (port fix!)
        assertTrue(script.contains("hide(n2[m])"))
        assertFalse(script.contains("hide(n2])"))
    }

    @Test fun `cosmetic script sanitizes unsafe selectors`() {
        val script = CosmeticFilter.buildScript(
            listOf(".ok-selector", "a[href^='http']", "evil{alert(1)}", "x", "")
        )
        assertTrue(script.contains(".ok-selector"))
        // braces are not in the safe set → dropped
        assertFalse(script.contains("alert"))
    }

    // ---- FilterUpdater scheduling ----

    @Test fun `auto update runs weekly or on first run only`() {
        val now = 1_700_000_000_000L
        assertFalse(FilterUpdater.dueForAutoUpdate(false, 0, now))
        assertTrue(FilterUpdater.dueForAutoUpdate(true, 0, now)) // first run
        assertFalse(FilterUpdater.dueForAutoUpdate(true, now - 1000, now)) // just updated
        assertTrue(FilterUpdater.dueForAutoUpdate(true, now - 8L * 24 * 3600 * 1000, now))
    }

    // ---- YouTubeFilter host matching ----

    @Test fun `youtube host matching`() {
        assertTrue(YouTubeFilter.matches("www.youtube.com"))
        assertTrue(YouTubeFilter.matches("m.youtube.com"))
        assertTrue(YouTubeFilter.matches("music.youtube.com"))
        assertTrue(YouTubeFilter.matches("www.youtube-nocookie.com"))
        assertFalse(YouTubeFilter.matches("youtube.fakesite.com.evil"))
        assertFalse(YouTubeFilter.matches("example.com"))
        assertFalse(YouTubeFilter.matches(null))
    }
}

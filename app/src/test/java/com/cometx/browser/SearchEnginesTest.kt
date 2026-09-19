package com.cometx.browser

import com.cometx.browser.browse.SearchEngines
import com.cometx.browser.util.UserInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.0.0 — search engine registry, custom engines and the omnibox template
 * hook. Pure JVM.
 */
class SearchEnginesTest {

    @Test fun `builtins include google first (default preserved)`() {
        assertEquals("Google", SearchEngines.BUILTIN.first().name)
        assertEquals("https://www.google.com/search?q=%s", SearchEngines.BUILTIN.first().query)
        assertTrue(SearchEngines.BUILTIN.size >= 5)
        assertTrue(SearchEngines.BUILTIN.none { it.custom })
    }

    @Test fun `allEngines appends customs after builtins`() {
        val json = SearchEngines.serializeCustomEngines(
            listOf(SearchEngines.Engine("Kagi", "https://kagi.com/search?q=%s", true))
        )
        val all = SearchEngines.allEngines(json)
        assertEquals(SearchEngines.BUILTIN.size + 1, all.size)
        assertEquals("Kagi", all.last().name)
        assertTrue(all.last().custom)
    }

    @Test fun `engineAt clamps into range`() {
        assertEquals(SearchEngines.BUILTIN.first(), SearchEngines.engineAt(null, -5))
        assertEquals(SearchEngines.BUILTIN.first(), SearchEngines.engineAt(null, 0))
        val last = SearchEngines.engineAt(null, 9999)
        assertEquals(SearchEngines.BUILTIN.last().name, last.name)
    }

    @Test fun `invalid custom engines are skipped silently`() {
        val raw = """[{"name":"","url":"https://x.com/?q=%s"},
            {"name":"NoPlaceholder","url":"https://x.com/search"},
            {"name":"NotHttp","url":"ftp://x.com/%s"},
            {"name":"Ok","url":"https://ok.com/?q=%s"}]"""
        val parsed = SearchEngines.parseCustomEngines(raw)
        assertEquals(1, parsed.size)
        assertEquals("Ok", parsed[0].name)
    }

    @Test fun `parse of garbage returns empty and serialize round-trips`() {
        assertTrue(SearchEngines.parseCustomEngines("not json").isEmpty())
        assertTrue(SearchEngines.parseCustomEngines(null).isEmpty())
        val engines = listOf(
            SearchEngines.Engine("A", "https://a.com/?q=%s", true),
            SearchEngines.Engine("builtin-ignored", "https://g.com/?q=%s", false)
        )
        val json = SearchEngines.serializeCustomEngines(engines)
        val parsed = SearchEngines.parseCustomEngines(json)
        assertEquals(1, parsed.size) // built-ins are not serialized
        assertEquals("A", parsed[0].name)
    }

    @Test fun `validCustomEngine rules`() {
        assertTrue(SearchEngines.validCustomEngine("X", "https://x.com/?q=%s"))
        assertFalse(SearchEngines.validCustomEngine("", "https://x.com/?q=%s"))
        assertFalse(SearchEngines.validCustomEngine("X", "https://x.com/?q=")) // no %s
        assertFalse(SearchEngines.validCustomEngine("X", null))
        assertFalse(SearchEngines.validCustomEngine("x".repeat(41), "https://x.com/?q=%s"))
    }

    @Test fun `searchUrl encodes the query like UserInput`() {
        val url = SearchEngines.searchUrl("best phone 2026", "https://duckduckgo.com/?q=%s")
        assertEquals("https://duckduckgo.com/?q=best%20phone%202026", url)
    }

    @Test fun `looksLikeUrl mirrors the omnibox heuristic`() {
        assertTrue(SearchEngines.looksLikeUrl("example.com"))
        assertTrue(SearchEngines.looksLikeUrl("https://example.com"))
        assertTrue(SearchEngines.looksLikeUrl("localhost"))
        assertFalse(SearchEngines.looksLikeUrl("best phone"))
        assertFalse(SearchEngines.looksLikeUrl("nope."))
    }

    // ---- UserInput template hook (default must be byte-identical) ----

    @Test fun `userInput default resolves google exactly like v1`() {
        assertEquals("https://www.google.com/search?q=best%20phone%202026",
            UserInput.resolve("best phone 2026"))
        assertEquals("https://example.com", UserInput.resolve("example.com"))
        assertEquals("https://x.com", UserInput.resolve("https://x.com"))
        assertEquals("", UserInput.resolve("   "))
    }

    @Test fun `userInput template overrides search terms only`() {
        val ddg = "https://duckduckgo.com/?q=%s"
        assertEquals("https://duckduckgo.com/?q=hello%20world",
            UserInput.resolve("hello world", ddg))
        // URL-looking inputs ignore the template
        assertEquals("https://example.com", UserInput.resolve("example.com", ddg))
        assertEquals("https://y.com", UserInput.resolve("https://y.com", ddg))
        // blank/invalid template falls back to Google
        assertEquals("https://www.google.com/search?q=hi",
            UserInput.resolve("hi", "no-placeholder"))
        assertEquals("https://www.google.com/search?q=hi",
            UserInput.resolve("hi", "  "))
    }

    @Test fun `userInput passes the start-page sentinels through`() {
        assertEquals("about:home", UserInput.resolve("about:home"))
        assertEquals("cometx://home", UserInput.resolve("cometx://home"))
    }
}

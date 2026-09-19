package com.cometx.browser

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cometx.browser.browse.BookmarksStore
import com.cometx.browser.browse.HistoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v2.0.0 — bookmarks & history SQLite stores (Robolectric).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoresTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun `bookmarks add contains dedupe remove`() {
        val store = BookmarksStore(context)
        assertTrue(store.add("https://example.com", "Example"))
        assertFalse(store.add("https://example.com", "Duplicate ignored"))
        assertTrue(store.contains("https://example.com"))
        assertFalse(store.contains("https://other.com"))
        val all = store.all()
        assertEquals(1, all.size)
        assertEquals("Example", all[0].title)
        assertTrue(store.remove(all[0].id))
        assertFalse(store.contains("https://example.com"))
    }

    @Test fun `bookmarks blank url is refused and blank title falls back`() {
        val store = BookmarksStore(context)
        assertFalse(store.add("", "x"))
        assertFalse(store.add(null, "x"))
        store.add("https://a.com", null)
        assertEquals("https://a.com", store.all()[0].title)
    }

    @Test fun `history add all remove clear`() {
        val store = HistoryStore(context)
        store.add("https://a.com", "A")
        store.add("https://b.com", "B")
        store.add("data:text/html,x", "start pages are refused")
        assertEquals(2, store.all().size)
        assertTrue(store.remove(store.all()[0].id))
        assertEquals(1, store.all().size)
        store.clear()
        assertTrue(store.all().isEmpty())
    }

    @Test fun `history topSites counts visits per distinct url`() {
        val store = HistoryStore(context)
        repeat(3) { store.add("https://hot.com/page", "Hot") }
        store.add("https://hot.com/page", "Hot (newest title)")
        repeat(2) { store.add("https://warm.com", "Warm") }
        store.add("https://cold.com", "Cold")
        val top = store.topSites(10)
        assertEquals(3, top.size)
        assertEquals("https://hot.com/page", top[0].url)
        assertEquals("https://warm.com", top[1].url)
        assertEquals("https://cold.com", top[2].url)
        val limited = store.topSites(2)
        assertEquals(2, limited.size)
    }
}

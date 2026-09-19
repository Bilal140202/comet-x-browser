package com.cometx.browser

import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.cometx.browser.ui.MainActivity
import com.cometx.browser.ui.TabManager
import com.cometx.browser.util.UserInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * v1.6.1 regression suite — user-reported fixes:
 *  1. tapping tab names in the switcher must switch tabs
 *     (row-level click handling + re-attach recompose + close-index shift)
 *  2. omnibox must behave Chrome-like (select-all on tap, replace, restore)
 *  3. keyboard search must always land (GO releases focus, live URL updates resume)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = com.cometx.browser.CometApp::class)
class TabAndOmniboxTest {

    // ------------------------------------------------------- TabManager

    private fun newManager(): Pair<TabManager, FrameLayout> {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val container = FrameLayout(ctx)
        val manager = TabManager(container, { android.webkit.WebView(ctx) }, {})
        return manager to container
    }

    @Test fun `switchTo attaches the chosen tab webview to the container`() {
        val (tabs, container) = newManager()
        tabs.newTab("https://a.example/")
        tabs.newTab("https://b.example/")
        tabs.newTab("https://c.example/")
        assertEquals(2, tabs.currentIndex)

        tabs.switchTo(0)
        assertEquals(0, tabs.currentIndex)
        assertEquals(tabs.tabs[0].webView, container.getChildAt(0))
        assertEquals("https://a.example/", tabs.current?.url)

        tabs.switchTo(1)
        assertEquals(tabs.tabs[1].webView, container.getChildAt(0))
        assertEquals(1, tabs.currentIndex)
    }

    @Test fun `attach parks the webview invisible until the posted recompose hop`() {
        val (tabs, container) = newManager()
        tabs.newTab("https://a.example/")
        tabs.newTab("https://b.example/")
        tabs.switchTo(0)
        // deterministic park: attach() hides first, next-frame post restores
        // (VISIBLE hop asserted in the activity-level sheet test below — an
        // unattached view never flushes its post queue in Robolectric)
        assertEquals(View.INVISIBLE, container.getChildAt(0).visibility)
    }

    @Test fun `closing a tab LEFT of current keeps the user on their page`() {
        val (tabs, container) = newManager()
        tabs.newTab("https://a.example/")
        tabs.newTab("https://b.example/")
        tabs.newTab("https://c.example/")
        assertEquals(2, tabs.currentIndex)
        val viewing = tabs.current?.webView

        tabs.close(0) // removed left of current → indices shift down
        assertEquals(1, tabs.currentIndex)
        assertEquals(viewing, container.getChildAt(0))
        assertEquals("https://c.example/", tabs.current?.url)
    }

    @Test fun `closing the current tab shows the next tab`() {
        val (tabs, container) = newManager()
        tabs.newTab("https://a.example/")
        tabs.newTab("https://b.example/")
        tabs.switchTo(1)
        tabs.close(1)
        assertEquals(0, tabs.currentIndex)
        assertEquals(tabs.tabs[0].webView, container.getChildAt(0))
    }

    @Test fun `closing the last tab spawns a fresh blank tab`() {
        val (tabs, container) = newManager()
        tabs.newTab("https://a.example/")
        tabs.close(0)
        assertEquals(1, tabs.tabs.size)
        assertEquals(0, tabs.currentIndex)
        assertEquals(tabs.tabs[0].webView, container.getChildAt(0))
    }

    @Test fun `switchTo out of range is a no-op`() {
        val (tabs, container) = newManager()
        tabs.newTab("https://a.example/")
        tabs.switchTo(5)
        assertEquals(0, tabs.currentIndex)
        assertEquals(tabs.tabs[0].webView, container.getChildAt(0))
    }

    // ------------------------------------------------------- Omnibox

    @Test fun `omnibox selects the whole url on focus (chrome replace-on-type)`() {
        ActivityScenario.launch(MainActivity::class.java).onActivity { activity ->
            val bar = activity.findViewById<EditText>(R.id.urlBar)
            bar.setText("https://www.example.com/some/long/page")
            assertTrue(bar.requestFocus())
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue(bar.hasSelection())
            val selected = bar.editableText
                .subSequence(bar.selectionStart, bar.selectionEnd).toString()
            assertEquals(bar.text.toString(), selected)
        }
    }

    @Test fun `omnibox restores the live page url on defocus`() {
        ActivityScenario.launch(MainActivity::class.java).onActivity { activity ->
            val bar = activity.findViewById<EditText>(R.id.urlBar)
            bar.setText("https://www.example.com/some/long/page")
            bar.requestFocus()
            bar.setText("garbage the user typed")
            bar.clearFocus()
            // first tab opens the v2.0.0 Comet Start page; tab.url is the sentinel
            assertEquals("about:home", bar.text.toString())
        }
    }

    @Test fun `keyboard GO commits search and releases focus (nothing feels stuck)`() {
        ActivityScenario.launch(MainActivity::class.java).onActivity { activity ->
            val bar = activity.findViewById<EditText>(R.id.urlBar)
            bar.requestFocus()
            bar.setText("cute cats")
            bar.onEditorAction(EditorInfo.IME_ACTION_GO)
            assertEquals("https://www.google.com/search?q=cute%20cats", bar.text.toString())
            assertFalse(bar.hasFocus())
        }
    }

    @Test fun `keyboard GO with a bare domain navigates to https url`() {
        ActivityScenario.launch(MainActivity::class.java).onActivity { activity ->
            val bar = activity.findViewById<EditText>(R.id.urlBar)
            bar.setText("example.com")
            bar.onEditorAction(EditorInfo.IME_ACTION_GO)
            assertEquals("https://example.com", bar.text.toString())
            assertFalse(bar.hasFocus())
        }
    }

    @Test fun `empty omnibox commit does nothing (no navigation)`() {
        ActivityScenario.launch(MainActivity::class.java).onActivity { activity ->
            val bar = activity.findViewById<EditText>(R.id.urlBar)
            bar.setText("   ")
            bar.onEditorAction(EditorInfo.IME_ACTION_GO)
            assertEquals("   ", bar.text.toString())
        }
    }

    // ------------------------------------------------------- UserInput (pure)

    @Test fun `userinput multi-word query becomes google search with encoded spaces`() {
        assertEquals(
            "https://www.google.com/search?q=cute%20cats%20and%20dogs",
            UserInput.resolve("cute cats and dogs")
        )
    }

    @Test fun `userinput bare domain gets https scheme`() {
        assertEquals("https://example.com", UserInput.resolve("example.com"))
    }

    @Test fun `userinput full url passes through untouched`() {
        assertEquals("https://a.b/c?d=e", UserInput.resolve("https://a.b/c?d=e"))
        assertEquals("http://insecure.example/", UserInput.resolve("http://insecure.example/"))
    }

    @Test fun `userinput blank resolves to empty (caller skips navigation)`() {
        assertEquals("", UserInput.resolve("   "))
    }

    // --------------------------------------- Sheet wiring (v2.0.0: Material tab grid)

    @Test fun `tab grid row click switches to that tab and syncs the omnibox`() {
        ActivityScenario.launch(MainActivity::class.java).onActivity { activity ->
            // second tab with a distinct URL (public entry point, same one the
            // agent's open-tab verb and external links use)
            activity.openInNewTab("https://b.example/")
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val bar = activity.findViewById<EditText>(R.id.urlBar)
            assertEquals("https://b.example/", bar.text.toString())

            // open the tab switcher (v2.0.0 grid overlay; kept showTabDialog name)
            activity.findViewById<View>(R.id.btnTabs).performClick()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.tabSwitcher).visibility)

            val grid = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.tabsGrid)
            assertEquals(2, grid.adapter!!.itemCount)

            // bind card 0 (the start-page tab) and tap the CARD (the v1.6.1
            // row-level click contract lives on the card root)
            val vh = grid.adapter!!.onCreateViewHolder(grid, 0)
            grid.adapter!!.onBindViewHolder(vh, 0)
            vh.itemView.performClick()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals("about:home", bar.text.toString())
            assertEquals(View.GONE, activity.findViewById<View>(R.id.tabSwitcher).visibility)
            // re-attach recompose completed inside a real window
            val web = activity.findViewById<FrameLayout>(R.id.webContainer)
            assertEquals(View.VISIBLE, web.getChildAt(0).visibility)
        }
    }

    @Test fun `tab grid close button closes that tab`() {
        ActivityScenario.launch(MainActivity::class.java).onActivity { activity ->
            activity.openInNewTab("https://b.example/")
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val bar = activity.findViewById<EditText>(R.id.urlBar)
            assertEquals("https://b.example/", bar.text.toString())

            activity.findViewById<View>(R.id.btnTabs).performClick()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val grid = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.tabsGrid)

            // tap the close button on card 0 (the start-page tab)
            val vh = grid.adapter!!.onCreateViewHolder(grid, 0)
            grid.adapter!!.onBindViewHolder(vh, 0)
            vh.itemView.findViewById<View>(R.id.btnCloseTab).performClick()
            shadowOf(android.os.Looper.getMainLooper()).idle()

            // remaining tab is b.example and the omnibox mirrors it
            assertEquals(1, grid.adapter!!.itemCount)
            assertEquals("https://b.example/", bar.text.toString())
        }
    }
}

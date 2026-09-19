package com.cometx.browser.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import com.cometx.browser.util.Logx

/**
 * TabManager — real multi-tab browser state. Each tab owns a live WebView;
 * tabs are retained (page state preserved on switch), detached views are
 * removed from the container but NOT destroyed (session persistence).
 *
 * v2.0.0 (additive, ported from the Zerium codebase): tabs carry incognito
 * mode (skips history/bookmarks/previews), per-tab desktop-site state with
 * the captured mobile UA, the pre-translation URL for View-original, reader
 * state, favicon, per-page blocked-request counter and a scaled screenshot
 * preview for the Material tab grid.
 */
class TabManager(
    private val container: ViewGroup,
    private val onCreateWebView: () -> WebView,
    private val onChanged: () -> Unit
) {

    class Tab(val id: Long, val webView: WebView) {
        var title: String = ""
        var url: String = ""

        /** v2.0.0: incognito tabs skip history, bookmarks, previews, icons. */
        var incognito: Boolean = false

        /** v2.0.0: desktop-site toggle state (per tab, Zerium/Lightning pattern). */
        var desktopMode: Boolean = false

        /** The tab's stock (mobile) WebView UA; captured at creation. */
        var mobileUA: String? = null

        /** Original URL before a translate.goog proxy hop (View-original action). */
        var translateSourceUrl: String? = null

        /** True while the reader view replaces this tab's rendered DOM. */
        var readerActive: Boolean = false

        /** Site icon received from the page (pinned-shortcut icon). */
        var favicon: Bitmap? = null

        /** Blocked network requests counted for the currently loaded page. */
        @Volatile
        var blockedOnPage: Long = 0

        /** True when cosmetic rules were already injected at document start. */
        @Volatile
        var cosmeticAtStart: Boolean = false

        /** Scaled screenshot of the last finished page (tab grid preview). */
        var preview: Bitmap? = null
    }

    private var nextId = 1L
    val tabs = mutableListOf<Tab>()
    var currentIndex = 0
        private set

    val current: Tab? get() = tabs.getOrNull(currentIndex)
    val currentWebView: WebView? get() = current?.webView

    fun newTab(url: String): Tab = newTab(url, incognito = false)

    /** v2.0.0: incognito tab creation (UI chrome marks the tab in the grid). */
    @SuppressLint("SetJavaScriptEnabled")
    fun newTab(url: String, incognito: Boolean): Tab {
        val tab = Tab(nextId++, onCreateWebView())
        tab.url = url
        tab.incognito = incognito
        tab.mobileUA = runCatching { tab.webView.settings.userAgentString }.getOrNull()
        tabs.add(tab)
        currentIndex = tabs.size - 1
        attach(tab)
        // v2.0.0: loading is DEFERRED to the caller (MainActivity.loadInTab) so
        // every main-frame navigation — including restored tabs and agent-opened
        // tabs — goes through the same path (DNT/GPC headers, HTTPS-first).
        onChanged()
        return tab
    }

    fun attach(tab: Tab) {
        // Pause every other tab FIRST (v1.6.1): background WebViews must stop
        // rendering — a live background surface can keep its last composed
        // frame on screen after the swap, which looked like "tab did not
        // switch". Real browsers pause background renderers the same way.
        for (t in tabs) if (t !== tab) t.webView.onPause()
        container.removeAllViews()
        if (tab.webView.parent != null) {
            (tab.webView.parent as ViewGroup).removeView(tab.webView)
        }
        container.addView(
            tab.webView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        tab.webView.onResume()
        // Re-attach recompose (v1.6.1): a WebView removed and re-added within
        // the same frame can keep its previous surface → page looks frozen.
        // The INVISIBLE→VISIBLE hop on the next frame forces a full recompose.
        tab.webView.visibility = View.INVISIBLE
        tab.webView.post { tab.webView.visibility = View.VISIBLE }
        onChanged()
    }

    fun switchTo(index: Int) {
        if (index < 0 || index >= tabs.size) return
        currentIndex = index
        attach(tabs[index])
    }

    fun close(index: Int): Boolean {
        if (index < 0 || index >= tabs.size) return false
        val tab = tabs[index]
        container.removeView(tab.webView)
        tab.webView.onPause()
        recyclePreview(tab)
        tabs.removeAt(index)
        if (tabs.isEmpty()) {
            newTab("about:blank")
            return true
        }
        // v1.6.1: removing a tab LEFT of the current one shifts every later tab
        // down by one — decrement first so the user stays on the page they were
        // viewing instead of silently landing on the neighbour tab.
        if (index < currentIndex) currentIndex--
        currentIndex = currentIndex.coerceIn(0, tabs.size - 1)
        attach(tabs[currentIndex])
        return true
    }

    fun closeCurrent() = close(currentIndex)

    /** v2.0.0: closes every tab and opens one fresh (tab switcher action). */
    fun closeAll() {
        for (tab in tabs.toList()) {
            container.removeView(tab.webView)
            runCatching { tab.webView.onPause() }
            recyclePreview(tab)
        }
        tabs.clear()
        currentIndex = 0
        newTab(StartPageUrl)
    }

    /** Activity teardown: detach + destroy every WebView (expert review P1-15). */
    fun destroyAll() {
        for (tab in tabs) {
            container.removeView(tab.webView)
            recyclePreview(tab)
            try {
                tab.webView.onPause()
                tab.webView.removeAllViews()
                tab.webView.destroy()
            } catch (_: Exception) {
            }
        }
        tabs.clear()
        currentIndex = 0
    }

    fun updateMeta(webView: WebView, title: String?, url: String?) {
        tabs.firstOrNull { it.webView == webView }?.let {
            if (!title.isNullOrBlank()) it.title = title
            if (!url.isNullOrBlank()) it.url = url
        }
        onChanged()
    }

    fun titles(): List<Pair<String, String>> = tabs.map { (it.title.ifBlank { "Untitled" }) to it.url }

    /** v2.0.0: hasIncognito drives the incognito-aware session persistence. */
    fun hasIncognito(): Boolean = tabs.any { it.incognito }

    /** v2.0.0: scaled screenshot for the tab grid (RGB_565, bounded memory). */
    fun capturePreview(tab: Tab) {
        try {
            if (tab.incognito) return
            val w = tab.webView
            val vw = w.width
            val vh = w.height
            if (vw <= 0 || vh <= 0) return
            val scale = minOf(1f, 320f / vw)
            val bmp = Bitmap.createBitmap(
                maxOf(1, (vw * scale).toInt()),
                maxOf(1, (vh * scale).toInt()),
                Bitmap.Config.RGB_565
            )
            val canvas = android.graphics.Canvas(bmp)
            canvas.drawColor(0xFFFFFFFF.toInt())
            w.draw(canvas)
            tab.preview?.recycle()
            tab.preview = bmp
        } catch (e: Exception) {
            Logx.d("preview capture failed: ${e.message}")
        }
    }

    private fun recyclePreview(tab: Tab) {
        try {
            tab.preview?.recycle()
        } catch (_: Exception) {
        }
        tab.preview = null
    }

    companion object {
        /** Sentinel consumed by MainActivity.loadInTab — the Comet-X start page. */
        const val StartPageUrl = "about:home"
    }
}

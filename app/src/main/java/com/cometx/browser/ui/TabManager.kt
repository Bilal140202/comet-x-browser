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
 */
class TabManager(
    private val container: ViewGroup,
    private val onCreateWebView: () -> WebView,
    private val onChanged: () -> Unit
) {

    class Tab(val id: Long, val webView: WebView) {
        var title: String = ""
        var url: String = ""
    }

    private var nextId = 1L
    val tabs = mutableListOf<Tab>()
    var currentIndex = 0
        private set

    val current: Tab? get() = tabs.getOrNull(currentIndex)
    val currentWebView: WebView? get() = current?.webView

    fun newTab(url: String): Tab {
        val tab = Tab(nextId++, onCreateWebView())
        tab.url = url
        tabs.add(tab)
        currentIndex = tabs.size - 1
        attach(tab)
        if (url.isNotBlank()) tab.webView.loadUrl(url)
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

    /** Activity teardown: detach + destroy every WebView (expert review P1-15). */
    fun destroyAll() {
        for (tab in tabs) {
            container.removeView(tab.webView)
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
            it.title = title ?: it.title
            it.url = url ?: it.url
        }
        onChanged()
    }

    fun titles(): List<Pair<String, String>> = tabs.map { (it.title.ifBlank { "Untitled" }) to it.url }
}

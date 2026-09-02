package com.cometx.browser.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
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
        currentIndex = currentIndex.coerceIn(0, tabs.size - 1)
        attach(tabs[currentIndex])
        return true
    }

    fun closeCurrent() = close(currentIndex)

    fun updateMeta(webView: WebView, title: String?, url: String?) {
        tabs.firstOrNull { it.webView == webView }?.let {
            it.title = title ?: it.title
            it.url = url ?: it.url
        }
        onChanged()
    }

    fun titles(): List<Pair<String, String>> = tabs.map { (it.title.ifBlank { "Untitled" }) to it.url }
}

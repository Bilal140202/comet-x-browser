package com.cometx.browser.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

/**
 * BrowserSwipeLayout (v2.0.0) — SwipeRefreshLayout that understands WebView
 * scroll state.
 *
 * Ported from the Zerium codebase (GPL-3.0, same owner), where it fixed a
 * real daily-driver bug: the stock `canChildScrollUp()` only asks its direct
 * child — a plain FrameLayout container that can never scroll — so every
 * downward drag anywhere on a page armed the refresh gesture and hijacked
 * upward scrolling.
 *
 * Fix: forward the question to the WebView that is actually visible inside
 * the container. Evaluated per gesture (never cached), so tab switches,
 * scroll restoration and back/forward navigation are always reflected
 * correctly with no wiring needed.
 */
class BrowserSwipeLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SwipeRefreshLayout(context, attrs) {

    override fun canChildScrollUp(): Boolean {
        val content = getChildAt(0)
        if (content is ViewGroup) {
            for (i in 0 until content.childCount) {
                val child = content.getChildAt(i)
                if (child != null && child.visibility == View.VISIBLE && child is WebView) {
                    // True when the page is scrolled away from the top: the
                    // gesture must scroll the page, never trigger a refresh.
                    return child.canScrollVertically(-1)
                }
            }
        }
        // No live WebView (empty transient state): defer to the default check.
        return super.canChildScrollUp()
    }
}

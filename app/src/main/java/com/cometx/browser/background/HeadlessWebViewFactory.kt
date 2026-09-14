package com.cometx.browser.background

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.cometx.browser.util.Logx

/**
 * HeadlessWebViewFactory (v1.8.0) — builds the background agent's ISOLATED
 * browser surface: a real Chromium WebView that is never attached to any
 * window, view hierarchy or the user's tab strip. The user's visible tabs,
 * omnibox and panels are structurally unreachable from here.
 *
 * Security posture mirrors BrowserController.createWebView() (THREAT_MODEL.md)
 * — JS on, DOM storage on, all file/content access off, mixed content never,
 * no geolocation, no JS bridge objects. The two sites are kept in lockstep by
 * review; a factory shared with the activity path would touch locked code.
 *
 * Crash containment (the "App keep closing" contract, BG-8): a gone renderer
 * is destroyed and reported via callback instead of killing the whole app
 * (the platform default). Nothing in this factory may throw into the caller.
 */
object HeadlessWebViewFactory {

    /** @return a hardened, measured, headless WebView — or null if creation failed. */
    @SuppressLint("SetJavaScriptEnabled")
    fun create(context: Context, onRenderGone: (String) -> Unit): WebView? = try {
        val web = WebView(context)

        // ---- security posture (mirror of BrowserController — see class doc) ----
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(false) // popups make no sense headless
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
            setGeolocationEnabled(false)
            textZoom = 100
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)

        // Software rendering: the view is never attached to a window, so
        // hardware-accelerated draw paths have no surface. A software layer
        // guarantees draw(canvas) produces real pixels for vision captures.
        web.setLayerType(View.LAYER_TYPE_SOFTWARE, null)

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme?.lowercase() ?: return false
                // http/https drive the headless engine; every other scheme is
                // silently refused (a page must never fire intent:// from the
                // background where no user could approve it)
                return scheme != "http" && scheme != "https"
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // BG-8: renderer crash must degrade the TASK, never the app.
                Logx.e("bg-agent: headless renderer gone (didCrash=${detail.didCrash()})")
                runCatching { (view.parent as? ViewGroup)?.removeView(view) }
                runCatching { view.destroy() }
                onRenderGone("The page's renderer crashed")
                return true
            }
        }

        // ---- headless layout: give the view a real viewport so JS layout,
        //      elementFromPoint and screenshot draws all behave like a phone
        val dm = context.resources.displayMetrics
        val w = dm.widthPixels.coerceAtLeast(320)
        val h = dm.heightPixels.coerceAtLeast(240)
        web.layoutParams = ViewGroup.LayoutParams(w, h)
        web.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        )
        web.layout(0, 0, w, h)
        web
    } catch (e: Exception) {
        Logx.e("bg-agent: headless webview creation failed: ${e.message}")
        null
    }
}

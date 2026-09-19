package com.cometx.browser.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.DownloadListener
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.cometx.browser.R
import com.cometx.browser.browse.AdBlocker
import com.cometx.browser.browse.CosmeticFilter
import com.cometx.browser.browse.HttpsFirst
import com.cometx.browser.browse.YouTubeFilter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.cometx.browser.util.Logx
import java.io.ByteArrayInputStream
import java.util.Collections

/**
 * BrowserController — configures the WebView stack (security settings, clients,
 * downloads, popups, file chooser). This is what makes Comet-X a *real* browser.
 *
 * v2.0.0 (additive, ported from the Zerium codebase): network-level ad/tracker
 * blocking (shouldInterceptRequest → 404), cosmetic element-hiding at document
 * start, YouTube ad suppression at document start, HTTPS-first main-frame
 * upgrades, DNT/Sec-GPC privacy headers, full-screen video, explicit SSL-error
 * dialog, geolocation and camera/microphone prompts, real file chooser,
 * favicon capture, find-in-page listener, force-zoom viewport rewrite,
 * algorithmic web darkening, text zoom and zoom controls.
 *
 * LOCKED SECURITY POSTURE (THREAT_MODEL.md — unchanged): JS on, file/content
 * access off, mixed content never, no JS bridge objects. Non-http(s) schemes
 * still require an explicit human decision (expert review P1-13).
 */
class BrowserController(private val activity: Activity) {

    /** Executable/archive extensions require user confirmation before download. */
    private val RISKY_EXT = Regex("""(?i)\.(exe|msi|bat|cmd|sh|jar|apk|dmg|app|deb|rpm|pkg)(\?|$)""")

    /** Applied when each WebView is created. */
    var thirdPartyCookiesEnabled: () -> Boolean = { false }

    var onExternalUrl: ((Uri) -> Unit)? = null
    var onFileChooser: ((WebChromeClient.FileChooserParams, ValueCallback<Array<Uri>>) -> Unit)? = null

    // ---- v2.0.0 behavior hooks (wired from MainActivity/SettingsRepository) ----

    /** Network blocking master switch (checked per request). */
    var blockAdsEnabled: () -> Boolean = { false }

    /** Blocking engine (may be loading; shouldBlock no-ops until ready). */
    var adBlocker: () -> AdBlocker? = { null }

    /** Cosmetic filtering toggle (document-start + page-finish fallback). */
    var blockCosmeticEnabled: () -> Boolean = { false }

    /** YouTube suppression toggle. */
    var youtubeSuppressEnabled: () -> Boolean = { false }

    /** DNT + Sec-GPC main-frame headers. */
    var privacyHeadersEnabled: () -> Boolean = { false }

    /** HTTPS-first main-frame upgrade toggle. */
    var httpsUpgradeEnabled: () -> Boolean = { false }

    /** First-party cookie master switch. */
    var cookiesEnabled: () -> Boolean = { true }

    /** Autoplay without user gesture (default OFF — gesture required). */
    var mediaAutoplayEnabled: () -> Boolean = { false }

    /** Web text zoom percent (50..200). */
    var textZoomPercent: () -> Int = { 100 }

    /** Force-enable zoom viewport rewrite (document start). */
    var forceZoomEnabled: () -> Boolean = { false }

    /** Algorithmic darkening for web content. */
    var webForceDark: () -> Boolean = { false }

    /** True when the tab currently loading is incognito (SSL errors auto-cancel). */
    var isIncognitoTab: (WebView) -> Boolean = { false }

    /** History sink for finished non-incognito pages (MainActivity wires HistoryStore). */
    var onVisitFinished: ((url: String, title: String) -> Unit)? = null

    /** Blocking counter tick — persists per-page totals (MainActivity wires settings). */
    var onPageStats: ((blockedOnPage: Long) -> Unit)? = null

    /** Chrome update hook (back/forward enabled states, omnibox, security icon). */
    var onChromeChanged: (() -> Unit)? = null

    /** Find-in-page results (activity shows the inline bar counter). */
    var onFindResults: ((activeMatchOrdinal: Int, numberOfMatches: Int, isDoneCounting: Boolean) -> Unit)? =
        null

    /** Favicon received (tab grid, pinned shortcuts). */
    var onFavicon: ((WebView, Bitmap) -> Unit)? = null

    /** Full-screen video state changes (activity hides chrome, handles back). */
    var onFullscreenChanged: ((active: Boolean) -> Unit)? = null

    /** Permission grants (camera/mic) — activity forwards runtime requests. */
    var onWebPermissionRequest: ((PermissionRequest) -> Unit)? = null

    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var fullscreenContainer: ViewGroup? = null

    /** Cached capability probe (document-start scripting available). */
    private var docStartSupported = false

    @SuppressLint("SetJavaScriptEnabled")
    fun createWebView(): WebView {
        val web = WebView(activity)
        web.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        // ---- Security posture (THREAT_MODEL.md) ----
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            mediaPlaybackRequiresUserGesture = !mediaAutoplayEnabled()
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
            // v2.0.0: real zoom controls + configurable text size
            setSupportZoom(true)
            setBuiltInZoomControls(true)
            displayZoomControls = false
            setGeolocationEnabled(true) // prompt-gated (dialog below)
            textZoom = textZoomPercent()
        }
        web.setBackgroundColor(activity.getColor(R.color.background))
        web.isFocusableInTouchMode = true
        CookieManager.getInstance().setAcceptCookie(cookiesEnabled())
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, thirdPartyCookiesEnabled())

        // v2.0.0: algorithmic darkening for web content (opt-in)
        if (webForceDark() && WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            runCatching { WebSettingsCompat.setAlgorithmicDarkeningAllowed(web.settings, true) }
        }

        // Never expose a native JS bridge object to pages.
        // Agent ↔ page interaction uses evaluateJavascript only.

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                val scheme = url.scheme?.lowercase() ?: return false
                if (scheme == "http") {
                    // HTTPS-first: upgrade main-frame navigations, skipping local
                    // addresses that have no TLS to upgrade to (v2.0.0).
                    if (httpsUpgradeEnabled() && request.isForMainFrame) {
                        val upgraded = HttpsFirst.upgraded(url.toString())
                        if (upgraded != null) {
                            view.loadUrl(upgraded, buildHeaders())
                            return true
                        }
                    }
                    return false
                }
                // Keep http/https in-app; everything else needs a human decision
                // (a page must never be able to fire intent:// / market:// etc.
                // silently — expert review P1-13).
                if (scheme == "https") return false
                confirmExternalLaunch(url)
                return true
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                // v2.0.0: network-level ad/tracker blocking → empty 404.
                val blocker = adBlocker()
                if (!blockAdsEnabled() || blocker == null || !blocker.isReady()) return null
                val url = request.url.toString()
                val tab = tabFor(view)
                if (blocker.shouldBlock(url, tab?.url)) {
                    tab?.let { it.blockedOnPage++ }
                    blocker.sessionBlocked.incrementAndGet()
                    runCatching {
                        return WebResourceResponse(
                            "text/plain", "utf-8", 404, "Blocked",
                            Collections.emptyMap(),
                            ByteArrayInputStream(ByteArray(0))
                        )
                    }
                }
                return null
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                val tab = tabFor(view)
                tab?.blockedOnPage = 0
                tab?.readerActive = false
                // Track the pre-translation URL for the View-original action.
                if (url.contains(".translate.goog")) {
                    if (tab?.translateSourceUrl == null) {
                        tab?.translateSourceUrl =
                            com.cometx.browser.browse.TranslateSupport.originalFromTranslateUrl(url)
                    }
                } else {
                    tab?.translateSourceUrl = null
                }
                (activity as MainActivity).onPageMeta(view, view.title, url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                (activity as MainActivity).onPageMeta(view, view.title, url)
                CookieManager.getInstance().flush()
                val tab = tabFor(view)
                if (tab != null && !com.cometx.browser.browse.StartPage.isStartPage(url)) {
                    if (!tab.incognito && url.startsWith("http")) {
                        onVisitFinished?.invoke(url, view.title ?: "")
                    }
                    val blocked = tab.blockedOnPage
                    if (blocked > 0) onPageStats?.invoke(blocked)
                    injectCosmeticFallback(view, tab)
                    injectYouTubeFallback(view, tab)
                    view.postDelayed({ tabFor(view)?.let { activityPreview(view, it) } }, 350)
                }
                onChromeChanged?.invoke()
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                onChromeChanged?.invoke()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                // WebView renders its own error page for main-frame failures.
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                // v2.0.0: explicit human decision; incognito always cancels.
                if (isIncognitoTab(view)) {
                    handler.cancel()
                    return
                }
                MaterialAlertDialogBuilder(activity)
                    .setTitle(com.cometx.browser.R.string.ssl_error_title)
                    .setMessage(
                        activity.getString(
                            com.cometx.browser.R.string.ssl_error_message,
                            sslErrorName(error)
                        )
                    )
                    .setPositiveButton(com.cometx.browser.R.string.proceed) { _, _ -> handler.proceed() }
                    .setNegativeButton(com.cometx.browser.R.string.cancel_dialog) { _, _ -> handler.cancel() }
                    .setOnCancelListener { handler.cancel() }
                    .show()
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // Renderer crash must degrade the TAB, never the app.
                Logx.e("renderer gone (didCrash=${detail.didCrash()})")
                runCatching {
                    (view.parent as? ViewGroup)?.removeView(view)
                    view.destroy()
                }
                val idx = tabsIndexOf(view)
                (activity as MainActivity).onRendererGone(idx)
                return true
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                (activity as MainActivity).onProgress(newProgress)
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                (activity as MainActivity).onPageMeta(view, title, view.url)
            }

            override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
                if (icon == null) return
                val tab = tabFor(view) ?: return
                if (tab.incognito) return
                tab.favicon = icon
                onFavicon?.invoke(view, icon)
            }

            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
                // Background popups (no user gesture) are ad-spam — reject them
                // (expert review P1-18).
                if (!isUserGesture) return false
                // Popup/new-window capture: create an offscreen WebView to receive
                // the target URL, then open it as a regular tab.
                val temp = WebView(view.context)
                temp.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                        (activity as MainActivity).openInNewTab(request.url.toString())
                        // the transport WebView's job is done — don't leak it
                        v.destroy()
                        return true
                    }
                }
                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = temp
                resultMsg.sendToTarget()
                return true
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                // v2.0.0: full-screen video (all chrome hidden, screen kept on).
                if (fullscreenView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                fullscreenView = view
                fullscreenCallback = callback
                fullscreenContainer()?.addView(
                    view,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                onFullscreenChanged?.invoke(true)
            }

            override fun onHideCustomView() {
                exitFullscreen()
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                if (onFileChooser != null) {
                    onFileChooser?.invoke(fileChooserParams, filePathCallback)
                } else {
                    // Dead click before (expert review P1-17) — say what happened.
                    android.widget.Toast.makeText(activity, "File upload is not supported yet", android.widget.Toast.LENGTH_SHORT).show()
                    filePathCallback.onReceiveValue(null)
                }
                return true
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: GeolocationPermissions.Callback
            ) {
                // v2.0.0: explicit per-site prompt (Zerium pattern) — nothing is
                // granted silently.
                MaterialAlertDialogBuilder(activity)
                    .setTitle(com.cometx.browser.R.string.location_permission)
                    .setMessage(
                        activity.getString(com.cometx.browser.R.string.location_permission_message, origin)
                    )
                    .setPositiveButton(com.cometx.browser.R.string.allow) { _, _ ->
                        callback.invoke(origin, true, false)
                    }
                    .setNegativeButton(com.cometx.browser.R.string.deny) { _, _ ->
                        callback.invoke(origin, false, false)
                    }
                    .setOnCancelListener { callback.invoke(origin, false, false) }
                    .show()
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                // v2.0.0: camera/mic for web RTC — runtime-gated through the
                // activity; nothing is granted without a system prompt.
                activity.runOnUiThread {
                    val wantsCamera = request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                    val wantsMic = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                    if (!wantsCamera && !wantsMic) {
                        request.deny()
                        return@runOnUiThread
                    }
                    onWebPermissionRequest?.invoke(request)
                }
            }
        }

        web.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            handleDownload(activity, url, userAgent, contentDisposition, mimeType)
        }

        // v2.0.0: find-in-page result counting for the inline find bar.
        web.setFindListener { activeMatchOrdinal, numberOfMatches, isDoneCounting ->
            onFindResults?.invoke(activeMatchOrdinal, numberOfMatches, isDoneCounting)
        }

        // ---- v2.0.0 document-start injections ----
        injectDocumentStartScripts(web)

        return web
    }

    // ------------------------------------------------------- injections

    /** Document-start: cosmetic hiding, YouTube suppression, force-zoom. */
    private fun injectDocumentStartScripts(web: WebView) {
        docStartSupported = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (!docStartSupported) return
        runCatching {
            // Cosmetic rules on every http(s) origin: ad containers never paint.
            if (blockCosmeticEnabled()) {
                val cosmeticScript = CosmeticFilter.getScript(activity, true)
                if (!cosmeticScript.isNullOrEmpty()) {
                    WebViewCompat.addDocumentStartJavaScript(
                        web, cosmeticScript,
                        setOf("http://*/*", "https://*/*")
                    )
                    tabFor(web)?.cosmeticAtStart = true
                }
            }
            // YouTube suppression before the player initializes.
            if (youtubeSuppressEnabled()) {
                val ytScript = YouTubeFilter.script(activity)
                if (ytScript.isNotEmpty()) {
                    WebViewCompat.addDocumentStartJavaScript(web, ytScript, YouTubeFilter.ORIGINS)
                }
            }
            // Force-zoom viewport rewrite (mirrors Firefox Focus / Chrome).
            if (forceZoomEnabled()) {
                WebViewCompat.addDocumentStartJavaScript(
                    web, FORCE_ZOOM_JS, setOf("http://*/*", "https://*/*")
                )
            }
        }
    }

    /** Page-finish fallbacks for engines without document-start scripting. */
    private fun injectCosmeticFallback(view: WebView, tab: TabManager.Tab) {
        if (!blockCosmeticEnabled()) return
        if (docStartSupported && tab.cosmeticAtStart) return
        if (docStartSupported) return // injected at document start during createWebView
        val script = CosmeticFilter.getScript(activity, true)
        if (!script.isNullOrEmpty()) view.evaluateJavascript(script, null)
    }

    private fun injectYouTubeFallback(view: WebView, tab: TabManager.Tab) {
        if (!blockAdsEnabled() || !youtubeSuppressEnabled()) return
        val host = AdBlocker.hostOf(tab.url) ?: return
        if (!YouTubeFilter.matches(host)) return
        val script = YouTubeFilter.script(activity)
        if (script.isNotEmpty()) view.evaluateJavascript(script, null)
    }

    private fun activityPreview(view: WebView, tab: TabManager.Tab) {
        (activity as? MainActivity)?.captureTabPreview(view, tab)
    }

    // ------------------------------------------------------- headers

    /** DNT + Sec-GPC main-frame headers (v2.0.0). */
    fun buildHeaders(): Map<String, String> {
        if (!privacyHeadersEnabled()) return emptyMap()
        return mapOf("DNT" to "1", "Sec-GPC" to "1")
    }

    // ------------------------------------------------------- fullscreen

    private fun fullscreenContainer(): ViewGroup {
        fullscreenContainer?.let { return it }
        val fc = android.widget.FrameLayout(activity)
        activity.addContentView(
            fc,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        fc.visibility = View.GONE
        fullscreenContainer = fc
        return fc
    }

    fun isInFullscreen(): Boolean = fullscreenView != null

    /** Exits full-screen video; safe to call repeatedly (back-press path). */
    fun exitFullscreen() {
        val view = fullscreenView ?: return
        runCatching {
            fullscreenContainer?.removeAllViews()
            fullscreenContainer?.visibility = View.GONE
            fullscreenCallback?.onCustomViewHidden()
        }
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        fullscreenView = null
        fullscreenCallback = null
        onFullscreenChanged?.invoke(false)
    }

    // ------------------------------------------------------- tab lookup

    /** Tabs registry hook (MainActivity wires it) for per-tab state lookup. */
    var tabLookup: ((WebView) -> TabManager.Tab?)? = null

    private fun tabFor(view: WebView): TabManager.Tab? = tabLookup?.invoke(view)

    private fun tabsIndexOf(view: WebView): Int {
        val tab = tabFor(view) ?: return -1
        return (activity as? MainActivity)?.indexOfTab(tab) ?: -1
    }

    // ------------------------------------------------------- misc

    /** Non-http(s) launch: user-gated (expert review P1-13). */
    private fun confirmExternalLaunch(url: Uri) {
        MaterialAlertDialogBuilder(activity)
            .setTitle("Open in another app?")
            .setMessage("This page wants to hand off to an external app:\n${url.toString().take(160)}")
            .setPositiveButton("Open") { _, _ ->
                try {
                    onExternalUrl?.invoke(url) ?: activity.startActivity(Intent(Intent.ACTION_VIEW, url))
                } catch (e: Exception) {
                    Logx.w("no handler for ${url.scheme}")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Download entry point for agent-initiated downloads (tab-verb wiring). */
    fun downloadDirect(url: String) {
        handleDownload(activity, url, "", "", "")
    }

    private fun handleDownload(
        context: Context,
        url: String,
        userAgent: String,
        contentDisposition: String,
        mimeType: String
    ) {
        val risky = RISKY_EXT.containsMatchIn(url)
        fun enqueue() {
            try {
                val req = DownloadManager.Request(Uri.parse(url))
                    .setMimeType(mimeType)
                    .setTitle(Uri.parse(url).lastPathSegment ?: "cometx-download")
                    .setDescription("Comet-X download")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, Uri.parse(url).lastPathSegment ?: "cometx-${System.currentTimeMillis()}")
                if (userAgent.isNotBlank()) req.addRequestHeader("User-Agent", userAgent)
                // v2.0.0: forward session cookies so authenticated downloads work
                runCatching {
                    CookieManager.getInstance().getCookie(url)?.let { req.addRequestHeader("Cookie", it) }
                }
                (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                Logx.i("download enqueued: ${url.take(80)}")
            } catch (e: Exception) {
                Logx.e("download failed", e)
            }
        }
        if (risky) {
            MaterialAlertDialogBuilder(context)
                .setTitle("Download executable file?")
                .setMessage("This file type can run code on your device:\n${url.take(160)}")
                .setPositiveButton("Download") { _, _ -> enqueue() }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            enqueue()
        }
    }

    /** Applied live when Settings changes (text zoom across all tabs). */
    fun applyTextZoomToAll(tabs: List<TabManager.Tab>, percent: Int) {
        for (t in tabs) {
            runCatching { t.webView.settings.textZoom = percent }
        }
    }

    private fun sslErrorName(error: android.net.http.SslError): String = runCatching {
        when (error.primaryError) {
            android.net.http.SslError.SSL_EXPIRED -> "certificate expired"
            android.net.http.SslError.SSL_IDMISMATCH -> "hostname mismatch"
            android.net.http.SslError.SSL_NOTYETVALID -> "certificate not yet valid"
            android.net.http.SslError.SSL_UNTRUSTED -> "untrusted certificate"
            android.net.http.SslError.SSL_DATE_INVALID -> "invalid certificate date"
            android.net.http.SslError.SSL_INVALID -> "invalid certificate"
            else -> "SSL error"
        }
    }.getOrDefault("SSL error")

    companion object {
        private val FORCE_ZOOM_JS =
            "(function(){function f(){try{" +
                "var m=document.querySelector('meta[name=\"viewport\"]');" +
                "if(m){m.setAttribute('content'," +
                "'width=device-width, initial-scale=1, maximum-scale=5, user-scalable=yes');}" +
                "}catch(e){}}f();" +
                "document.addEventListener('DOMContentLoaded',f);})();"

        /** Start-page sentinel shared with MainActivity (TabManager.StartPageUrl). */
        const val START_PAGE_URL = TabManager.StartPageUrl
    }
}

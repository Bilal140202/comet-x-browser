package com.cometx.browser.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.cometx.browser.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.cometx.browser.util.Logx

/**
 * BrowserController — configures the WebView stack (security settings, clients,
 * downloads, popups, file chooser). This is what makes Comet-X a *real* browser.
 */
class BrowserController(private val activity: Activity) {

    /** Executable/archive extensions require user confirmation before download. */
    private val RISKY_EXT = Regex("""(?i)\.(exe|msi|bat|cmd|sh|jar|apk|dmg|app|deb|rpm|pkg)(\?|$)""")

    /** Applied when each WebView is created. */
    var thirdPartyCookiesEnabled: () -> Boolean = { false }

    var onExternalUrl: ((Uri) -> Unit)? = null
    var onFileChooser: ((android.webkit.ValueCallback<Array<Uri>>) -> Unit)? = null

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
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
            setGeolocationEnabled(false)
            textZoom = 100
        }
        web.setBackgroundColor(activity.getColor(R.color.background))
        web.isFocusableInTouchMode = true
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, thirdPartyCookiesEnabled())

        // Never expose a native JS bridge object to pages.
        // Agent ↔ page interaction uses evaluateJavascript only.

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                val scheme = url.scheme?.lowercase() ?: return false
                // Keep http/https in-app; everything else needs a human decision
                // (a page must never be able to fire intent:// / market:// etc.
                // silently — expert review P1-13).
                if (scheme == "http" || scheme == "https") return false
                confirmExternalLaunch(url)
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                (activity as MainActivity).onPageMeta(view, view.title, url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                (activity as MainActivity).onPageMeta(view, view.title, url)
                CookieManager.getInstance().flush()
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                (activity as MainActivity).onProgress(newProgress)
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

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: android.webkit.ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                if (onFileChooser != null) {
                    onFileChooser?.invoke(filePathCallback)
                } else {
                    // Dead click before (expert review P1-17) — say what happened.
                    android.widget.Toast.makeText(activity, "File upload is not supported yet", android.widget.Toast.LENGTH_SHORT).show()
                    filePathCallback.onReceiveValue(null)
                }
                return true
            }
        }

        web.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            handleDownload(activity, url, userAgent, contentDisposition, mimeType)
        }

        return web
    }

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
                req.addRequestHeader("User-Agent", userAgent)
                (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                com.cometx.browser.util.Logx.i("download enqueued: ${url.take(80)}")
            } catch (e: Exception) {
                com.cometx.browser.util.Logx.e("download failed", e)
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
}

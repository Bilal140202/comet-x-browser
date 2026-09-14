package com.cometx.browser.background

import android.app.DownloadManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Environment
import android.webkit.WebView
import com.cometx.browser.automation.ActionExecutor
import com.cometx.browser.engine.AgentSink
import com.cometx.browser.engine.SomShot
import com.cometx.browser.perception.DomExtractor
import com.cometx.browser.perception.PageObservation
import com.cometx.browser.perception.Screenshotter
import com.cometx.browser.util.Logx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * HeadlessWebViewSink (v1.8.0) — the AgentSink implementation for background
 * runs. Same engine contract as [com.cometx.browser.engine.LiveWebViewSink],
 * but bound to the service-owned ISOLATED WebView instead of a user tab:
 *
 *  - observe(): DOM/a11y extraction from the headless view (works without a
 *    window — evaluateJavascript is surface-independent)
 *  - execute(): the SAME ActionExecutor JS pipeline as foreground runs, so
 *    click/type/scroll semantics are byte-identical; tab-level verbs are
 *    adapted (open_tab = navigate here; switch/close don't exist headless)
 *  - screenshots: manual measure→layout→draw(Canvas) capture. PixelCopy is
 *    unavailable headless (no window); the software layer set by the factory
 *    makes draw() produce genuine pixels. Vision + Set-of-Marks work.
 *  - downloads: non-executable files go straight to the system DownloadManager
 *    (which shows its own progress notifications); executables are REFUSED —
 *    a background task must never place an .apk on the device unattended.
 */
class HeadlessWebViewSink(private val context: Context, private val web: WebView) : AgentSink {

    private val executor = ActionExecutor(context)

    override suspend fun observe(): PageObservation? = try {
        val title = web.title?.takeIf { it.isNotBlank() } ?: "Background task"
        val url = web.url ?: ""
        DomExtractor.observe(web, listOf(PageObservation.TabInfo(0, title, url, true)), 0)
    } catch (e: Exception) {
        Logx.e("bg-agent: observe failed: ${e.message}")
        null
    }

    override suspend fun execute(action: JSONObject): ActionExecutor.Result = try {
        when (action.optString("action", "")) {
            "open_tab" -> {
                val url = action.optString("url", "")
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    web.loadUrl(url)
                    ActionExecutor.Result(true, "opened: ${url.take(80)}")
                } else ActionExecutor.Result(false, "non-web URL blocked")
            }
            "switch_tab" -> ActionExecutor.Result(false, "background task has a single isolated page (no tabs)")
            "close_tab" -> ActionExecutor.Result(false, "background task has a single isolated page (no tabs)")
            "download" -> downloadHeadless(action)
            else -> executor.execute(web, action)
        }
    } catch (e: Exception) {
        Logx.e("bg-agent: execute failed: ${e.message}")
        ActionExecutor.Result(false, "executor exception: ${e.message}")
    }

    /** Executables are never fetched unattended (browser layer asks the user; there is no user here). */
    private val riskyExt = Regex("""(?i)\.(exe|msi|bat|cmd|sh|jar|apk|dmg|app|deb|rpm|pkg)(\?|$)""")

    private fun downloadHeadless(action: JSONObject): ActionExecutor.Result {
        val url = action.optString("url", "").ifBlank { action.optString("ref", "") }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ActionExecutor.Result(false, "no downloadable http(s) URL in action")
        }
        if (riskyExt.containsMatchIn(url)) {
            return ActionExecutor.Result(false, "executable downloads are blocked in background mode")
        }
        return try {
            val name = android.net.Uri.parse(url).lastPathSegment ?: "cometx-${System.currentTimeMillis()}"
            val req = DownloadManager.Request(android.net.Uri.parse(url))
                .setTitle(name)
                .setDescription("Comet-X background agent download")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            ActionExecutor.Result(true, "download started: ${url.take(80)}")
        } catch (e: Exception) {
            Logx.e("bg-agent: download enqueue failed: ${e.message}")
            ActionExecutor.Result(false, "download failed: ${e.message}")
        }
    }

    override suspend fun screenshotBase64(): String? {
        val bmp = captureBitmap() ?: return null
        return Screenshotter.toBase64Jpeg(bmp)
    }

    override suspend fun screenshotAnnotatedBase64(obs: PageObservation): SomShot? {
        val bmp = captureBitmap() ?: return null
        return try {
            Screenshotter.toAnnotatedBase64Jpeg(bmp, obs.elements, obs.viewportW, obs.viewportH)
                ?.let { SomShot(it.first, it.second) }
        } catch (e: Exception) {
            Logx.e("bg-agent: som annotate failed: ${e.message}")
            Screenshotter.toBase64Jpeg(bmp)?.let { SomShot(it, 0) }
        }
    }

    /**
     * Headless capture: re-measure against the CURRENT content height (pages
     * grow while the task runs), then draw into a bitmap. Never throws.
     */
    private suspend fun captureBitmap(): Bitmap? = withContext(Dispatchers.Main) {
        try {
            val dm = context.resources.displayMetrics
            val w = web.measuredWidth.takeIf { it > 0 } ?: dm.widthPixels.coerceAtLeast(320)
            val h = web.measuredHeight.takeIf { it > 0 } ?: dm.heightPixels.coerceAtLeast(240)
            web.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY)
            )
            web.layout(0, 0, w, h)
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            web.draw(Canvas(out))
            out
        } catch (e: Exception) {
            Logx.e("bg-agent: headless capture failed: ${e.message}")
            null
        }
    }
}

package com.cometx.browser.engine

import android.content.Context
import android.webkit.WebView
import com.cometx.browser.automation.ActionExecutor
import com.cometx.browser.perception.DomExtractor
import com.cometx.browser.perception.PageObservation
import com.cometx.browser.perception.Screenshotter
import org.json.JSONObject

/**
 * AgentSink — the engine's interface to the browser. The live implementation
 * drives a WebView; tests substitute a fake sink with scripted pages. This
 * mirrors the driver abstraction used by desktop browser agents and keeps the
 * agent loop itself free of Android types (headlessly testable).
 */
interface AgentSink {
    suspend fun observe(): PageObservation?
    suspend fun execute(action: JSONObject): ActionExecutor.Result
    suspend fun screenshotBase64(): String?
}

/** Live sink bound to the current tab's WebView. */
class LiveWebViewSink(
    private val context: Context,
    private val webViewProvider: () -> WebView?,
    private val tabsProvider: () -> List<PageObservation.TabInfo>,
    private val activeTabIndexProvider: () -> Int
) : AgentSink {

    private val executor = ActionExecutor(context)

    override suspend fun observe(): PageObservation? {
        val web = webViewProvider() ?: return null
        return DomExtractor.observe(web, tabsProvider(), activeTabIndexProvider())
    }

    override suspend fun execute(action: JSONObject): ActionExecutor.Result {
        val web = webViewProvider() ?: return ActionExecutor.Result(false, "browser view not ready")
        return executor.execute(web, action)
    }

    override suspend fun screenshotBase64(): String? {
        val web = webViewProvider() ?: return null
        val bmp = Screenshotter.capture(web) ?: return null
        return Screenshotter.toBase64Jpeg(bmp)
    }
}

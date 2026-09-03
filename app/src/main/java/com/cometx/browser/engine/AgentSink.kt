package com.cometx.browser.engine

import android.content.Context
import android.webkit.WebView
import com.cometx.browser.automation.ActionExecutor
import com.cometx.browser.perception.DomExtractor
import com.cometx.browser.perception.PageObservation
import com.cometx.browser.perception.Screenshotter
import com.cometx.browser.util.Logx
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

/**
 * Live sink bound to the current tab's WebView.
 *
 * Tab-level verbs (open_tab / close_tab / switch_tab / download) are routed
 * to the browser layer via callbacks — previously they were advertised to the
 * model but died in the executor ("not executable here"), burning steps
 * (expert review P0-1).
 */
class LiveWebViewSink(
    private val context: Context,
    private val webViewProvider: () -> WebView?,
    private val tabsProvider: () -> List<PageObservation.TabInfo>,
    private val activeTabIndexProvider: () -> Int,
    private val onOpenTab: (String) -> Unit = {},
    private val onSwitchTab: (Int) -> Unit = {},
    private val onCloseTab: (Int) -> Unit = {},
    private val onDownload: (String) -> Unit = {}
) : AgentSink {

    private val executor = ActionExecutor(context)

    override suspend fun observe(): PageObservation? {
        val web = webViewProvider() ?: return null
        return DomExtractor.observe(web, tabsProvider(), activeTabIndexProvider())
    }

    override suspend fun execute(action: JSONObject): ActionExecutor.Result {
        val kind = action.optString("action", "")
        return when (kind) {
            "open_tab" -> {
                val url = action.optString("url", "")
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    onOpenTab(url)
                    ActionExecutor.Result(true, "opened new tab: ${url.take(80)}")
                } else ActionExecutor.Result(false, "non-web URL blocked")
            }
            "switch_tab" -> {
                val idx = action.optInt("index", -1)
                val tabs = tabsProvider()
                if (idx in tabs.indices) {
                    onSwitchTab(idx)
                    ActionExecutor.Result(true, "switched to tab $idx (${tabs[idx].title.take(40)})")
                } else ActionExecutor.Result(false, "tab $idx does not exist (${tabs.size} tabs)")
            }
            "close_tab" -> {
                val idx = action.optInt("index", -1)
                val tabs = tabsProvider()
                if (idx in tabs.indices && tabs.size > 1) {
                    onCloseTab(idx)
                    ActionExecutor.Result(true, "closed tab $idx")
                } else if (tabs.size <= 1) ActionExecutor.Result(false, "cannot close the last tab")
                else ActionExecutor.Result(false, "tab $idx does not exist")
            }
            "download" -> {
                val url = action.optString("url", "").ifBlank { action.optString("ref", "") }
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    onDownload(url)
                    ActionExecutor.Result(true, "download requested: ${url.take(80)}")
                } else ActionExecutor.Result(false, "no downloadable http(s) URL in action")
            }
            else -> {
                val web = webViewProvider()
                    ?: return ActionExecutor.Result(false, "browser view not ready")
                executor.execute(web, action)
            }
        }
    }

    override suspend fun screenshotBase64(): String? {
        val web = webViewProvider() ?: return null
        val bmp = try {
            Screenshotter.capture(web)
        } catch (e: Exception) {
            Logx.e("screenshot failed", e)
            null
        } ?: return null
        return Screenshotter.toBase64Jpeg(bmp)
    }
}

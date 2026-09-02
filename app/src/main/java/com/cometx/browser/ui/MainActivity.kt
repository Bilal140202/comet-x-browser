package com.cometx.browser.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.cometx.browser.CometApp
import com.cometx.browser.R
import com.cometx.browser.ai.CustomOpenAIProvider
import com.cometx.browser.ai.GroqProvider
import com.cometx.browser.ai.HuggingFaceProvider
import com.cometx.browser.ai.ModelRouter
import com.cometx.browser.ai.OpenAICompatibleProvider
import com.cometx.browser.ai.OpenRouterProvider
import com.cometx.browser.ai.SettingsRepository
import com.cometx.browser.ai.UrlNormalizer
import com.cometx.browser.automation.ActionExecutor
import com.cometx.browser.automation.LocalTestServer
import com.cometx.browser.engine.AgentEngine
import com.cometx.browser.engine.LiveWebViewSink
import com.cometx.browser.memory.MemoryStore
import com.cometx.browser.perception.ChallengeResult
import com.cometx.browser.perception.PageObservation
import com.cometx.browser.perception.VisionPolicy
import com.cometx.browser.security.SecureStore
import com.cometx.browser.skills.SkillRegistry
import com.cometx.browser.util.Logx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class MainActivity : Activity() {

    private lateinit var browser: BrowserController
    private lateinit var tabs: TabManager
    private lateinit var engine: AgentEngine
    private lateinit var panel: AgentPanelController
    private lateinit var settings: SettingsRepository
    private lateinit var memory: MemoryStore
    private lateinit var testServer: LocalTestServer

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var urlBar: EditText? = null
    private var progress: ProgressBar? = null

    // ---- providers built once; keys read live from secure store ----
    private lateinit var providers: Map<String, OpenAICompatibleProvider>
    private lateinit var router: ModelRouter

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val app = CometApp.app
        val secure = SecureStore(app)
        settings = SettingsRepository(app, secure)
        memory = MemoryStore(app.filesDir) { settings.memoryEnabled() }
        val skills = SkillRegistry(app)
        val executor = ActionExecutor(this)

        providers = mapOf(
            "groq" to GroqProvider { settings.apiKey("groq") },
            "openrouter" to OpenRouterProvider { settings.apiKey("openrouter") },
            "huggingface" to HuggingFaceProvider { settings.apiKey("huggingface") },
            "custom" to CustomOpenAIProvider(
                keyProvider = { settings.apiKey("custom") },
                readyCheck = { !settings.apiKey("custom").isNullOrBlank() || !settings.baseUrl("custom").isNullOrBlank() }
            )
        )
        // apply user-saved base URLs (self-run endpoints) — previously silently ignored
        for ((pid, prov) in providers) {
            settings.baseUrl(pid)?.let { prov.setBaseUrl(UrlNormalizer.normalize(it)) }
        }
        router = ModelRouter(settings, providers)

        browser = BrowserController(this)

        val container = findViewById<ViewGroup>(R.id.webContainer)
        tabs = TabManager(container, { browser.createWebView() }, { syncTabUi() })
        browser.thirdPartyCookiesEnabled = { settings.thirdPartyCookies() }

        engine = AgentEngine(
            router, settings, memory,
            VisionPolicy(settings),
            LiveWebViewSink(this, { tabs.currentWebView }, {
                tabs.tabs.mapIndexed { i, t ->
                    PageObservation.TabInfo(i, t.title.ifBlank { "Untitled" }, t.url, i == tabs.currentIndex)
                }
            }, { tabs.currentIndex })
        )
        panel = AgentPanelController(this, engine, settings, skills)
        engine.bind(panel)

        // ---- top bar ----
        urlBar = findViewById(R.id.urlBar)
        progress = findViewById(R.id.progress)
        findViewById<Button>(R.id.btnBack).setOnClickListener { tabs.currentWebView?.goBack() }
        findViewById<Button>(R.id.btnForward).setOnClickListener { tabs.currentWebView?.goForward() }
        findViewById<Button>(R.id.btnReload).setOnClickListener { tabs.currentWebView?.reload() }
        urlBar?.setOnEditorActionListener { v, _, _ ->
            val raw = v.text.toString().trim()
            if (raw.isNotEmpty()) loadUserUrl(raw)
            true
        }
        findViewById<Button>(R.id.btnMenu).setOnClickListener { showMenu(it) }
        findViewById<Button>(R.id.btnTabs).setOnClickListener { showTabDialog() }

        // ---- challenge banner ----
        findViewById<Button>(R.id.btnChallengeTake).setOnClickListener {
            engine.takeControl("challenge in progress")
            findViewById<LinearLayout>(R.id.challengeBanner).visibility = View.GONE
            Toast.makeText(this, "You have control. Complete the challenge, then Resume.", Toast.LENGTH_LONG).show()
        }
        findViewById<Button>(R.id.btnChallengeResume).setOnClickListener {
            findViewById<LinearLayout>(R.id.challengeBanner).visibility = View.GONE
            engine.resume(null)
        }

        // ---- intent / start page ----
        val startUrl = intent?.dataString ?: settings.homepage()
        tabs.newTab(startUrl)
        memory.lastBrowserState()?.let { (u, _) -> if (startUrl == settings.homepage() && u.isNotBlank()) { /* restore hint only */ } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.dataString?.let { openInNewTab(it) }
    }

    fun loadUserUrl(raw: String) {
        val input = raw.trim()
        val url = when {
            input.startsWith("http://") || input.startsWith("https://") -> input
            input.contains(" ") || !input.contains(".") -> "https://www.google.com/search?q=" + Uri.encode(input)
            else -> "https://$input"
        }
        urlBar?.setText(url)
        tabs.currentWebView?.loadUrl(url)
    }

    fun openInNewTab(url: String) {
        runOnUiThread {
            // Scheme gate (red-team F8): only http/https may open as tabs
            val lower = url.trim().lowercase()
            if (lower.startsWith("http://") || lower.startsWith("https://")) {
                tabs.newTab(url.trim())
            } else {
                Toast.makeText(this, "Blocked non-web URL", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun onPageMeta(view: WebView, title: String?, url: String?) {
        runOnUiThread {
            tabs.updateMeta(view, title, url)
            if (view == tabs.currentWebView) {
                if (urlBar?.hasFocus() != true) urlBar?.setText(url ?: "")
            }
        }
    }

    fun onProgress(p: Int) {
        runOnUiThread {
            progress?.progress = p
            progress?.visibility = if (p in 1..99) View.VISIBLE else View.GONE
        }
    }

    private fun syncTabUi() { /* hook for future tab-count badge */ }

    // ------------------------------------------------------------- menu

    private fun showMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add("New tab")
        menu.menu.add("Close current tab")
        menu.menu.add("Clear browsing data")
        menu.menu.add("Agent self-test (local pages)")
        menu.menu.add("Settings")
        menu.setOnMenuItemClickListener { item ->
            when (item.title) {
                "New tab" -> tabs.newTab(settings.homepage())
                "Close current tab" -> tabs.closeCurrent()
                "Clear browsing data" -> confirmClearData()
                "Agent self-test (local pages)" -> startSelfTest()
                "Settings" -> startActivity(Intent(this, SettingsActivity::class.java))
            }
            true
        }
        menu.show()
    }

    private fun confirmClearData() {
        AlertDialog.Builder(this)
            .setTitle("Clear browsing data?")
            .setMessage("Cookies, site storage and cache will be removed. Agent memory is not affected (manage it in Settings).")
            .setPositiveButton("Clear") { _, _ ->
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
                tabs.currentWebView?.clearCache(true)
                tabs.currentWebView?.clearHistory()
                Toast.makeText(this, "Browsing data cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Boots the loopback test server and opens the index page in a new tab. */
    private fun startSelfTest() {
        if (!::testServer.isInitialized) {
            testServer = LocalTestServer(8081)
        }
        if (!testServer.start()) {
            // port busy → assume already running from a previous tap
        }
        openInNewTab("http://127.0.0.1:8081/test/index.html")
        Toast.makeText(this, "Local test pages served on 127.0.0.1:8081", Toast.LENGTH_LONG).show()
    }

    private fun showTabDialog() {
        val items = tabs.titles()
        val listItems = items.mapIndexed { i, (t, u) -> "${i + 1}. $t" + (if (i == tabs.currentIndex) "  ●" else "") }
        val lv = ListView(this)
        lv.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, listItems)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Tabs (${items.size})")
            .setView(lv)
            .setPositiveButton("New tab") { _, _ -> tabs.newTab(settings.homepage()) }
            .setNegativeButton("Close", null)
            .create()
        lv.setOnItemClickListener { _, _, which, _ ->
            tabs.switchTo(which)
            dialog.dismiss()
        }
        lv.setOnItemLongClickListener { _, _, which, _ ->
            tabs.close(which)
            dialog.dismiss()
            true
        }
        dialog.show()
    }

    override fun onBackPressed() {
        if (panel.isVisible()) { panel.collapse(); return }
        val web = tabs.currentWebView
        if (web?.canGoBack() == true) web.goBack() else super.onBackPressed()
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
        tabs.currentWebView?.onPause()
    }

    override fun onResume() {
        super.onResume()
        tabs.currentWebView?.onResume()
    }

    override fun onDestroy() {
        if (::testServer.isInitialized) testServer.stop()
        super.onDestroy()
    }
}

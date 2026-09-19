package com.cometx.browser.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.print.PrintAttributes
import android.print.PrintManager
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cometx.browser.CometApp
import com.cometx.browser.R
import com.cometx.browser.ai.LlmProvider
import com.cometx.browser.ai.ModelCatalog
import com.cometx.browser.ai.ModelRouter
import com.cometx.browser.ai.ProviderSet
import com.cometx.browser.ai.SettingsRepository
import com.cometx.browser.automation.ActionExecutor
import com.cometx.browser.automation.LocalTestServer
import com.cometx.browser.browse.AdBlocker
import com.cometx.browser.browse.BookmarksStore
import com.cometx.browser.browse.CosmeticFilter
import com.cometx.browser.browse.FilterUpdater
import com.cometx.browser.browse.HistoryStore
import com.cometx.browser.browse.ReaderSupport
import com.cometx.browser.browse.SearchEngines
import com.cometx.browser.browse.StartPage
import com.cometx.browser.browse.StartPageLogic
import com.cometx.browser.browse.TranslateSupport
import com.cometx.browser.engine.AgentEngine
import com.cometx.browser.engine.LiveWebViewSink
import com.cometx.browser.memory.MemoryStore
import com.cometx.browser.perception.PageObservation
import com.cometx.browser.perception.VisionPolicy
import com.cometx.browser.security.SecureStore
import com.cometx.browser.skills.SkillInterview
import com.cometx.browser.skills.SkillPlayer
import com.cometx.browser.skills.SkillRecorder
import com.cometx.browser.skills.SkillRegistry
import com.cometx.browser.skills.UserSkillStore
import com.cometx.browser.util.Logx
import com.cometx.browser.util.UserInput
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class MainActivity : AppCompatActivity() {

    private lateinit var browser: BrowserController
    private lateinit var tabs: TabManager
    private lateinit var engine: AgentEngine
    private lateinit var panel: AgentPanelController
    private lateinit var settings: SettingsRepository
    private lateinit var memory: MemoryStore
    private lateinit var testServer: LocalTestServer
    private lateinit var recorder: SkillRecorder
    private lateinit var adblock: AdBlocker
    private lateinit var bookmarks: BookmarksStore
    private lateinit var history: HistoryStore

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var urlBar: EditText? = null
    private var progress: ProgressBar? = null
    private lateinit var container: ViewGroup
    private var swipe: BrowserSwipeLayout? = null
    private var securityIcon: ImageView? = null
    private var tabsAdapter: TabGridAdapter? = null

    // v1.6.1: while the omnibox commit is in flight, the defocus listener must
    // not restore the previous page URL over the freshly committed one.
    private var suppressUrlRestore = false

    // ---- find bar (v2.0.0) ----
    private var findBar: LinearLayout? = null
    private var findInput: EditText? = null
    private var findCount: TextView? = null
    private var findPending: Runnable? = null

    // ---- v2.0.0 permission/file plumbing ----
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingWebPermission: PermissionRequest? = null
    private var cachedDesktopUA: String? = null

    // ---- providers built once; keys read live from secure store ----
    private lateinit var providers: Map<String, LlmProvider>
    private lateinit var router: ModelRouter

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // v2.0.0: Material You dynamic color (Android 12+, user-toggleable).
        // Read directly from prefs — no SecureStore needed before inflation.
        val earlyPrefs = getSharedPreferences("cometx_settings", Context.MODE_PRIVATE)
        if (earlyPrefs.getBoolean("material_you", true) && android.os.Build.VERSION.SDK_INT >= 31) {
            com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(this)
        }
        setContentView(R.layout.activity_main)

        val app = CometApp.app
        val secure = SecureStore(app)
        settings = SettingsRepository(app, secure)
        memory = MemoryStore(app.filesDir) { settings.memoryEnabled() }
        adblock = app.adBlocker
        bookmarks = BookmarksStore(this)
        history = HistoryStore(this)
        val skills = SkillRegistry(app)
        val executor = ActionExecutor(this)

        // v1.8.0: provider construction lives in ProviderSet (shared with the
        // background agent service — one map, no drift). Same providers, same
        // live key reads, byte-identical behavior to the v1.7.0 inline map.
        providers = ProviderSet.build(settings)
        // apply user-saved base URLs (self-run endpoints) — previously silently ignored
        ProviderSet.applyBaseUrls(settings, providers)
        // Phase 2 migration: v1.1.0 per-role model picks become optional Advanced overrides
        settings.runModeMigration()
        router = ModelRouter(settings, providers, ModelCatalog(app))

        browser = BrowserController(this)
        container = findViewById(R.id.webContainer)
        tabs = TabManager(container, { browser.createWebView() }, { syncTabUi() })
        browser.tabLookup = { view -> tabs.tabs.firstOrNull { it.webView == view } }
        browser.thirdPartyCookiesEnabled = { settings.thirdPartyCookies() }
        browser.blockAdsEnabled = { settings.blockAds() }
        browser.adBlocker = { adblock }
        browser.blockCosmeticEnabled = { settings.blockCosmetic() }
        browser.youtubeSuppressEnabled = { settings.youtubeSuppress() }
        browser.privacyHeadersEnabled = { settings.privacyHeaders() }
        browser.httpsUpgradeEnabled = { settings.httpsUpgrade() }
        browser.cookiesEnabled = { settings.cookiesEnabled() }
        browser.mediaAutoplayEnabled = { settings.mediaAutoplay() }
        browser.textZoomPercent = { settings.textZoom() }
        browser.forceZoomEnabled = { settings.forceZoom() }
        browser.webForceDark = { settings.webForceDark() }
        browser.isIncognitoTab = { view -> browser.tabLookup?.invoke(view)?.incognito == true }
        browser.onVisitFinished = { url, title -> runCatching { history.add(url, title) } }
        browser.onPageStats = { blocked -> runCatching { settings.addTotalBlocked(blocked) } }
        browser.onChromeChanged = { updateChrome() }
        browser.onFindResults = { active, total, done -> onFindResults(active, total, done) }
        browser.onFavicon = { _, _ -> if (tabSwitcherVisible()) tabsAdapter?.notifyDataSetChanged() }
        browser.onFullscreenChanged = { active -> onFullscreenChanged(active) }
        browser.onWebPermissionRequest = { request -> handleWebPermission(request) }
        browser.onFileChooser = { params, callback -> launchFileChooser(params, callback) }

        engine = AgentEngine(
            router, settings, memory,
            VisionPolicy(settings),
            LiveWebViewSink(this, { tabs.currentWebView }, {
                tabs.tabs.mapIndexed { i, t ->
                    PageObservation.TabInfo(i, t.title.ifBlank { "Untitled" }, t.url, i == tabs.currentIndex)
                }
            }, { tabs.currentIndex },
                // Tab-level verbs (expert review P0-1): the model can now really use them
                onOpenTab = { url -> openInNewTab(url) },
                onSwitchTab = { idx -> runOnUiThread { tabs.switchTo(idx); syncOmniboxToCurrentTab() } },
                onCloseTab = { idx -> runOnUiThread { tabs.close(idx); syncOmniboxToCurrentTab() } },
                onDownload = { url -> runOnUiThread { browser.downloadDirect(url) } }
            )
        )

        // ---- Phase 3: skills infrastructure ----
        // Bridges forward events to the panel; they read `panel` lazily so the
        // creation order (recorder → interview → panel) never matters.
        fun panelIfReady(): AgentPanelController? = if (::panel.isInitialized) panel else null
        val recorderBridge = object : SkillRecorder.Listener {
            override fun onStepCountChanged(count: Int) { panelIfReady()?.recorderListener?.onStepCountChanged(count) }
            override fun onRecordError(message: String) { panelIfReady()?.recorderListener?.onRecordError(message) }
        }
        val interviewBridge = object : SkillInterview.Listener {
            override fun onQuestion(question: String) { panelIfReady()?.interviewListener?.onQuestion(question) }
            override fun onLog(line: String) { panelIfReady()?.interviewListener?.onLog(line) }
            override fun onDraftReady(skill: com.cometx.browser.skills.RecordedSkill, jsonText: String) { panelIfReady()?.interviewListener?.onDraftReady(skill, jsonText) }
            override fun onError(message: String) { panelIfReady()?.interviewListener?.onError(message) }
            override fun onEnded() { panelIfReady()?.interviewListener?.onEnded() }
        }
        val playerBridge = object : SkillPlayer.Listener {
            override fun onStepStarted(index: Int, total: Int, description: String) { panelIfReady()?.playerListener?.onStepStarted(index, total, description) }
            override fun onStepResult(index: Int, ok: Boolean, message: String) { panelIfReady()?.playerListener?.onStepResult(index, ok, message) }
            override fun onFinished(success: Boolean, summary: String) { panelIfReady()?.playerListener?.onFinished(success, summary) }
            override suspend fun askSensitiveValue(fieldDescription: String): String? =
                panelIfReady()?.playerListener?.askSensitiveValue(fieldDescription)
            override suspend fun confirmStep(message: String): Boolean =
                panelIfReady()?.playerListener?.confirmStep(message) ?: false
        }

        recorder = SkillRecorder(scope, recorderBridge)
        val interview = SkillInterview(router, interviewBridge)
        val userSkillStore = UserSkillStore(this)
        panel = AgentPanelController(
            this, engine, settings, skills,
            recorder, userSkillStore, interview,
            playerFactory = {
                SkillPlayer(
                    LiveWebViewSink(this, { tabs.currentWebView }, {
                        tabs.tabs.mapIndexed { i, t ->
                            PageObservation.TabInfo(i, t.title.ifBlank { "Untitled" }, t.url, i == tabs.currentIndex)
                        }
                    }, { tabs.currentIndex }),
                    { tabs.currentWebView }, router,
                    aiFallbackEnabled = { settings.skillAiFallback() },
                    confirmHighRisk = { settings.confirmHighRisk() },
                    listener = playerBridge
                )
            },
            webViewProvider = { tabs.currentWebView },
            currentUrlProvider = { tabs.current?.url ?: StartPage.HOME_URL }
        )
        engine.bind(panel)

        // v1.8.0: background agent launcher — request the notification perm
        // opportunistically (denial never blocks, DL-7 spirit) and hand the
        // goal to the isolated foreground service.
        panel.launchBackground = { goal ->
            maybeAskNotificationPermission()
            runCatching { com.cometx.browser.background.AgentTaskService.start(this, goal) }
                .onFailure {
                    Toast.makeText(this, "Could not start the background task", Toast.LENGTH_SHORT).show()
                }
        }

        // ---- top bar ----
        urlBar = findViewById(R.id.urlBar)
        progress = findViewById(R.id.progress)
        securityIcon = findViewById(R.id.securityIcon)
        findViewById<Button>(R.id.btnBack).setOnClickListener { tabs.currentWebView?.goBack() }
        findViewById<Button>(R.id.btnForward).setOnClickListener { tabs.currentWebView?.goForward() }
        findViewById<Button>(R.id.btnReload).setOnClickListener {
            val t = tabs.current
            if (t != null && !StartPage.isStartPage(t.url)) t.webView.reload()
        }
        // v1.6.1 Chrome-like omnibox: tapping the bar selects the whole text so
        // typing replaces it in one go; leaving the bar restores the live page
        // URL (page-load updates are suppressed while the field has focus).
        urlBar?.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                (v as EditText).selectAll()
            } else if (!suppressUrlRestore) {
                urlBar?.setText(tabs.current?.url ?: "")
            }
        }
        urlBar?.setOnEditorActionListener { v, _, _ ->
            val raw = v.text.toString().trim()
            if (raw.isNotEmpty()) loadUserUrl(raw)
            true
        }
        securityIcon?.setOnClickListener { showSecurityInfo() }
        findViewById<Button>(R.id.btnMenu).setOnClickListener { showMenu(it) }
        findViewById<Button>(R.id.btnTabs).setOnClickListener { showTabDialog() }

        // ---- find bar (v2.0.0) ----
        findBar = findViewById(R.id.findBar)
        findInput = findViewById(R.id.findInput)
        findCount = findViewById(R.id.findCount)
        findViewById<Button>(R.id.btnFindNext).setOnClickListener { tabs.currentWebView?.findNext(true) }
        findViewById<Button>(R.id.btnFindPrev).setOnClickListener { tabs.currentWebView?.findNext(false) }
        findViewById<Button>(R.id.btnFindClose).setOnClickListener { hideFindBar() }
        findInput?.setOnEditorActionListener { _, _, _ ->
            tabs.currentWebView?.findNext(true)
            true
        }
        findInput?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                findPending?.let { findBar?.removeCallbacks(it) }
                val r = Runnable {
                    val t = tabs.current ?: return@Runnable
                    val q = s?.toString()?.trim() ?: ""
                    if (q.isEmpty()) t.webView.clearMatches() else t.webView.findAllAsync(q)
                }
                findPending = r
                findBar?.postDelayed(r, 250)
            }
        })

        // ---- pull-to-refresh (v2.0.0) ----
        swipe = findViewById(R.id.swipe)
        swipe?.setOnRefreshListener {
            val t = tabs.current
            if (t != null && !StartPage.isStartPage(t.url)) t.webView.reload() else swipe?.isRefreshing = false
        }

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

        // ---- tab switcher grid (v2.0.0) ----
        val grid = findViewById<RecyclerView>(R.id.tabsGrid)
        grid.layoutManager = GridLayoutManager(this, 2)
        tabsAdapter = TabGridAdapter()
        grid.adapter = tabsAdapter
        findViewById<Button>(R.id.btnNewTab).setOnClickListener { hideTabSwitcher(); newHomeTab(incognito = false) }
        findViewById<Button>(R.id.btnNewIncognito).setOnClickListener { hideTabSwitcher(); newHomeTab(incognito = true) }
        findViewById<Button>(R.id.btnSwitcherClose).setOnClickListener { hideTabSwitcher() }
        findViewById<Button>(R.id.btnCloseAllTabs).setOnClickListener {
            hideTabSwitcher()
            tabs.closeAll()
            loadInTab(tabs.current!!, StartPage.HOME_URL)
            syncOmniboxToCurrentTab()
        }

        // ---- session / intent / start page (v2.0.0) ----
        restoreSession()
        intent?.dataString?.let { openInNewTab(it) }

        // v1.8.0: notification tap → open the agent panel (background monitor)
        if (intent?.getBooleanExtra(com.cometx.browser.background.AgentNotifications.EXTRA_OPEN_PANEL, false) == true) {
            panel.expand()
        }

        maybeAutoUpdateLists()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.dataString?.let { openInNewTab(it) }
        // v1.8.0: notification tap while the app is alive
        if (intent.getBooleanExtra(com.cometx.browser.background.AgentNotifications.EXTRA_OPEN_PANEL, false)) {
            panel.expand()
        }
    }

    /**
     * v1.8.0: background tasks report through a foreground-service
     * notification — on Android 13+ it is only VISIBLE with POST_NOTIFICATIONS.
     * Ask once, opportunistically; the task itself runs either way.
     */
    private fun maybeAskNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            runCatching {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 8001)
            }
        }
    }

    // ------------------------------------------------------- loading

    /** v2.0.0: central loader — start page sentinel, DNT/GPC headers, upgrades. */
    fun loadInTab(tab: TabManager.Tab, url: String) {
        if (url == StartPage.HOME_URL || url == StartPage.HOME_SCHEME_ALIAS) {
            tab.url = StartPage.HOME_URL
            tab.title = getString(R.string.start_page)
            tab.webView.loadDataWithBaseURL(null, startPageHtml(tab), "text/html", "utf-8", null)
            updateChrome()
            return
        }
        val headers = browser.buildHeaders()
        if (headers.isEmpty()) tab.webView.loadUrl(url) else tab.webView.loadUrl(url, headers)
    }

    /** Renders the Comet-X start page (most-visited tiles unless incognito). */
    private fun startPageHtml(tab: TabManager.Tab?): String {
        val custom = StartPageLogic.run {
            val raw = settings.homeTiles()
            if (raw.isBlank()) emptyList() else parseHomeTiles(raw)
        }
        val topSites: List<Pair<String, String>> =
            if (tab?.incognito == true) emptyList()
            else runCatching {
                history.topSites(StartPageLogic.TILE_COUNT * 3).map { it.url to it.title }
            }.getOrDefault(emptyList())
        val tiles = StartPageLogic.resolveTiles(custom, topSites)
        val template = SearchEngines.templateFor(settings.customEngines(), settings.searchEngine())
        return StartPage.html(this, tiles, template, settings.totalBlocked())
    }

    private fun parseHomeTiles(raw: String): List<StartPageLogic.Tile> =
        SearchEngines.parseCustomEngines(raw).map { StartPageLogic.Tile(it.name, it.query, "", "") }

    fun loadUserUrl(raw: String) {
        val template = SearchEngines.templateFor(settings.customEngines(), settings.searchEngine())
        val url = UserInput.resolve(raw, template)
        if (url.isEmpty()) return
        val tab = tabs.current ?: return
        suppressUrlRestore = true
        // v1.6.1 parity: the committed URL (with scheme) fills the omnibox
        urlBar?.setText(url)
        // Chrome behavior: commit releases the omnibox — keyboard closes and
        // live page-URL updates resume (they were suppressed while focused, so
        // a stuck load previously looked like "nothing happens").
        urlBar?.let { bar ->
            (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(bar.windowToken, 0)
            bar.clearFocus()
        }
        suppressUrlRestore = false
        loadInTab(tab, url)
    }

    fun openInNewTab(url: String) {
        runOnUiThread {
            // Scheme gate (red-team F8): only http/https may open as tabs
            val lower = url.trim().lowercase()
            if (lower.startsWith("http://") || lower.startsWith("https://")) {
                tabs.newTab(url.trim())
                tabs.current?.let { loadInTab(it, url.trim()) }
                syncOmniboxToCurrentTab()
            } else if (lower == "about:home" || lower == "cometx://home") {
                tabs.current?.let { loadInTab(it, StartPage.HOME_URL) }
            } else {
                Toast.makeText(this, "Blocked non-web URL", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun newHomeTab(incognito: Boolean) {
        val t = tabs.newTab(TabManager.StartPageUrl, incognito)
        loadInTab(t, StartPage.HOME_URL)
        syncOmniboxToCurrentTab()
        if (incognito) {
            Toast.makeText(this, getString(R.string.incognito_started), Toast.LENGTH_SHORT).show()
        }
    }

    /** v1.6.1: omnibox must mirror the CURRENT tab after sheet-driven tab ops. */
    private fun syncOmniboxToCurrentTab() {
        urlBar?.let { bar ->
            suppressUrlRestore = true
            if (bar.hasFocus()) bar.clearFocus()
            bar.setText(tabs.current?.url ?: "")
            suppressUrlRestore = false
        }
        updateChrome()
    }

    fun onPageMeta(view: WebView, title: String?, url: String?) {
        runOnUiThread {
            tabs.updateMeta(view, title, url)
            // Phase 3: navigation events feed the skill recorder while recording
            if (::recorder.isInitialized) recorder.onNavigation(url ?: "")
            if (view == tabs.currentWebView) {
                if (urlBar?.hasFocus() != true) urlBar?.setText(url ?: "")
                updateChrome()
            }
        }
    }

    fun onProgress(p: Int) {
        runOnUiThread {
            progress?.progress = p
            progress?.visibility = if (p in 1..99) View.VISIBLE else View.GONE
        }
    }

    private fun syncTabUi() {
        updateChrome()
        if (tabSwitcherVisible()) tabsAdapter?.notifyDataSetChanged()
    }

    // ------------------------------------------------------- chrome

    private fun updateChrome() {
        val t = tabs.current ?: return
        val startPage = StartPage.isStartPage(t.url)
        swipe?.isEnabled = !startPage && settings.pullToRefresh()
        securityIcon?.setImageResource(
            when {
                startPage -> R.drawable.ic_home
                t.url.startsWith("https://") -> R.drawable.ic_lock
                else -> R.drawable.ic_globe
            }
        )
        securityIcon?.contentDescription =
            if (startPage) getString(R.string.start_page) else getString(R.string.cd_security)
        findViewById<Button>(R.id.btnBack).isEnabled = t.webView.canGoBack()
        findViewById<Button>(R.id.btnForward).isEnabled = t.webView.canGoForward()
        findViewById<Button>(R.id.btnBack).alpha = if (t.webView.canGoBack()) 1f else 0.4f
        findViewById<Button>(R.id.btnForward).alpha = if (t.webView.canGoForward()) 1f else 0.4f
    }

    // ------------------------------------------------------- find in page

    private fun showFindBar() {
        if (tabs.current == null) return
        findBar?.visibility = View.VISIBLE
        findInput?.setText("")
        findCount?.text = "0/0"
        findInput?.requestFocus()
        findInput?.post {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(findInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideFindBar() {
        if (findBar?.visibility != View.VISIBLE) return
        findBar?.visibility = View.GONE
        tabs.currentWebView?.clearMatches()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.let { imm ->
            findInput?.windowToken?.let { imm.hideSoftInputFromWindow(it, 0) }
        }
    }

    private fun onFindResults(activeMatchOrdinal: Int, numberOfMatches: Int, isDoneCounting: Boolean) {
        if (findBar?.visibility != View.VISIBLE) return
        if (isDoneCounting) {
            val shown = if (numberOfMatches == 0) 0 else activeMatchOrdinal + 1
            findCount?.text = "$shown/$numberOfMatches"
        }
    }

    // ------------------------------------------------------- tab switcher

    private fun tabSwitcherVisible(): Boolean =
        findViewById<View>(R.id.tabSwitcher)?.visibility == View.VISIBLE

    /** Kept name (lock doc F-02 references it); now opens the Material grid. */
    private fun showTabDialog() {
        tabs.current?.let { tabs.capturePreview(it) }
        tabsAdapter?.notifyDataSetChanged()
        findViewById<View>(R.id.tabSwitcher).visibility = View.VISIBLE
        hideKeyboard()
        urlBar?.clearFocus()
    }

    private fun hideTabSwitcher() {
        findViewById<View>(R.id.tabSwitcher)?.visibility = View.GONE
    }

    private inner class TabGridAdapter : RecyclerView.Adapter<TabGridAdapter.VH>() {
        val items: List<TabManager.Tab> get() = tabs.tabs

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val card: MaterialCardView = v.findViewById(R.id.tabCard)
            val preview: ImageView = v.findViewById(R.id.tabPreview)
            val title: TextView = v.findViewById(R.id.tabTitle)
            val url: TextView = v.findViewById(R.id.tabUrl)
            val incognito: TextView = v.findViewById(R.id.tabIncognito)
            val close: View = v.findViewById(R.id.btnCloseTab)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = layoutInflater.inflate(R.layout.item_tab_grid, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val t = items.getOrNull(position) ?: return
            h.title.text = t.title.ifBlank {
                if (StartPage.isStartPage(t.url)) getString(R.string.start_page) else t.url
            }
            val host = AdBlocker.hostOf(t.url)
            h.url.text = when {
                StartPage.isStartPage(t.url) -> if (t.incognito) "Incognito" else getString(R.string.start_page)
                !host.isNullOrBlank() -> host
                else -> t.url
            }
            h.incognito.visibility = if (t.incognito) View.VISIBLE else View.GONE
            if (t.preview != null && !t.preview!!.isRecycled) {
                h.preview.setImageBitmap(t.preview)
            } else {
                h.preview.setImageDrawable(null)
                h.preview.setImageResource(R.drawable.bg_avatar)
            }
            val current = position == tabs.currentIndex
            h.card.strokeWidth = if (current) dp(3) else 1
            h.card.strokeColor = getColor(if (current) R.color.accent else R.color.outline_variant)
            // v1.6.1 lesson: clicks live on the ROW, never on ListView machinery
            h.card.setOnClickListener {
                hideFindBar()
                hideTabSwitcher()
                tabs.switchTo(items.indexOf(t).coerceAtLeast(0))
                syncOmniboxToCurrentTab()
            }
            h.close.setOnClickListener {
                val idx = items.indexOf(t)
                tabs.close(idx)
                syncOmniboxToCurrentTab()
            }
        }

        override fun getItemCount(): Int = items.size
    }

    /** BrowserController page-finish hook → grid previews stay fresh. */
    fun captureTabPreview(view: WebView, tab: TabManager.Tab) {
        if (view != tabs.currentWebView) return
        tabs.capturePreview(tab)
    }

    /** BrowserController renderer-crash hook: degrade the tab, never the app. */
    fun onRendererGone(index: Int) {
        runOnUiThread {
            if (index >= 0) {
                runCatching { tabs.close(index) }
                Toast.makeText(this, getString(R.string.tab_crashed), Toast.LENGTH_SHORT).show()
            }
            syncOmniboxToCurrentTab()
        }
    }

    fun indexOfTab(tab: TabManager.Tab): Int = tabs.tabs.indexOf(tab)

    // ------------------------------------------------------- menu

    private fun showMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        val t = tabs.current
        menu.menu.add(0, MENU_NEW_TAB, 0, R.string.menu_new_tab)
        menu.menu.add(0, MENU_NEW_INCOGNITO, 1, R.string.menu_new_incognito)
        menu.menu.add(0, MENU_BOOKMARK_ADD, 2, if (isCurrentBookmarked()) R.string.menu_remove_bookmark else R.string.menu_add_bookmark)
        menu.menu.add(0, MENU_BOOKMARKS, 3, R.string.menu_bookmarks)
        menu.menu.add(0, MENU_HISTORY, 4, R.string.menu_history)
        menu.menu.add(0, MENU_DOWNLOADS, 5, R.string.menu_downloads)
        menu.menu.add(0, MENU_FIND, 6, R.string.menu_find)
        menu.menu.add(0, MENU_DESKTOP, 7, R.string.menu_desktop).apply {
            isCheckable = true
            isChecked = t?.desktopMode == true
        }
        menu.menu.add(0, MENU_READER, 8, R.string.menu_reader).apply {
            isEnabled = t != null && !StartPage.isStartPage(t.url)
        }
        menu.menu.add(0, MENU_TRANSLATE, 9, if (TranslateSupport.isTranslated(t?.url)) R.string.menu_view_original else R.string.menu_translate).apply {
            isEnabled = t != null && !StartPage.isStartPage(t.url)
        }
        menu.menu.add(0, MENU_PRINT, 10, R.string.menu_print).apply {
            isEnabled = t != null && !StartPage.isStartPage(t.url)
        }
        menu.menu.add(0, MENU_PIN, 11, R.string.menu_pin).apply {
            isEnabled = t != null && !StartPage.isStartPage(t.url)
        }
        menu.menu.add(0, MENU_SHARE, 12, R.string.menu_share)
        menu.menu.add(0, MENU_BLOCK_INFO, 13, R.string.menu_block_info)
        menu.menu.add(0, MENU_CLEAR_DATA, 14, R.string.menu_clear_data)
        menu.menu.add(0, MENU_AGENT_SELFTEST, 15, R.string.menu_agent_selftest)
        menu.menu.add(0, MENU_SETTINGS, 16, R.string.menu_settings)
        menu.setOnMenuItemClickListener { item ->
            handleMenu(item.itemId)
            true
        }
        menu.show()
    }

    private fun handleMenu(id: Int) {
        val t = tabs.current
        when (id) {
            MENU_NEW_TAB -> { hideTabSwitcher(); newHomeTab(incognito = false) }
            MENU_NEW_INCOGNITO -> { hideTabSwitcher(); newHomeTab(incognito = true) }
            MENU_BOOKMARK_ADD -> toggleBookmark()
            MENU_BOOKMARKS -> startActivityForResult(Intent(this, BookmarksActivity::class.java), 101)
            MENU_HISTORY -> startActivityForResult(Intent(this, HistoryActivity::class.java), 102)
            MENU_DOWNLOADS -> startActivity(Intent(this, DownloadsActivity::class.java))
            MENU_FIND -> showFindBar()
            MENU_DESKTOP -> toggleDesktop()
            MENU_READER -> toggleReader()
            MENU_TRANSLATE -> translatePage()
            MENU_PRINT -> printPage()
            MENU_PIN -> addToHomeScreen()
            MENU_SHARE -> {
                if (t != null && !StartPage.isStartPage(t.url)) {
                    val si = Intent(Intent.ACTION_SEND)
                    si.type = "text/plain"
                    si.putExtra(Intent.EXTRA_TEXT, t.url)
                    startActivity(Intent.createChooser(si, getString(R.string.menu_share)))
                }
            }
            MENU_BLOCK_INFO -> showBlockInfo()
            MENU_CLEAR_DATA -> confirmClearData()
            MENU_AGENT_SELFTEST -> startSelfTest()
            MENU_SETTINGS -> startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun isCurrentBookmarked(): Boolean {
        val t = tabs.current ?: return false
        if (StartPage.isStartPage(t.url)) return false
        return runCatching { bookmarks.contains(t.url) }.getOrDefault(false)
    }

    private fun toggleBookmark() {
        val t = tabs.current ?: return
        if (StartPage.isStartPage(t.url) || t.incognito) return
        runCatching {
            if (bookmarks.contains(t.url)) {
                for (e in bookmarks.all()) {
                    if (e.url == t.url) {
                        bookmarks.remove(e.id)
                        break
                    }
                }
                Toast.makeText(this, R.string.bookmark_removed, Toast.LENGTH_SHORT).show()
            } else {
                bookmarks.add(t.url, t.title.ifBlank { t.url })
                Toast.makeText(this, R.string.bookmark_added, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmClearData() {
        MaterialAlertDialogBuilder(this)
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

    // ------------------------------------------------------- security / blocking

    private fun showSecurityInfo() {
        val t = tabs.current
        if (t == null || StartPage.isStartPage(t.url)) {
            Toast.makeText(this, R.string.start_page, Toast.LENGTH_SHORT).show()
            return
        }
        val secure = t.url.startsWith("https://")
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.security_title)
            .setMessage(
                getString(
                    R.string.security_body,
                    getString(if (secure) R.string.security_secure else R.string.security_insecure),
                    t.blockedOnPage
                )
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showBlockInfo() {
        val t = tabs.current
        val b = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.block_info_title)
            .setMessage(
                getString(
                    R.string.block_info_body,
                    t?.blockedOnPage ?: 0,
                    adblock.sessionBlockedCount(),
                    settings.totalBlocked()
                )
            )
            .setPositiveButton(android.R.string.ok, null)
        val host = t?.let { AdBlocker.hostOf(it.url) }
        if (host != null && host.isNotEmpty() && !StartPage.isStartPage(t.url)) {
            b.setNeutralButton(R.string.allow_site) { _, _ ->
                val cur = settings.allowlist()
                if (cur.lowercase().contains(host)) {
                    Toast.makeText(this, R.string.site_already_allowed, Toast.LENGTH_SHORT).show()
                } else {
                    settings.setAllowlist(if (cur.isBlank()) host else "$cur\n$host")
                    adblock.rebuildAllowlist(settings.allowlist())
                    Toast.makeText(this, R.string.site_allowed, Toast.LENGTH_SHORT).show()
                }
            }
        }
        b.show()
    }

    /** Weekly automatic filter-list refresh (can be disabled in Settings).
     *  Never runs under Robolectric — unit tests must not touch the network. */
    private fun maybeAutoUpdateLists() {
        if (android.os.Build.FINGERPRINT?.contains("robolectric", ignoreCase = true) == true) return
        if (!FilterUpdater.dueForAutoUpdate(settings.autoUpdateLists(), settings.listLastUpdate())) return
        FilterUpdater.updateAll(this) { updated ->
            if (updated > 0) {
                settings.setListLastUpdate(System.currentTimeMillis())
                reloadBlocklists()
                Toast.makeText(this, R.string.lists_updated, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun reloadBlocklists() {
        adblock.loadFrom(filesDir) {
            assets.open(AdBlocker.ASSET_FILE).bufferedReader().use { it.readLines() }
        }
        adblock.rebuildAllowlist(settings.allowlist())
        CosmeticFilter.invalidate()
    }

    // ------------------------------------------------------- page tools

    /**
     * Desktop UA derived from the device's own WebView engine so the Chromium
     * major version always matches what the device actually runs (stale
     * hardcoded versions trigger Google "unsupported browser" walls).
     */
    private fun desktopUA(): String {
        cachedDesktopUA?.let { return it }
        var major = "130"
        runCatching {
            val def = android.webkit.WebSettings.getDefaultUserAgent(this)
            Regex("Chrome/(\\d+)").find(def)?.groupValues?.get(1)?.let { major = it }
        }
        cachedDesktopUA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/$major.0.0.0 Safari/537.36"
        return cachedDesktopUA!!
    }

    private fun toggleDesktop() {
        val t = tabs.current ?: return
        if (StartPage.isStartPage(t.url)) return
        t.desktopMode = !t.desktopMode
        val s = t.webView.settings
        if (t.desktopMode) {
            if (t.mobileUA.isNullOrBlank()) t.mobileUA = s.userAgentString
            s.userAgentString = desktopUA()
        } else {
            s.userAgentString = if (!t.mobileUA.isNullOrBlank()) t.mobileUA!!
            else android.webkit.WebSettings.getDefaultUserAgent(this)
        }
        t.webView.reload()
    }

    /** Reader view toggle (bundled Mozilla Readability; restores DOM on off). */
    private fun toggleReader() {
        val t = tabs.current ?: return
        if (StartPage.isStartPage(t.url)) return
        if (t.readerActive) {
            t.readerActive = false
            t.webView.evaluateJavascript(ReaderSupport.offScript(), null)
            return
        }
        t.webView.evaluateJavascript(ReaderSupport.onScript(this)) { value ->
            try {
                val o = org.json.JSONObject(value)
                when (o.optInt("ok", 0)) {
                    1 -> {
                        t.readerActive = true
                        Toast.makeText(
                            this,
                            getString(R.string.reader_minutes, o.optInt("minutes", 1)),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    3 -> t.readerActive = true
                    else -> Toast.makeText(this, R.string.reader_unavailable, Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {
                Toast.makeText(this, R.string.reader_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Translate the current page through Google's translate.goog proxy. */
    private fun translatePage() {
        val t = tabs.current ?: return
        if (StartPage.isStartPage(t.url)) return
        if (TranslateSupport.isTranslated(t.url)) {
            val back = t.translateSourceUrl
                ?: TranslateSupport.originalFromTranslateUrl(t.url)
            if (back != null) loadInTab(t, back)
            return
        }
        val out = TranslateSupport.translateUrl(
            t.url,
            java.util.Locale.getDefault().language.ifBlank { "en" }
        ) ?: return
        t.translateSourceUrl = t.url
        loadInTab(t, out)
        Toast.makeText(this, R.string.translating, Toast.LENGTH_SHORT).show()
    }

    /** System print dialog; its destination picker offers Save as PDF. */
    private fun printPage() {
        val t = tabs.current ?: return
        if (StartPage.isStartPage(t.url)) return
        runCatching {
            val pmgr = getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
            val title = t.title.ifBlank { AdBlocker.hostOf(t.url) ?: "page" }
            val jobName = "${getString(R.string.app_name)} — $title"
            pmgr.print(jobName, t.webView.createPrintDocumentAdapter(jobName), PrintAttributes.Builder().build())
        }.onFailure {
            Toast.makeText(this, R.string.print_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /** Pins a shortcut to the launcher with the site icon (API 26+ guaranteed). */
    private fun addToHomeScreen() {
        val t = tabs.current ?: return
        if (StartPage.isStartPage(t.url)) return
        runCatching {
            val sm = getSystemService(android.content.pm.ShortcutManager::class.java)
            if (sm == null || !sm.isRequestPinShortcutSupported) {
                Toast.makeText(this, R.string.pin_unsupported, Toast.LENGTH_SHORT).show()
                return
            }
            val si = Intent(Intent.ACTION_VIEW, Uri.parse(t.url))
            si.setPackage(packageName)
            si.setClass(this, MainActivity::class.java)
            si.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            var label = t.title.ifBlank { AdBlocker.hostOf(t.url) ?: getString(R.string.app_name) }
            if (label.isBlank()) label = getString(R.string.app_name)
            val icon = if (t.favicon != null && !t.favicon!!.isRecycled)
                android.graphics.drawable.Icon.createWithBitmap(t.favicon)
            else android.graphics.drawable.Icon.createWithResource(this, R.mipmap.ic_launcher)
            val info = android.content.pm.ShortcutInfo.Builder(this, "home_${t.url.hashCode()}")
                .setShortLabel(if (label.length > 24) label.take(24) else label)
                .setLongLabel(label)
                .setIntent(si)
                .setIcon(icon)
                .build()
            sm.requestPinShortcut(info, null)
            Toast.makeText(this, R.string.pin_requested, Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, R.string.pin_unsupported, Toast.LENGTH_SHORT).show()
        }
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

    // ------------------------------------------------------- permissions & files

    private fun handleWebPermission(request: PermissionRequest) {
        val wantsCamera = request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
        val wantsMic = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
        val camOk = wantsCamera && checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val micOk = wantsMic && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if ((!wantsCamera || camOk) && (!wantsMic || micOk)) {
            request.grant(request.resources)
            return
        }
        pendingWebPermission = request
        val needed = ArrayList<String>()
        if (wantsCamera && !camOk) needed.add(android.Manifest.permission.CAMERA)
        if (wantsMic && !micOk) needed.add(android.Manifest.permission.RECORD_AUDIO)
        runCatching { requestPermissions(needed.toTypedArray(), REQ_PERMISSION) }
            .onFailure {
                request.deny()
                pendingWebPermission = null
            }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSION && pendingWebPermission != null) {
            val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) pendingWebPermission?.grant(pendingWebPermission?.resources) else pendingWebPermission?.deny()
            pendingWebPermission = null
        }
    }

    private fun launchFileChooser(
        params: android.webkit.WebChromeClient.FileChooserParams,
        callback: ValueCallback<Array<Uri>>
    ) {
        if (fileCallback != null) fileCallback?.onReceiveValue(null)
        fileCallback = callback
        runCatching {
            startActivityForResult(params.createIntent(), REQ_FILE_CHOOSER)
        }.onFailure {
            fileCallback = null
            Toast.makeText(this, "File upload is not supported here", Toast.LENGTH_SHORT).show()
            callback.onReceiveValue(null)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_FILE_CHOOSER -> {
                val cb = fileCallback
                fileCallback = null
                cb?.onReceiveValue(android.webkit.WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            }
            101 -> { // BookmarksActivity → open the picked page in a new tab
                val url = data?.getStringExtra("url")
                if (!url.isNullOrBlank()) openInNewTab(url)
            }
            102 -> { // HistoryActivity
                val url = data?.getStringExtra("url")
                if (!url.isNullOrBlank()) openInNewTab(url)
            }
        }
    }

    // ------------------------------------------------------- fullscreen video

    private fun onFullscreenChanged(active: Boolean) {
        runOnUiThread {
            val chrome = findViewById<LinearLayout>(R.id.topBar)
            val ask = findViewById<LinearLayout>(R.id.askBar)
            chrome.visibility = if (active) View.GONE else View.VISIBLE
            ask.visibility = if (active) View.GONE else View.VISIBLE
            progress?.visibility = View.GONE
            if (active && panel.isVisible()) panel.collapse()
        }
    }

    // ------------------------------------------------------- session

    private fun restoreSession() {
        val saved = settings.savedTabs()
        val index = settings.savedTabIndex()
        var opened = 0
        if (saved.isNotBlank()) {
            for (u in saved.split("||")) {
                val url = u.trim()
                if (url.isEmpty()) continue
                if (opened >= MAX_RESTORED_TABS) break
                val t = tabs.newTab(url)
                loadInTab(t, url)
                opened++
            }
        }
        if (opened == 0) {
            val cold = if (settings.hasCustomHomepage()) settings.homepage() else TabManager.StartPageUrl
            val t = tabs.newTab(cold)
            loadInTab(t, cold)
        } else {
            tabs.switchTo(index.coerceIn(0, opened - 1))
            tabs.current?.let { if (StartPage.isStartPage(it.url)) loadInTab(it, StartPage.HOME_URL) }
        }
        syncOmniboxToCurrentTab()
    }

    private fun saveSession() {
        val sb = StringBuilder()
        var current = 0
        var i = 0
        for (t in tabs.tabs) {
            if (t.incognito) continue
            var u = t.url
            if (u.startsWith("data:") || u == "about:blank") u = StartPage.HOME_URL
            if (sb.isNotEmpty()) sb.append("||")
            if (i == tabs.currentIndex) current = i
            sb.append(u)
            i++
            if (i >= MAX_RESTORED_TABS) break
        }
        settings.setSavedTabs(sb.toString())
        settings.setSavedTabIndex(current)
    }

    // ------------------------------------------------------- lifecycle

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Chrome parity (v1.6.1): back while the omnibox is focused just leaves
        // the editor (URL restored, keyboard closed) instead of leaving the app.
        val bar = urlBar
        if (bar != null && bar.hasFocus()) {
            bar.clearFocus()
            hideKeyboard(bar)
            return
        }
        // v2.0.0: layered surfaces leave in order
        if (browser.isInFullscreen()) { browser.exitFullscreen(); return }
        if (findBar?.visibility == View.VISIBLE) { hideFindBar(); return }
        if (tabSwitcherVisible()) { hideTabSwitcher(); return }
        if (panel.isVisible()) { panel.collapse(); return }
        val web = tabs.currentWebView
        if (web?.canGoBack() == true) web.goBack() else super.onBackPressed()
    }

    private fun hideKeyboard(bar: EditText) {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(bar.windowToken, 0)
    }

    private fun hideKeyboard() {
        urlBar?.let { hideKeyboard(it) }
    }

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
        tabs.currentWebView?.onPause()
        if (::settings.isInitialized) saveSession()
    }

    override fun onResume() {
        super.onResume()
        tabs.currentWebView?.onResume()
        // Expert review P1-7: re-apply saved base URLs so Settings edits reach
        // the live providers without a process restart.
        ProviderSet.applyBaseUrls(settings, providers)
        // v2.0.0: Settings may have changed while we were away — text zoom
        // applies live to every tab, and a filter-list download reloads the
        // network blocklist without a restart.
        browser.applyTextZoomToAll(tabs.tabs, settings.textZoom())
        if (::adblock.isInitialized && adblock.loadedAt > 0 && settings.listLastUpdate() > adblock.loadedAt) {
            reloadBlocklists()
        }
        updateChrome()
    }

    override fun onDestroy() {
        if (engine.state == AgentEngine.State.RUNNING || engine.state == AgentEngine.State.AWAITING_USER || engine.state == AgentEngine.State.AWAITING_CONFIRM) {
            engine.stop()
        }
        if (::recorder.isInitialized && recorder.state == SkillRecorder.State.RECORDING) recorder.cancel()
        if (::panel.isInitialized) panel.dispose()
        scope.cancel()
        if (::testServer.isInitialized) testServer.stop()
        tabs.destroyAll()
        super.onDestroy()
    }

    companion object {
        private const val MAX_RESTORED_TABS = 10
        private const val REQ_FILE_CHOOSER = 41
        private const val REQ_PERMISSION = 42

        // Menu command ids (v2.0.0 — F-03 extension)
        private const val MENU_NEW_TAB = 1
        private const val MENU_NEW_INCOGNITO = 2
        private const val MENU_BOOKMARK_ADD = 3
        private const val MENU_BOOKMARKS = 4
        private const val MENU_HISTORY = 5
        private const val MENU_DOWNLOADS = 6
        private const val MENU_FIND = 7
        private const val MENU_DESKTOP = 8
        private const val MENU_READER = 9
        private const val MENU_TRANSLATE = 10
        private const val MENU_PRINT = 11
        private const val MENU_PIN = 12
        private const val MENU_SHARE = 13
        private const val MENU_BLOCK_INFO = 14
        private const val MENU_CLEAR_DATA = 15
        private const val MENU_AGENT_SELFTEST = 16
        private const val MENU_SETTINGS = 17
    }
}

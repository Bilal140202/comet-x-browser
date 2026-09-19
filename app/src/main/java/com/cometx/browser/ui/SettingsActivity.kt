package com.cometx.browser.ui

import android.os.Bundle
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.cometx.browser.ai.ConnectionDiagnostics
import com.cometx.browser.ai.CustomOpenAIProvider
import com.cometx.browser.ai.GroqProvider
import com.cometx.browser.ai.HuggingFaceProvider
import com.cometx.browser.ai.ModelCatalog
import com.cometx.browser.ai.ModelRanker
import com.cometx.browser.ai.ModelRouter
import com.cometx.browser.ai.OpenAICompatibleProvider
import com.cometx.browser.ai.OpenRouterProvider
import com.cometx.browser.ai.ProviderException
import com.cometx.browser.ai.SettingsRepository
import com.cometx.browser.ai.UrlNormalizer
import com.cometx.browser.CometApp
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.cometx.browser.browse.FilterUpdater
import com.cometx.browser.browse.SearchEngines
import com.cometx.browser.memory.MemoryStore
import com.cometx.browser.security.SecureStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * SettingsActivity — Phase 2 zero-config UX (§12/§22):
 *
 *   Choose provider → paste API key → [Test & Enable] → READY.
 *
 * The app discovers models, verifies capabilities and selects the best
 * agent-compatible model automatically ("AUTO"). Manual model entry survives
 * only inside an opt-in [Advanced] section for power users (§13) and is never
 * required. Includes the Agent Compatibility self-test (§26) and the AI event
 * log viewer (§36/§37).
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: SettingsRepository
    private lateinit var secure: SecureStore
    private lateinit var memory: MemoryStore
    private lateinit var catalog: ModelCatalog
    private lateinit var root: LinearLayout

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private companion object {
        /** SAF request code for GGUF import (onActivityResult). */
        const val REQ_IMPORT_GGUF = 4101
    }

    private val providerNames = mapOf(
        "groq" to "Groq",
        "openrouter" to "OpenRouter",
        "huggingface" to "Hugging Face",
        "custom" to "Self-run (OpenAI-compatible)"
    )
    private val providerTags = mapOf(
        "groq" to "fastest inference · free tier · key is enough",
        "openrouter" to "one key · free models used automatically",
        "huggingface" to "inference router · free quota · key is enough",
        "custom" to "Ollama · LM Studio · vLLM · any /v1 endpoint"
    )

    /** Per-provider pending (unsaved) key/url UI state. */
    private class ProviderUi {
        var keyText: String = ""
        var urlText: String = ""
        var savedKey: String = ""
        var savedUrl: String = ""
        var dirtyDot: TextView? = null
        var statusLabel: TextView? = null
        var autoLabel: TextView? = null
        var testButton: MaterialButton? = null
        var testInProgress: Boolean = false

        fun isDirty(): Boolean = keyText != savedKey || urlText != savedUrl
    }

    private val ui = mutableMapOf<String, ProviderUi>()
    private val advancedExpanded = mutableMapOf<String, Boolean>()

    private val providers: Map<String, OpenAICompatibleProvider> by lazy {
        mapOf(
            "groq" to GroqProvider(keyProvider = { settings.apiKey("groq") }),
            "openrouter" to OpenRouterProvider(keyProvider = { settings.apiKey("openrouter") }),
            "huggingface" to HuggingFaceProvider(keyProvider = { settings.apiKey("huggingface") }),
            "custom" to CustomOpenAIProvider(
                keyProvider = { settings.apiKey("custom") },
                readyCheck = { !settings.apiKey("custom").isNullOrBlank() || !settings.baseUrl("custom").isNullOrBlank() }
            )
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        secure = SecureStore(this)
        settings = SettingsRepository(this, secure)
        memory = MemoryStore(filesDir) { settings.memoryEnabled() }
        catalog = ModelCatalog(this)
        settings.runModeMigration()

        val scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        scroll.addView(root)
        setContentView(scroll)
        setTitle("Comet-X Settings")

        buildUi()
    }

    private fun buildUi() {
        root.removeAllViews()
        ui.clear()

        header("AI Provider")
        body("Paste an API key and press Test & Enable. Comet-X discovers the available models, " +
            "checks what each one supports and picks the best agent model automatically. " +
            "No model IDs, no JSON settings — a key is enough. " +
            "Keys are encrypted with the Android Keystore and never leave the device.")
        for (id in SettingsRepository.ALL_PROVIDERS) addProviderBlock(id)

        header("AI diagnostics")
        addDiagnosticsButtons()

        header("On-device AI (beta)")
        addLocalAi()

        header("Agent behavior")
        addNumberField("Max steps per task (4–60)", settings.maxSteps()) { settings.setMaxSteps(it) }
        body("The agent auto-extends this budget a little while a task is visibly progressing (up to 3 extensions, never above 60) and stops thrashing runs instead of funding them.")
        addCheck("Confirm high-risk actions (purchases, deletes, sends)", settings.confirmHighRisk()) { settings.setConfirmHighRisk(it) }
        addCheck("Agent memory enabled (can be cleared below)", settings.memoryEnabled()) { settings.setMemoryEnabled(it) }
        addCheck("Skill replay: let AI re-locate elements the recorder missed", settings.skillAiFallback()) { settings.setSkillAiFallback(it) }
        addVisionMode()
        addCheck("Set-of-Marks: number page elements on agent screenshots (helps the model aim)", settings.somOverlay()) { settings.setSomOverlay(it) }

        header("Browser")
        addTextField("Homepage", settings.homepage(), autoSave = true) { settings.setHomepage(it) }
        addCheck("Allow third-party cookies", settings.thirdPartyCookies()) { settings.setThirdPartyCookies(it) }
        addSearchEngine()
        addTextZoom()
        addCheck("Force-enable zoom on pages that block it", settings.forceZoom()) { settings.setForceZoom(it) }
        addCheck("Media autoplay (off = sites need a tap before playing)", settings.mediaAutoplay()) { settings.setMediaAutoplay(it) }
        addCheck("Pull-to-refresh gesture", settings.pullToRefresh()) { settings.setPullToRefresh(it) }

        header("Privacy & blocking")
        body("Comet-X drops ad and tracker requests before they reach the network (StevenBlack hosts list + curated URL rules), hides ad containers before first paint (cosmetic rules) and suppresses YouTube ads client-side. Blocked counts never leave the device.")
        addCheck("Block ads and trackers (network level)", settings.blockAds()) { settings.setBlockAds(it) }
        addCheck("Hide ad containers (cosmetic filtering)", settings.blockCosmetic()) { settings.setBlockCosmetic(it) }
        addCheck("YouTube ad suppression", settings.youtubeSuppress()) { settings.setYoutubeSuppress(it) }
        addCheck("Send Do-Not-Track and Global-Privacy-Control headers", settings.privacyHeaders()) { settings.setPrivacyHeaders(it) }
        addCheck("HTTPS-first: upgrade http:// pages automatically", settings.httpsUpgrade()) { settings.setHttpsUpgrade(it) }
        addCheck("Accept first-party cookies", settings.cookiesEnabled()) { settings.setCookiesEnabled(it) }
        addCheck("Darken web pages on dark theme (algorithmic)", settings.webForceDark()) { settings.setWebForceDark(it) }
        addAllowlistEditor()
        body("Blocked so far (all time): ${settings.totalBlocked()}")
        val resetBlockedBtn = actionButton("Reset blocked counter", Tonal.TONAL)
        root.addView(resetBlockedBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(4), 0, dp(8)) })
        resetBlockedBtn.setOnClickListener {
            settings.resetTotalBlocked()
            buildUi()
        }
        addFilterUpdater()

        header("Appearance")
        addThemePicker()
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            addCheck("Material You: tint the app with your wallpaper colors", settings.materialYou()) { settings.setMaterialYou(it) }
        }

        header("Memory")
        body(memorySummary())
        val viewBtn = actionButton("View memory", Tonal.TONAL)
        val clearBtn = actionButton("Clear memory", Tonal.TONAL)
        row(viewBtn, clearBtn)
        viewBtn.setOnClickListener { showMemory() }
        clearBtn.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Clear all memory?")
                .setMessage("User memory, recent tasks and browser state will be deleted.")
                .setPositiveButton("Clear") { _, _ -> memory.clearAll(); buildUi() }
                .setNegativeButton("Cancel", null).show()
        }
    }

    // ---------------------------------------------------------------- providers

    private fun addProviderBlock(id: String) {
        val p = providers[id] ?: return
        val s = ProviderUi()
        ui[id] = s
        s.savedKey = settings.apiKey(id) ?: ""
        s.savedUrl = settings.baseUrl(id) ?: ""
        s.keyText = s.savedKey
        s.urlText = s.savedUrl

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = getDrawable(com.cometx.browser.R.drawable.bg_card)
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, dp(6), 0, dp(6))
        root.addView(card, lp)

        // -- title + status
        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        titleRow.addView(TextView(this).apply {
            text = providerNames[id]
            textSize = 15f
            setTextColor(getColor(com.cometx.browser.R.color.text_primary))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        s.statusLabel = TextView(this).apply {
            textSize = 12f
            setTextColor(getColor(com.cometx.browser.R.color.text_secondary))
            gravity = android.view.Gravity.END
        }
        titleRow.addView(s.statusLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        card.addView(titleRow)

        card.addView(TextView(this).apply {
            text = providerTags[id]
            textSize = 12f
            setTextColor(getColor(com.cometx.browser.R.color.text_secondary))
        })

        // -- enabled (fallback chain membership)
        val enabled = MaterialSwitch(this).apply {
            text = "Enabled — included in fallback"
            textSize = 13f
            setTextColor(getColor(com.cometx.browser.R.color.text_primary))
            isChecked = settings.providerEnabled(id)
        }
        card.addView(enabled, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(4), 0, 0) })
        enabled.setOnCheckedChangeListener { _, checked ->
            settings.setProviderEnabled(id, checked)
        }

        // -- API key
        card.addView(TextView(this).apply {
            text = if (s.savedKey.isNotBlank()) "API key (saved — retype to replace)" else if (id == "custom") "API key (optional for local servers)" else "API key"
            textSize = 12f
            setTextColor(getColor(com.cometx.browser.R.color.text_secondary))
        })
        val keyInput = EditText(this).apply {
            hint = if (s.savedKey.isNotBlank()) "••••••••  (leave blank to keep)" else keyHint(id)
            textSize = 13f
            setTextColor(getColor(com.cometx.browser.R.color.text_primary))
            transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        card.addView(keyInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp(6)) })
        keyInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) { s.keyText = e?.toString() ?: ""; refreshDirty(s) }
            override fun beforeTextChanged(c: CharSequence?, a: Int, b: Int, d: Int) {}
            override fun onTextChanged(c: CharSequence?, a: Int, b: Int, d: Int) {}
        })

        // -- base URL (custom only)
        if (id == "custom") {
            card.addView(TextView(this).apply {
                text = "Server base URL (OpenAI-compatible, should end with /v1)"
                textSize = 12f
                setTextColor(getColor(com.cometx.browser.R.color.text_secondary))
            })
            val urlInput = EditText(this).apply {
                setText(s.savedUrl.ifBlank { "http://localhost:11434" })
                hint = "http://localhost:11434/v1"
                textSize = 13f
                setTextColor(getColor(com.cometx.browser.R.color.text_primary))
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            }
            card.addView(urlInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp(2)) })
            val urlHint = TextView(this).apply {
                textSize = 12f
                setTextColor(getColor(com.cometx.browser.R.color.text_secondary))
            }
            card.addView(urlHint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp(6)) })
            fun updateHint() {
                val raw = urlInput.text.toString()
                val norm = UrlNormalizer.normalize(raw)
                urlHint.text = if (norm != raw.trim()) "Will be saved as: $norm" else "Ollama: http://localhost:11434/v1 · LM Studio: http://localhost:1234/v1 · vLLM: http://host:8000/v1"
            }
            updateHint()
            urlInput.addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(e: android.text.Editable?) { s.urlText = e?.toString() ?: ""; updateHint(); refreshDirty(s) }
                override fun beforeTextChanged(c: CharSequence?, a: Int, b: Int, d: Int) {}
                override fun onTextChanged(c: CharSequence?, a: Int, b: Int, d: Int) {}
            })
        }

        // -- AUTO status line ("Agent model: AUTO — …")
        s.autoLabel = TextView(this).apply {
            textSize = 12f
            setTextColor(getColor(com.cometx.browser.R.color.text_primary))
            setPadding(0, dp(2), 0, dp(2))
        }
        card.addView(s.autoLabel)

        // -- Test & Enable (the ONLY button a normal user ever needs)
        val test = actionButton("Test & Enable", Tonal.FILL)
        s.testButton = test
        card.addView(test, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(6), 0, dp(2)) })

        // -- unsaved indicator
        s.dirtyDot = TextView(this).apply {
            text = "unsaved — Test & Enable will save it"
            textSize = 12f
            setTextColor(getColor(com.cometx.browser.R.color.accent_bright))
            visibility = android.view.View.GONE
        }
        card.addView(s.dirtyDot)

        // -- Advanced disclosure (optional overrides, §13)
        val advToggle = actionButton(if (advancedExpanded[id] == true) "Advanced ▴" else "Advanced ▾", Tonal.TEXT).apply { textSize = 12f }
        card.addView(advToggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(2), 0, 0) })
        val advBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (advancedExpanded[id] == true) android.view.View.VISIBLE else android.view.View.GONE
            setPadding(dp(4), 0, 0, 0)
        }
        card.addView(advBox)
        advToggle.setOnClickListener {
            advancedExpanded[id] = advancedExpanded[id] != true
            advToggle.text = if (advancedExpanded[id] == true) "Advanced ▴" else "Advanced ▾"
            advBox.visibility = if (advancedExpanded[id] == true) android.view.View.VISIBLE else android.view.View.GONE
        }
        buildAdvanced(advBox, id, p)

        refreshStatus(id, s)
        test.setOnClickListener { testAndEnable(id, s) }
        refreshDirty(s)
    }

    /** Advanced section: mode + optional per-role overrides (§13). Never required. */
    private fun buildAdvanced(box: LinearLayout, id: String, p: OpenAICompatibleProvider) {
        box.addView(TextView(this).apply {
            text = "Everything here is OPTIONAL — AUTO handles models, protocols and fallbacks for you."
            textSize = 12f
            setTextColor(getColor(com.cometx.browser.R.color.text_secondary))
        })
        box.addView(TextView(this).apply {
            text = "Model selection"
            textSize = 12f
            setTextColor(getColor(com.cometx.browser.R.color.text_primary))
            setPadding(0, dp(6), 0, dp(2))
        })
        val modeSpinner = Spinner(this)
        val modes = listOf("AUTO — recommended (app picks the best model)", "MANUAL — advanced overrides below")
        modeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, modes)
        modeSpinner.setSelection(if (settings.modelMode(id) == SettingsRepository.ModelMode.MANUAL) 1 else 0)
        box.addView(modeSpinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        modeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            private var first = true
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, pos: Int, rowId: Long) {
                if (first) { first = false; return }
                settings.setModelMode(id, if (pos == 0) SettingsRepository.ModelMode.AUTO else SettingsRepository.ModelMode.MANUAL)
                toast(if (pos == 0) "AUTO — Comet-X picks models automatically" else "MANUAL — advanced overrides active")
                refreshAutoLabel(id, ui[id] ?: return)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        box.addView(TextView(this).apply {
            text = "Per-role overrides (MANUAL mode only — AUTO ignores these):"
            textSize = 12f
            setTextColor(getColor(com.cometx.browser.R.color.text_secondary))
            setPadding(0, dp(6), 0, dp(2))
        })
        for (role in ModelRouter.Role.entries) addAdvancedModelSpinner(box, id, role)

        box.addView(TextView(this).apply {
            text = "Cache: " + (settings.lastDiagnostics(id)?.optString("providerName")?.let { "last test stored" } ?: "no test run yet")
            textSize = 12f
            setTextColor(getColor(com.cometx.browser.R.color.text_secondary))
            setPadding(0, dp(8), 0, 0)
        })
        val clearCache = actionButton("Forget discovered models (refresh cache)", Tonal.TEXT).apply { textSize = 12f; isAllCaps = false }
        box.addView(clearCache, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        clearCache.setOnClickListener {
            catalog.invalidate(id)
            toast("Model cache cleared — next Test & Enable re-discovers")
        }
    }

    /** One advanced per-role override dropdown (AUTO / fetched / custom). */
    private fun addAdvancedModelSpinner(parent: LinearLayout, id: String, role: ModelRouter.Role) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(TextView(this).apply {
            text = role.name.lowercase().replaceFirstChar { it.uppercase() }
            textSize = 12f
            setTextColor(getColor(com.cometx.browser.R.color.text_primary))
        }, LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = android.view.Gravity.CENTER_VERTICAL })

        val spinner = Spinner(this)
        row.addView(spinner, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        parent.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(2), 0, dp(2)) })

        val saved = settings.modelFor(id, role)
        val options = ArrayList<String>()
        options.add("AUTO")
        options.addAll(lastKnownModels(id))
        options.add("Custom… (type manually)")
        var selected = 0
        if (!saved.isNullOrBlank()) {
            val idx = lastKnownModels(id).indexOf(saved)
            selected = if (idx >= 0) idx + 1 else options.size - 1
        }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, options)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        var pos = selected.coerceAtMost(options.size - 1)
        spinner.setSelection(pos, false)
        var dialogOpen = false
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parentView: android.widget.AdapterView<*>?, view: android.view.View?, sel: Int, rowId: Long) {
                if (sel == pos) return
                pos = sel
                when {
                    sel == 0 -> settings.setModel(id, role, "")
                    sel == options.size - 1 -> {
                        if (dialogOpen) return
                        dialogOpen = true
                        val input = EditText(this@SettingsActivity).apply {
                            setText(saved ?: "")
                            hint = "exact model id served by this provider"
                        }
                        MaterialAlertDialogBuilder(this@SettingsActivity)
                            .setTitle("${providerNames[id]} · ${role.name.lowercase()}")
                            .setMessage("Type the exact model id. Blank falls back to AUTO.")
                            .setView(input)
                            .setPositiveButton("OK") { _, _ ->
                                settings.setModel(id, role, input.text.toString().trim())
                                dialogOpen = false
                            }
                            .setNegativeButton("Cancel") { _, _ -> dialogOpen = false }
                            .show()
                    }
                    else -> settings.setModel(id, role, options[sel])
                }
            }
            override fun onNothingSelected(parentView: android.widget.AdapterView<*>?) {}
        }
    }

    /** Model ids from the last cached catalog (advanced dropdown population). */
    private fun lastKnownModels(id: String): List<String> {
        val p = providers[id] ?: return emptyList()
        return try {
            catalog.cachedModels(p.id)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun keyHint(id: String) = when (id) {
        "groq" -> "gsk_…  (console.groq.com/keys)"
        "openrouter" -> "sk-or-…  (openrouter.ai/keys)"
        "huggingface" -> "hf_…  (huggingface.co/settings/tokens)"
        else -> "optional — local servers usually need none"
    }

    // ------------------------------------------------------------ test & enable

    private fun testAndEnable(id: String, s: ProviderUi) {
        if (s.testInProgress) return
        // The button SAVES first (§35: paste → connect, no separate save step)
        if (s.keyText.isNotBlank()) settings.setApiKey(id, s.keyText.trim())
        if (id == "custom") settings.setBaseUrl(id, UrlNormalizer.normalize(s.urlText))
        s.savedKey = settings.apiKey(id) ?: ""
        s.savedUrl = settings.baseUrl(id) ?: ""

        val p = providers[id] ?: return
        if (!p.isReady()) { toast("Paste an API key first" + if (id == "custom") " (or a base URL)" else ""); return }
        if (id == "custom") {
            val base = settings.baseUrl(id)?.let { UrlNormalizer.normalize(it) } ?: ""
            if (base.isBlank()) { toast("Enter the server base URL first"); return }
        }

        s.testInProgress = true
        s.testButton?.isEnabled = false
        s.testButton?.text = "Testing…"
        s.statusLabel?.text = "connecting…"
        val diagnostics = ConnectionDiagnostics(catalog)

        uiScope.launch {
            var report: ConnectionDiagnostics.Report? = null
            var error: String? = null
            try {
                report = diagnostics.run(p, deep = false) { step ->
                    runOnUiThread { s.statusLabel?.text = step }
                }
                settings.setLastDiagnostics(id, report.toJson())
                settings.setLastTest(id, report.ready, report.render().lineSequence().firstOrNull { it.startsWith("✓ Model") || it.startsWith("✗") || it.startsWith("⚠") } ?: "checked")
            } catch (e: Exception) {
                error = if (e is ProviderException) e.message ?: "provider error" else "${e.javaClass.simpleName}: ${e.message}"
                settings.setLastTest(id, false, error ?: "failed")
            }
            s.testInProgress = false
            MaterialAlertDialogBuilder(this@SettingsActivity)
                .setTitle(
                    when {
                        report?.ready == true -> "✓ Connected to ${providerNames[id]}"
                        report != null -> "⚠ ${providerNames[id]} — usable with fallbacks"
                        else -> "✗ Connection failed"
                    }
                )
                .setMessage(report?.render() ?: (error ?: "unknown error"))
                .setPositiveButton("OK", null)
                .show()
            buildUi()
        }
    }

    // -------------------------------------------------------------- diagnostics

    private fun addDiagnosticsButtons() {
        body("Run a full agent compatibility rehearsal, or inspect automatic fallback decisions. " +
            "Diagnostics never display API keys.")
        val compatBtn = actionButton("Run Agent Compatibility Test", Tonal.TONAL)
        root.addView(compatBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        compatBtn.setOnClickListener { runCompatibilityTest() }

        val logBtn = actionButton("View AI event log", Tonal.TONAL)
        val clearLogBtn = actionButton("Clear log", Tonal.TEXT)
        row(logBtn, clearLogBtn)
        logBtn.setOnClickListener { showAiLog() }
        clearLogBtn.setOnClickListener { settings.clearAiLog(); toast("AI event log cleared") }
    }

    private fun runCompatibilityTest() {
        val ready = providers.entries.filter { it.value.isReady() }
        if (ready.isEmpty()) { toast("Test & Enable a provider first"); return }
        val ids = ready.map { it.key }
        var chosen = ids.first()
        MaterialAlertDialogBuilder(this)
            .setTitle("Run on which provider?")
            .setSingleChoiceItems(ids.map { providerNames[it] }.toTypedArray(), 0) { _, which -> chosen = ids[which] }
            .setPositiveButton("Run") { _, _ -> doCompatibilityTest(chosen) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doCompatibilityTest(id: String) {
        val p = providers[id] ?: return
        val dialog = MaterialAlertDialogBuilder(this).setTitle("Agent Compatibility Test").setMessage("Running…").show()
        uiScope.launch {
            val text = try {
                ConnectionDiagnostics(catalog).compatibilitySelfTest(p)
            } catch (e: Exception) {
                "Compatibility test failed: ${e.message}"
            }
            runOnUiThread {
                MaterialAlertDialogBuilder(this@SettingsActivity)
                    .setTitle("COMET-X AI COMPATIBILITY")
                    .setMessage(text)
                    .setPositiveButton("OK", null)
                    .show()
                dialog.dismiss()
            }
        }
    }

    private fun showAiLog() {
        val log = settings.aiLog()
        val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
        val text = if (log.isEmpty()) "(empty)" else buildString {
            for ((ts, line) in log.reversed()) appendLine("${fmt.format(Date(ts))}  $line")
        }
        MaterialAlertDialogBuilder(this).setTitle("AI event log — automatic fallbacks & switches").setMessage(text)
            .setPositiveButton("Close", null)
            .show()
    }

    // ---------------------------------------------------------------- state ui

    private fun refreshDirty(s: ProviderUi) {
        s.dirtyDot?.visibility = if (s.isDirty()) android.view.View.VISIBLE else android.view.View.GONE
        s.testButton?.text = if (s.isDirty()) "Save & Test" else "Test & Enable"
    }

    private fun refreshStatus(id: String, s: ProviderUi) {
        val last = settings.lastTest(id)
        val text = when {
            last != null && last.startsWith("ok") -> "✓ ${last.split("|").getOrNull(2) ?: ""}"
            last != null && last.startsWith("fail") -> "✗ ${last.split("|").getOrNull(2) ?: ""}"
            s.savedKey.isNotBlank() || (id == "custom" && s.savedUrl.isNotBlank()) -> "saved · untested"
            else -> "not set up"
        }
        s.statusLabel?.text = text
        refreshAutoLabel(id, s)
    }

    private fun refreshAutoLabel(id: String, s: ProviderUi) {
        val diag = settings.lastDiagnostics(id)
        val best = diag?.optJSONObject("bestModel")
        s.autoLabel?.text = if (best != null && settings.modelMode(id) == SettingsRepository.ModelMode.AUTO) {
            val protocol = diag.optString("protocol", "")
            val name = best.optString("displayName", best.optString("id"))
            "Model: AUTO — currently ${name}${if (protocol.isNotBlank()) " · protocol: $protocol" else ""}"
        } else if (settings.modelMode(id) == SettingsRepository.ModelMode.MANUAL) {
            "Model: MANUAL (advanced overrides active)"
        } else {
            "Model: AUTO — run Test & Enable to discover models"
        }
    }

    // ---------------------------------------------------------------- on-device AI (v1.6.0)

    private val localStatusLabels = mutableMapOf<String, TextView>()
    private var localRefreshTick: Runnable? = null

    private fun addLocalAi() {
        val local = CometApp.app.localAI
        if (!local.nativeAvailable()) {
            body("This device cannot run on-device AI: the native llama.cpp runtime only ships for arm64 phones. Cloud providers keep working normally.")
            return
        }

        body("Run the agent fully on this phone with llama.cpp — no API key, works offline, nothing leaves the device. " +
            "Models are downloaded once from Hugging Face and verified (SHA-256) before activation. " +
            "On-device models are text-only: vision is served by cloud models when available. Requires a modern arm64 phone.")
        addCheck("Prefer on-device AI (local-first; cloud as backup)", settings.localAiPreferred()) {
            settings.setLocalAiPreferred(it)
            local.autoReloadIfPreferred()
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = getDrawable(com.cometx.browser.R.drawable.bg_card)
        }
        card.addView(TextView(this).apply {
            text = "This device: ${local.deviceSummary()}"
            textSize = 12f
            setTextColor(getColor(com.cometx.browser.R.color.text_secondary))
        })
        card.addView(TextView(this).apply {
            text = "Chain position: ${if (settings.localAiPreferred()) "on-device FIRST, cloud backup" else "cloud first, on-device as last-resort fallback"}"
            textSize = 12f
            setTextColor(getColor(com.cometx.browser.R.color.text_secondary))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(2), 0, 0) })
        root.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(6), 0, dp(6)) })

        for (m in local.allModels()) addLocalModelCard(local, m)

        // section-level controls
        val testBtn = actionButton("Test active model", Tonal.TONAL)
        val importBtn = actionButton("Import .gguf file", Tonal.TEXT)
        row(testBtn, importBtn)
        testBtn.setOnClickListener { runLocalModelTest(local) }
        importBtn.setOnClickListener { pickGgufFile() }

        addNumberField("Context size (1024–4096; smaller = less RAM)", settings.localContext()) { settings.setLocalContext(it) }
        addNumberField("Inference threads (0 = auto-detect fast cores)", settings.localThreads()) { settings.setLocalThreads(it) }
        addNumberField("Auto-unload after idle minutes (0 = never)", settings.localUnloadMin()) { settings.setLocalUnloadMin(it) }

        // live status refresh while this screen is visible (download progress etc.)
        localRefreshTick = object : Runnable {
            override fun run() {
                refreshLocalStatuses(local)
                localRefreshTick?.let { android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(it, 800) }
            }
        }
        localRefreshTick?.run()
    }

    private fun addLocalModelCard(local: com.cometx.browser.ai.local.LocalModelManager, m: com.cometx.browser.ai.local.LocalModelCatalog.CatalogModel) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = getDrawable(com.cometx.browser.R.drawable.bg_card)
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, dp(6), 0, dp(6))
        root.addView(card, lp)

        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        titleRow.addView(TextView(this).apply {
            text = "${m.repo.substringBefore('/').take(20)} · ${m.params} · ${m.quant} · ${m.sizeMb} MB"
            textSize = 15f
            setTextColor(getColor(com.cometx.browser.R.color.text_primary))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        localStatusLabels[m.id] = TextView(this).apply {
            textSize = 12f
            setTextColor(getColor(com.cometx.browser.R.color.text_secondary))
            gravity = android.view.Gravity.END
        }
        titleRow.addView(localStatusLabels[m.id], LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        card.addView(titleRow)

        card.addView(TextView(this).apply {
            text = "${m.fileName}\n${m.strengths}"
            textSize = 12f
            setTextColor(getColor(com.cometx.browser.R.color.text_secondary))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(2), 0, 0) })

        val dl = local.states.value[m.id]
        val downloaded = local.isDownloaded(m)
        val active = local.isModelActive(m)
        val loading = local.isLoading()

        val primary = actionButton(
            when {
                dl is com.cometx.browser.ai.local.LocalModelManager.DownloadState.Downloading -> "Pause"
                dl is com.cometx.browser.ai.local.LocalModelManager.DownloadState.WaitingNetwork -> "Pause"
                dl is com.cometx.browser.ai.local.LocalModelManager.DownloadState.Idle && File(local.fileFor(m).absolutePath + ".part").exists() -> "Resume"
                downloaded -> "Activate"
                else -> "Download"
            },
            if (downloaded && !active) Tonal.FILL else Tonal.TONAL
        )
        val secondary = actionButton("Delete", Tonal.TEXT)
        if (!downloaded && dl == null) secondary.isEnabled = false
        row(primary, secondary)

        primary.setOnClickListener {
            when {
                dl is com.cometx.browser.ai.local.LocalModelManager.DownloadState.Downloading -> { local.pause(m); buildUi() }
                dl is com.cometx.browser.ai.local.LocalModelManager.DownloadState.WaitingNetwork -> { local.pause(m); buildUi() }
                dl is com.cometx.browser.ai.local.LocalModelManager.DownloadState.Idle && File(local.fileFor(m).absolutePath + ".part").exists() -> { local.resume(m); buildUi() }
                downloaded -> uiScope.launch {
                    val ok = local.selectAndLoad(m)
                    if (!ok) {
                        val fail = (local.loadState.value as? com.cometx.browser.ai.local.LocalModelManager.LoadState.Failed)?.reason
                        toast(fail ?: "Activation failed")
                    } else toast("Active: ${m.id}")
                    buildUi()
                }
                else -> {
                    maybeAskNotificationPermission()
                    local.download(m); buildUi()
                }
            }
        }
        secondary.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Delete ${m.fileName}?")
                .setMessage(if (active) "The model is currently active and will be unloaded, then deleted." else "The downloaded file will be removed. You can re-download it anytime.")
                .setPositiveButton("Delete") { _, _ -> local.delete(m); buildUi() }
                .setNegativeButton("Cancel", null).show()
        }

        when {
            loading && (local.loadState.value as? com.cometx.browser.ai.local.LocalModelManager.LoadState.Loading)?.modelId == m.id ->
                localStatusLabels[m.id]?.text = "loading into RAM…"
            active -> localStatusLabels[m.id]?.text = "ACTIVE"
            dl is com.cometx.browser.ai.local.LocalModelManager.DownloadState.Downloading -> {
                val pct = (dl.downloaded * 100 / dl.total.coerceAtLeast(1))
                localStatusLabels[m.id]?.text = "$pct% · ${dl.speedKbs} KB/s"
            }
            dl is com.cometx.browser.ai.local.LocalModelManager.DownloadState.WaitingNetwork -> {
                val pct = (dl.downloaded * 100 / dl.total.coerceAtLeast(1))
                localStatusLabels[m.id]?.text = "$pct% · waiting for network…"
            }
            dl is com.cometx.browser.ai.local.LocalModelManager.DownloadState.Verifying ->
                localStatusLabels[m.id]?.text = "verifying…"
            dl is com.cometx.browser.ai.local.LocalModelManager.DownloadState.Failed ->
                localStatusLabels[m.id]?.text = "✗ ${dl.reason.take(48)}"
            downloaded -> localStatusLabels[m.id]?.text = "downloaded"
            else -> localStatusLabels[m.id]?.text = "not downloaded"
        }
    }

    private val lastLocalStates = mutableMapOf<String, Boolean>() // modelId → was terminal (Done/Idle)

    /**
     * v1.7.0: background downloads run behind a foreground-service notification.
     * On Android 13+ the notification is only VISIBLE with POST_NOTIFICATIONS —
     * ask once, opportunistically; the download itself works either way.
     */
    private fun maybeAskNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            runCatching {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7001)
            }
        }
    }

    private fun refreshLocalStatuses(local: com.cometx.browser.ai.local.LocalModelManager) {
        var anyTransition = false
        for (m in local.allModels()) {
            val tv = localStatusLabels[m.id] ?: continue
            val dl = local.states.value[m.id]
            val active = local.isModelActive(m)
            val terminal = dl is com.cometx.browser.ai.local.LocalModelManager.DownloadState.Done ||
                dl is com.cometx.browser.ai.local.LocalModelManager.DownloadState.Failed
            if (lastLocalStates[m.id] == false && terminal) anyTransition = true
            lastLocalStates[m.id] = terminal
            when {
                active -> tv.text = "ACTIVE"
                dl is com.cometx.browser.ai.local.LocalModelManager.DownloadState.Downloading -> {
                    val pct = (dl.downloaded * 100 / dl.total.coerceAtLeast(1))
                    tv.text = "$pct% · ${dl.speedKbs} KB/s"
                }
                dl is com.cometx.browser.ai.local.LocalModelManager.DownloadState.WaitingNetwork -> {
                    val pct = (dl.downloaded * 100 / dl.total.coerceAtLeast(1))
                    tv.text = "$pct% · waiting for network…"
                }
                dl is com.cometx.browser.ai.local.LocalModelManager.DownloadState.Verifying -> tv.text = "verifying…"
                dl is com.cometx.browser.ai.local.LocalModelManager.DownloadState.Failed -> tv.text = "✗ ${dl.reason.take(48)}"
                local.isDownloaded(m) -> tv.text = "downloaded"
            }
        }
        // a download just finished/failed: rebuild once so buttons match the new state
        if (anyTransition) { stopLocalRefresh(); buildUi() }
    }

    private fun runLocalModelTest(local: com.cometx.browser.ai.local.LocalModelManager) {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Test model").setMessage("Generating…").show()
        uiScope.launch {
            val report = local.testActive()
            runOnUiThread {
                dialog.dismiss()
                report.fold(
                    onSuccess = { r ->
                        MaterialAlertDialogBuilder(this@SettingsActivity)
                            .setTitle("Model works")
                            .setMessage("${r.modelId}\n${r.elapsedMs} ms · ${r.tokens} tok · ${"%.1f".format(r.tokensPerSec)} tok/s\n\nOutput: ${r.snippet}")
                            .setPositiveButton("OK", null).show()
                    },
                    onFailure = { t ->
                        MaterialAlertDialogBuilder(this@SettingsActivity)
                            .setTitle("Test failed")
                            .setMessage(t.message ?: "Unknown error")
                            .setPositiveButton("OK", null).show()
                    },
                )
            }
        }
    }

    private fun pickGgufFile() {
        val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(android.content.Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(android.content.Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream", "application/gguf"))
        }
        startActivityForResult(intent, REQ_IMPORT_GGUF)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_IMPORT_GGUF && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            uiScope.launch {
                val res = CometApp.app.localAI.import(uri)
                res.fold(
                    onSuccess = { f ->
                        toast("Imported ${f.name}")
                        val imported = CometApp.app.localAI.importedModels().firstOrNull { it.fileName == f.name }
                        if (imported != null) CometApp.app.localAI.selectAndLoad(imported)
                        buildUi()
                    },
                    onFailure = { t -> toast("Import failed: ${t.message}") },
                )
            }
        }
    }

    private fun stopLocalRefresh() {
        localRefreshTick?.let { android.os.Handler(android.os.Looper.getMainLooper()).removeCallbacks(it) }
        localRefreshTick = null
        localStatusLabels.clear()
    }

    override fun onPause() {
        super.onPause()
        stopLocalRefresh()
    }

    // ------------------------------------------------------------- misc widgets

    private fun addTextField(label: String, value: String?, isPassword: Boolean = false, autoSave: Boolean = true, onDone: (String) -> Unit) {
        val tv = TextView(this)
        tv.text = label
        tv.textSize = 12f
        tv.setTextColor(getColor(com.cometx.browser.R.color.text_secondary))
        root.addView(tv)
        val input = EditText(this)
        input.setText(value)
        input.textSize = 13f
        input.setTextColor(getColor(com.cometx.browser.R.color.text_primary))
        if (isPassword) input.transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
        root.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp(8)) })
        if (autoSave) {
            input.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) onDone(input.text.toString().trim()) }
        }
    }

    private fun addNumberField(label: String, value: Int, onDone: (Int) -> Unit) {
        addTextField(label, value.toString()) { v -> v.toIntOrNull()?.let(onDone) }
    }

    private fun addCheck(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        val cb = MaterialSwitch(this)
        cb.text = label
        cb.textSize = 13f
        cb.isChecked = checked
        cb.setTextColor(getColor(com.cometx.browser.R.color.text_primary))
        cb.setOnCheckedChangeListener { _, c -> onChange(c) }
        root.addView(cb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(4), 0, dp(4)) })
    }

    private fun addVisionMode() {
        body("Vision (screenshot) usage: AUTO = only when needed (recommended), ALWAYS = every step (expensive), OFF = only on explicit agent request. " +
            "If the agent model cannot read images, Comet-X automatically uses a separate vision model when available, or DOM/accessibility perception.")
        val spinner = Spinner(this)
        val modes = listOf("AUTO", "ALWAYS", "OFF")
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, modes.map {
            if (it == "AUTO") "$it (recommended)" else it
        })
        spinner.setSelection(modes.indexOf(settings.visionMode().name).coerceAtLeast(0))
        root.addView(spinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp(8)) })
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, pos: Int, id: Long) {
                settings.setVisionMode(SettingsRepository.VisionMode.valueOf(modes[pos]))
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    // ------------------------------------------------- v2.0.0 browser settings

    /** Search engine picker (built-ins + customs) + custom-engine manager. */
    private fun addSearchEngine() {
        body("Search engine: used by the omnibox and the start page. Custom engines need a URL with a %s placeholder for the query.")
        val customs = com.cometx.browser.browse.SearchEngines.parseCustomEngines(settings.customEngines())
        val all = com.cometx.browser.browse.SearchEngines.allEngines(settings.customEngines())
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, all.map { it.name }).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val current = settings.searchEngine().coerceIn(0, all.size - 1)
        spinner.setSelection(current)
        root.addView(spinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp(8)) })
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, pos: Int, id: Long) {
                settings.setSearchEngine(pos)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        val manageBtn = actionButton("Custom search engines (${customs.size})", Tonal.TONAL)
        root.addView(manageBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp(8)) })
        manageBtn.setOnClickListener { showCustomEngineDialog() }
    }

    /** Add/set-default/delete loop for user-defined engines (custom, safe). */
    private fun showCustomEngineDialog() {
        val customs = com.cometx.browser.browse.SearchEngines.parseCustomEngines(settings.customEngines()).toMutableList()
        val names = if (customs.isEmpty()) arrayOf("(none yet)") else customs.map { it.name }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Custom search engines")
            .setItems(names) { _, which ->
                if (customs.isNotEmpty()) {
                    customs.removeAt(which)
                    settings.setCustomEngines(com.cometx.browser.browse.SearchEngines.serializeCustomEngines(customs))
                    buildUi()
                }
            }
            .setPositiveButton("Add engine") { _, _ -> showAddEngineDialog(customs) }
            .setNeutralButton("Close", null)
            .show()
    }

    private fun showAddEngineDialog(customs: MutableList<com.cometx.browser.browse.SearchEngines.Engine>) {
        val nameInput = EditText(this).apply { hint = "Name (e.g. Kagi)" }
        val urlInput = EditText(this).apply { hint = "https://kagi.com/search?q=%s" }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(nameInput)
            addView(urlInput)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Add search engine")
            .setView(box)
            .setPositiveButton("Add") { _, _ ->
                val name = nameInput.text.toString().trim()
                val url = urlInput.text.toString().trim()
                if (!com.cometx.browser.browse.SearchEngines.validCustomEngine(name, url)) {
                    toast("Engine needs a name and a URL containing %s")
                    return@setPositiveButton
                }
                if (customs.size >= com.cometx.browser.browse.SearchEngines.MAX_CUSTOM) {
                    toast("Custom engine list is full")
                    return@setPositiveButton
                }
                customs.add(com.cometx.browser.browse.SearchEngines.Engine(name, url, true))
                settings.setCustomEngines(com.cometx.browser.browse.SearchEngines.serializeCustomEngines(customs))
                buildUi()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Web text zoom 50–200% applied live to every open tab. */
    private fun addTextZoom() {
        val label = TextView(this).apply {
            text = "Web text size: ${settings.textZoom()}% (applied live to every tab)"
            textSize = 12f
            setTextColor(getColor(com.cometx.browser.R.color.text_secondary))
        }
        root.addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp(4)) })
        val seek = android.widget.SeekBar(this)
        seek.max = 150 // 50..200
        seek.progress = (settings.textZoom() - 50).coerceIn(0, 150)
        root.addView(seek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp(8)) })
        seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar?, value: Int, fromUser: Boolean) {
                label.text = "Web text size: ${value + 50}%"
            }
            override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(s: android.widget.SeekBar?) {
                settings.setTextZoom((s?.progress ?: 50) + 50)
            }
        })
    }

    /** Multiline per-site blocking exemption editor. */
    private fun addAllowlistEditor() {
        val editBtn = actionButton("Sites exempt from blocking", Tonal.TONAL)
        root.addView(editBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(4), 0, dp(8)) })
        editBtn.setOnClickListener {
            val input = EditText(this).apply {
                hint = "example.com\nanother.org"
                setText(settings.allowlist())
                minLines = 3
            }
            MaterialAlertDialogBuilder(this)
                .setTitle("Sites exempt from blocking")
                .setMessage("One host per line. Requests to these sites are never blocked.")
                .setView(input)
                .setPositiveButton("Save") { _, _ ->
                    settings.setAllowlist(input.text.toString())
                    val app = CometApp.app
                    app.adBlocker.rebuildAllowlist(input.text.toString())
                    toast("Blocking exemption saved")
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    /** Manual + automatic filter-list refresh (validated, atomic swap). */
    private fun addFilterUpdater() {
        addCheck("Keep filter lists updated (about weekly)", settings.autoUpdateLists()) { settings.setAutoUpdateLists(it) }
        val last = settings.listLastUpdate()
        val lastText = if (last == 0L) "never" else SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(last))
        body("Last update: $lastText · Sources: StevenBlack hosts (MIT) + curated cosmetic rules.")
        val updateBtn = actionButton("Update filter lists", Tonal.TONAL)
        root.addView(updateBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp(8)) })
        updateBtn.setOnClickListener {
            toast("Updating filter lists…")
            FilterUpdater.updateAll(this) { updated ->
                if (updated > 0) {
                    settings.setListLastUpdate(System.currentTimeMillis())
                    val app = CometApp.app
                    app.adBlocker.loadFrom(filesDir) {
                        assets.open(com.cometx.browser.browse.AdBlocker.ASSET_FILE).bufferedReader().use { it.readLines() }
                    }
                    app.adBlocker.rebuildAllowlist(settings.allowlist())
                    com.cometx.browser.browse.CosmeticFilter.invalidate()
                    toast("Filter lists updated")
                } else {
                    toast("Update failed — the bundled lists keep blocking")
                }
                buildUi()
            }
        }
    }

    /** App theme: follow system / light / dark (applies immediately). */
    private fun addThemePicker() {
        val spinner = Spinner(this)
        val modes = listOf("Follow system", "Light", "Dark")
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, modes).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinner.setSelection(settings.appTheme().coerceIn(0, 2))
        root.addView(spinner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp(8)) })
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, pos: Int, id: Long) {
                settings.setAppTheme(pos)
                androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                    when (pos) {
                        1 -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                        2 -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                        else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    }
                )
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    private fun memorySummary(): String {
        val um = memory.userMemory()
        val recent = memory.recentTasks()
        return "User memory: ${um.size} fact(s). Recent tasks: ${recent.size}. " +
            if (um.isNotEmpty()) "Saved keys: ${um.keys.take(8).joinToString(", ")}" else "No user facts saved yet."
    }

    private fun showMemory() {
        val um = memory.userMemory()
        val recent = memory.recentTasks()
        val text = buildString {
            appendLine("USER MEMORY (${um.size}):")
            um.forEach { (k, v) -> appendLine("• $k: ${v.take(80)}") }
            appendLine()
            appendLine("RECENT TASKS (${recent.size}):")
            recent.forEach { (g, o, _) -> appendLine("• ${g.take(60)} → $o") }
            if (um.isEmpty() && recent.isEmpty()) appendLine("(empty)")
        }
        MaterialAlertDialogBuilder(this).setTitle("Agent memory").setMessage(text)
            .setPositiveButton("Close", null)
            .setNeutralButton("Clear all") { _, _ -> memory.clearAll(); buildUi() }
            .show()
    }

    private fun header(text: String) {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 13f
        tv.letterSpacing = 0.06f
        tv.isAllCaps = true
        tv.setTypeface(tv.typeface, android.graphics.Typeface.BOLD)
        tv.setTextColor(getColor(com.cometx.browser.R.color.primary))
        root.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(22), 0, dp(10)) })
    }

    private fun body(text: String) {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 12f
        tv.setTextColor(getColor(com.cometx.browser.R.color.text_secondary))
        root.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp(8)) })
    }

    private fun row(a: MaterialButton, b: MaterialButton) {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        lp.setMargins(0, dp(4), dp(8), 0)
        r.addView(a, lp)
        r.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(r)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()

    // ------------------------------------------------- comet component factory

    private enum class Tonal { FILL, TONAL, TEXT }

    /** Full-pill action button in one of the three M3 tonal roles. */
    private fun actionButton(label: String, kind: Tonal): MaterialButton {
        val attr = when (kind) {
            Tonal.FILL -> com.google.android.material.R.attr.materialButtonStyle
            Tonal.TONAL -> com.cometx.browser.R.attr.cometButtonTonalStyle
            Tonal.TEXT -> com.cometx.browser.R.attr.cometButtonTextStyle
        }
        return MaterialButton(this, null, attr).apply {
            text = label
            isAllCaps = false
            textSize = 13f
            cornerRadius = dp(22)
            insetTop = 0
            insetBottom = 0
            minHeight = dp(44)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        uiScope.cancel()
    }
}

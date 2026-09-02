package com.cometx.browser.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
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
import com.cometx.browser.memory.MemoryStore
import com.cometx.browser.security.SecureStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
class SettingsActivity : Activity() {

    private lateinit var settings: SettingsRepository
    private lateinit var secure: SecureStore
    private lateinit var memory: MemoryStore
    private lateinit var catalog: ModelCatalog
    private lateinit var root: LinearLayout

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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
        var testButton: Button? = null
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

        header("Agent behavior")
        addNumberField("Max steps per task (4–60)", settings.maxSteps()) { settings.setMaxSteps(it) }
        addCheck("Confirm high-risk actions (purchases, deletes, sends)", settings.confirmHighRisk()) { settings.setConfirmHighRisk(it) }
        addCheck("Agent memory enabled (can be cleared below)", settings.memoryEnabled()) { settings.setMemoryEnabled(it) }
        addVisionMode()

        header("Browser")
        addTextField("Homepage", settings.homepage(), autoSave = true) { settings.setHomepage(it) }
        addCheck("Allow third-party cookies", settings.thirdPartyCookies()) { settings.setThirdPartyCookies(it) }

        header("Memory")
        body(memorySummary())
        val viewBtn = Button(this); viewBtn.text = "View memory"
        val clearBtn = Button(this); clearBtn.text = "Clear memory"
        row(viewBtn, clearBtn)
        viewBtn.setOnClickListener { showMemory() }
        clearBtn.setOnClickListener {
            AlertDialog.Builder(this)
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
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = getDrawable(com.cometx.browser.R.drawable.bg_panel_input)
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
            textSize = 11f
            setTextColor(getColor(com.cometx.browser.R.color.text_secondary))
            gravity = android.view.Gravity.END
        }
        titleRow.addView(s.statusLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        card.addView(titleRow)

        card.addView(TextView(this).apply {
            text = providerTags[id]
            textSize = 11f
            setTextColor(getColor(com.cometx.browser.R.color.text_secondary))
        })

        // -- enabled (fallback chain membership)
        val enabled = CheckBox(this).apply {
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
                textSize = 11f
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
        val test = Button(this).apply { text = "Test & Enable" }
        s.testButton = test
        card.addView(test, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(6), 0, dp(2)) })

        // -- unsaved indicator
        s.dirtyDot = TextView(this).apply {
            text = "unsaved — Test & Enable will save it"
            textSize = 11f
            setTextColor(getColor(com.cometx.browser.R.color.accent_bright))
            visibility = android.view.View.GONE
        }
        card.addView(s.dirtyDot)

        // -- Advanced disclosure (optional overrides, §13)
        val advToggle = Button(this).apply { text = if (advancedExpanded[id] == true) "Advanced ▴" else "Advanced ▾"; textSize = 11f }
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
            textSize = 11f
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
            textSize = 11f
            setTextColor(getColor(com.cometx.browser.R.color.text_secondary))
            setPadding(0, dp(6), 0, dp(2))
        })
        for (role in ModelRouter.Role.entries) addAdvancedModelSpinner(box, id, role)

        box.addView(TextView(this).apply {
            text = "Cache: " + (settings.lastDiagnostics(id)?.optString("providerName")?.let { "last test stored" } ?: "no test run yet")
            textSize = 11f
            setTextColor(getColor(com.cometx.browser.R.color.text_secondary))
            setPadding(0, dp(8), 0, 0)
        })
        val clearCache = Button(this).apply { text = "Forget discovered models (refresh cache)"; textSize = 11f }
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
                        AlertDialog.Builder(this@SettingsActivity)
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
            AlertDialog.Builder(this@SettingsActivity)
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
        val compatBtn = Button(this); compatBtn.text = "Run Agent Compatibility Test"
        root.addView(compatBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        compatBtn.setOnClickListener { runCompatibilityTest() }

        val logBtn = Button(this); logBtn.text = "View AI event log"
        val clearLogBtn = Button(this); clearLogBtn.text = "Clear log"
        row(logBtn, clearLogBtn)
        logBtn.setOnClickListener { showAiLog() }
        clearLogBtn.setOnClickListener { settings.clearAiLog(); toast("AI event log cleared") }
    }

    private fun runCompatibilityTest() {
        val ready = providers.entries.filter { it.value.isReady() }
        if (ready.isEmpty()) { toast("Test & Enable a provider first"); return }
        val ids = ready.map { it.key }
        var chosen = ids.first()
        AlertDialog.Builder(this)
            .setTitle("Run on which provider?")
            .setSingleChoiceItems(ids.map { providerNames[it] }.toTypedArray(), 0) { _, which -> chosen = ids[which] }
            .setPositiveButton("Run") { _, _ -> doCompatibilityTest(chosen) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doCompatibilityTest(id: String) {
        val p = providers[id] ?: return
        val dialog = AlertDialog.Builder(this).setTitle("Agent Compatibility Test").setMessage("Running…").show()
        uiScope.launch {
            val text = try {
                ConnectionDiagnostics(catalog).compatibilitySelfTest(p)
            } catch (e: Exception) {
                "Compatibility test failed: ${e.message}"
            }
            runOnUiThread {
                AlertDialog.Builder(this@SettingsActivity)
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
        AlertDialog.Builder(this).setTitle("AI event log — automatic fallbacks & switches").setMessage(text)
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
        val cb = CheckBox(this)
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
        AlertDialog.Builder(this).setTitle("Agent memory").setMessage(text)
            .setPositiveButton("Close", null)
            .setNeutralButton("Clear all") { _, _ -> memory.clearAll(); buildUi() }
            .show()
    }

    private fun header(text: String) {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 19f
        tv.setTextColor(getColor(com.cometx.browser.R.color.accent_bright))
        root.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(18), 0, dp(6)) })
    }

    private fun body(text: String) {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 12f
        tv.setTextColor(getColor(com.cometx.browser.R.color.text_secondary))
        root.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp(8)) })
    }

    private fun row(a: Button, b: Button) {
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

    override fun onDestroy() {
        super.onDestroy()
        uiScope.cancel()
    }
}

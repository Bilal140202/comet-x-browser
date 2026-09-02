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
import com.cometx.browser.ai.CustomOpenAIProvider
import com.cometx.browser.ai.GroqProvider
import com.cometx.browser.ai.HuggingFaceProvider
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

/**
 * SettingsActivity — provider configuration with explicit Save/Test per provider,
 * model dropdowns (fetched + manual + default), and a user-ordered fallback chain.
 *
 * v1.1.0: replaces focus-loss persistence with explicit [Save]; adds [Test]
 * (real connectivity check), [Fetch models] (live catalog), per-role model
 * dropdowns with default fallback, chain priority ordering, and self-run URL
 * normalization.
 */
class SettingsActivity : Activity() {

    private lateinit var settings: SettingsRepository
    private lateinit var secure: SecureStore
    private lateinit var memory: MemoryStore
    private lateinit var root: LinearLayout

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val providerNames = mapOf(
        "groq" to "Groq",
        "openrouter" to "OpenRouter",
        "huggingface" to "Hugging Face",
        "custom" to "Self-run (OpenAI-compatible)"
    )
    private val providerTags = mapOf(
        "groq" to "fastest inference · free tier",
        "openrouter" to "400+ models behind one key",
        "huggingface" to "inference router · free quota",
        "custom" to "Ollama · LM Studio · vLLM · any /v1 endpoint"
    )

    /** Per-provider pending (unsaved) UI state. */
    private class ProviderUi {
        var keyText: String = ""
        var urlText: String = ""
        val roleValues = mutableMapOf<ModelRouter.Role, String>() // "" = default
        var fetched: List<String>? = null
        val spinners = mutableMapOf<ModelRouter.Role, Spinner>()
        var dirtyDot: TextView? = null
        var statusLabel: TextView? = null
        var testButton: Button? = null
        var fetchButton: Button? = null
        var savedKey: String = ""
        var savedUrl: String = ""
        val savedRoleValues = mutableMapOf<ModelRouter.Role, String>()
        val spinnerPos = mutableMapOf<ModelRouter.Role, Int>()
        var dialogOpen = false

        fun isDirty(): Boolean =
            keyText != savedKey || urlText != savedUrl ||
                ModelRouter.Role.entries.any { (roleValues[it] ?: "") != (savedRoleValues[it] ?: "") }
    }

    private val ui = mutableMapOf<String, ProviderUi>()

    private val providers: Map<String, OpenAICompatibleProvider> by lazy {
        mapOf(
            "groq" to GroqProvider { settings.apiKey("groq") },
            "openrouter" to OpenRouterProvider { settings.apiKey("openrouter") },
            "huggingface" to HuggingFaceProvider { settings.apiKey("huggingface") },
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

        header("AI Providers")
        body("Paste a key, press Save, then Test. Defaults are preconfigured — a key alone works. " +
            "Enabled providers form a fallback chain: if one fails, the next serves the request automatically. " +
            "Keys are encrypted with the Android Keystore and never leave the device.")
        chainSummary()
        for (id in SettingsRepository.ALL_PROVIDERS) addProviderBlock(id)

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

    private fun chainSummary() {
        val live = settings.liveChain()
        val text = if (live.isEmpty())
            "⚠ Fallback chain is EMPTY — enable a provider and save a key."
        else
            "Fallback chain: " + live.mapIndexed { i, id -> "${i + 1}. ${providerNames[id]}" }.joinToString("  →  ")
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 12f
        tv.setTextColor(getColor(if (live.isEmpty()) com.cometx.browser.R.color.accent_bright else com.cometx.browser.R.color.text_secondary))
        root.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp(8)) })
    }

    // ---------------------------------------------------------------- providers

    private fun addProviderBlock(id: String) {
        val p = providers[id] ?: return
        val s = ProviderUi()
        ui[id] = s
        s.savedKey = settings.apiKey(id) ?: ""
        s.savedUrl = settings.baseUrl(id) ?: ""
        for (role in ModelRouter.Role.entries) {
            s.savedRoleValues[role] = settings.modelFor(id, role) ?: ""
            s.roleValues[role] = s.savedRoleValues[role] ?: ""
        }
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
        val name = TextView(this).apply {
            text = providerNames[id]
            textSize = 15f
            setTextColor(getColor(com.cometx.browser.R.color.text_primary))
        }
        titleRow.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        s.statusLabel = TextView(this).apply {
            textSize = 11f
            setTextColor(getColor(com.cometx.browser.R.color.text_secondary))
            gravity = android.view.Gravity.END
        }
        titleRow.addView(s.statusLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        card.addView(titleRow)

        val tag = TextView(this).apply {
            text = providerTags[id]
            textSize = 11f
            setTextColor(getColor(com.cometx.browser.R.color.text_secondary))
        }
        card.addView(tag)

        // -- enabled + priority
        val enabled = CheckBox(this).apply {
            text = "Enabled — in fallback chain"
            textSize = 13f
            setTextColor(getColor(com.cometx.browser.R.color.text_primary))
            isChecked = settings.providerEnabled(id)
        }
        card.addView(enabled, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(4), 0, 0) })
        enabled.setOnCheckedChangeListener { _, checked ->
            settings.setProviderEnabled(id, checked)
            buildUi()
        }

        val prioRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        prioRow.addView(TextView(this).apply {
            text = "Priority:"
            textSize = 12f
            setTextColor(getColor(com.cometx.browser.R.color.text_secondary))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { gravity = android.view.Gravity.CENTER_VERTICAL })
        val order = settings.chainOrder()
        val pos = order.indexOf(id)
        val up = Button(this).apply { text = "▲"; textSize = 11f; isEnabled = pos > 0 }
        val down = Button(this).apply { text = "▼"; textSize = 11f; isEnabled = pos < order.size - 1 }
        up.setOnClickListener { settings.moveInChain(id, -1); buildUi() }
        down.setOnClickListener { settings.moveInChain(id, 1); buildUi() }
        prioRow.addView(up, LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(4), 0, 0, 0) })
        prioRow.addView(down, LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.WRAP_CONTENT))
        card.addView(prioRow)

        // -- API key (not auto-saved)
        val keyLabel = TextView(this).apply {
            text = if (s.savedKey.isNotBlank()) "API key (saved — retype to replace)" else if (id == "custom") "API key (optional for local servers)" else "API key (not set)"
            textSize = 12f
            setTextColor(getColor(com.cometx.browser.R.color.text_secondary))
        }
        card.addView(keyLabel)
        val keyInput = EditText(this).apply {
            setText(if (s.savedKey.isNotBlank()) "" else "") // never echo saved key to screen
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

        // -- models per role: dropdown with Default + fetched + Custom
        card.addView(TextView(this).apply {
            text = "Models per role — Default works out of the box; Fetch models to load the live list; Custom to type one:"
            textSize = 12f
            setTextColor(getColor(com.cometx.browser.R.color.text_secondary))
            setPadding(0, dp(6), 0, dp(2))
        })
        for (role in ModelRouter.Role.entries) addModelSpinner(card, id, role, s)

        // -- actions: Save / Test / Fetch models
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val save = Button(this).apply { text = "Save" }
        val test = Button(this).apply { text = "Test" }
        val fetch = Button(this).apply { text = "Fetch models"; textSize = 11f }
        s.testButton = test
        s.fetchButton = fetch
        actions.addView(save, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(test, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(6), 0, 0, 0) })
        card.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(8), 0, dp(2)) })
        card.addView(fetch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val dirtyRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        s.dirtyDot = TextView(this).apply {
            text = "unsaved changes — press Save to apply"
            textSize = 11f
            setTextColor(getColor(com.cometx.browser.R.color.accent_bright))
            visibility = android.view.View.GONE
        }
        dirtyRow.addView(s.dirtyDot)
        card.addView(dirtyRow)

        refreshStatus(id, s)

        save.setOnClickListener { saveProvider(id, s) }
        test.setOnClickListener { testProvider(id, s) }
        fetch.setOnClickListener { fetchModels(id, s) }
        refreshDirty(s)
    }

    private fun addModelSpinner(parent: LinearLayout, id: String, role: ModelRouter.Role, s: ProviderUi) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(TextView(this).apply {
            text = role.name.lowercase().replaceFirstChar { it.uppercase() }
            textSize = 12f
            setTextColor(getColor(com.cometx.browser.R.color.text_primary))
        }, LinearLayout.LayoutParams(dp(88), ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = android.view.Gravity.CENTER_VERTICAL })

        val spinner = Spinner(this)
        s.spinners[role] = spinner
        row.addView(spinner, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        parent.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(2), 0, dp(2)) })
        refreshSpinner(id, role, s)
    }

    private fun refreshSpinner(id: String, role: ModelRouter.Role, s: ProviderUi) {
        val spinner = s.spinners[role] ?: return
        val defaultModel = ModelRouter.defaultModelFor(id, role)
        val options = ArrayList<String>()
        options.add("Default — $defaultModel")
        s.fetched?.let { fetched -> options.addAll(fetched) }
        options.add("Custom… (type manually)")

        val current = s.roleValues[role] ?: ""
        var selected = 0
        if (current.isNotBlank()) {
            val idx = s.fetched?.indexOf(current)?.plus(1) ?: -1
            selected = if (idx >= 0) idx else options.size - 1
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, options)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setSelection(selected.coerceAtMost(options.size - 1), false)
        s.spinnerPos[role] = selected.coerceAtMost(options.size - 1)
        spinner.tag = role

        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, pos: Int, rowId: Long) {
                val r = spinner.tag as ModelRouter.Role
                if (pos == s.spinnerPos[r]) return // programmatic re-render, not a user choice
                s.spinnerPos[r] = pos
                when {
                    pos == 0 -> { s.roleValues[r] = ""; refreshDirty(s) }
                    pos == options.size - 1 -> {
                        if (s.dialogOpen) return
                        s.dialogOpen = true
                        promptManualModel(id, r, s, current = s.roleValues[r] ?: "") { typed ->
                            s.dialogOpen = false
                            if (typed != null) {
                                s.roleValues[r] = typed.trim()
                                refreshDirty(s)
                            }
                            refreshSpinner(id, r, s) // re-render; loop-safe via spinnerPos
                        }
                    }
                    else -> {
                        s.roleValues[r] = options[pos]
                        refreshDirty(s)
                    }
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    private fun promptManualModel(id: String, role: ModelRouter.Role, s: ProviderUi, current: String, onDone: (String?) -> Unit) {
        val input = EditText(this).apply {
            setText(current)
            hint = "e.g. llama-3.3-70b-versatile or qwen2.5:14b"
            textSize = 13f
        }
        AlertDialog.Builder(this)
            .setTitle("Custom model — ${providerNames[id]} / ${role.name.lowercase()}")
            .setMessage("Type the exact model id served by this provider. Leave blank to fall back to the Default.")
            .setView(input)
            .setPositiveButton("OK") { _, _ -> onDone(input.text.toString().trim()) }
            .setNegativeButton("Cancel") { _, _ -> onDone(null) }
            .show()
    }

    private fun keyHint(id: String) = when (id) {
        "groq" -> "gsk_…  (console.groq.com/keys)"
        "openrouter" -> "sk-or-…  (openrouter.ai/keys)"
        "huggingface" -> "hf_…  (huggingface.co/settings/tokens)"
        else -> "optional — local servers usually need none"
    }

    // ------------------------------------------------------------ save / test

    private fun saveProvider(id: String, s: ProviderUi) {
        // key: blank keeps existing (never wipe silently)
        if (s.keyText.isNotBlank()) settings.setApiKey(id, s.keyText.trim())
        if (id == "custom") settings.setBaseUrl(id, UrlNormalizer.normalize(s.urlText))
        for (role in ModelRouter.Role.entries) {
            settings.setModel(id, role, s.roleValues[role] ?: "")
        }
        toast("Saved ✓")
        buildUi()
    }

    private fun testProvider(id: String, s: ProviderUi) {
        if (s.isDirty()) { toast("Unsaved changes — press Save first"); return }
        val p = providers[id] ?: return
        if (!p.isReady()) { toast("Nothing to test yet — save a key (or a base URL) first"); return }

        s.testButton?.isEnabled = false
        s.fetchButton?.isEnabled = false
        s.statusLabel?.text = "testing…"
        val model = settings.modelFor(id, ModelRouter.Role.FAST) ?: ModelRouter.defaultModelFor(id, ModelRouter.Role.FAST)

        uiScope.launch {
            var result = StringBuilder()
            var okAll = true
            try {
                val t0 = System.currentTimeMillis()
                val models = p.listModels()
                val tModels = System.currentTimeMillis() - t0
                result.append("✓ Connected — ${models.size} models visible (${tModels}ms)\n")
                val t1 = System.currentTimeMillis()
                p.ping(model)
                val tPing = System.currentTimeMillis() - t1
                result.append("✓ Model \"$model\" answered in ${tPing}ms\n\nReady — chat & agent will use ${providerNames[id]}.")
                settings.setLastTest(id, true, "OK — $model (${tPing}ms)")
            } catch (e: Exception) {
                okAll = false
                val msg = if (e is ProviderException) e.message ?: "provider error" else "${e.javaClass.simpleName}: ${e.message}"
                result.append("✗ Test failed — $msg")
                if (id == "custom") result.append("\n\nTip: the URL must be reachable from this device. For a server on your computer, expose it via your LAN IP or a tunnel (e.g. cloudflared).")
                settings.setLastTest(id, false, msg)
            }
            AlertDialog.Builder(this@SettingsActivity)
                .setTitle(if (okAll) "Test passed" else "Test failed")
                .setMessage(result.toString())
                .setPositiveButton("OK", null)
                .show()
            buildUi()
        }
    }

    private fun fetchModels(id: String, s: ProviderUi) {
        if (s.isDirty()) { toast("Unsaved changes — press Save first"); return }
        val p = providers[id] ?: return
        if (!p.isReady()) { toast("Save a key first"); return }
        s.fetchButton?.isEnabled = false
        s.statusLabel?.text = "fetching models…"
        uiScope.launch {
            try {
                val models = p.listModels()
                s.fetched = models
                toast("${models.size} models loaded")
            } catch (e: Exception) {
                val msg = if (e is ProviderException) e.message else "${e.message}"
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("Could not list models").setMessage("$msg")
                    .setPositiveButton("OK", null).show()
            }
            withContext(Dispatchers.Main) { buildUi() }
        }
    }

    // ---------------------------------------------------------------- state ui

    private fun refreshDirty(s: ProviderUi) {
        s.dirtyDot?.visibility = if (s.isDirty()) android.view.View.VISIBLE else android.view.View.GONE
        val blocked = s.isDirty()
        s.testButton?.isEnabled = !blocked
        s.fetchButton?.isEnabled = !blocked
        s.testButton?.alpha = if (blocked) 0.5f else 1f
        s.fetchButton?.alpha = if (blocked) 0.5f else 1f
    }

    private fun refreshStatus(id: String, s: ProviderUi) {
        val last = settings.lastTest(id)
        val text = when {
            last != null && last.startsWith("ok") -> {
                val msg = last.split("|").getOrNull(2) ?: ""
                "✓ $msg"
            }
            last != null && last.startsWith("fail") -> {
                val msg = last.split("|").getOrNull(2) ?: ""
                "✗ $msg"
            }
            s.savedKey.isNotBlank() || (id == "custom" && s.savedUrl.isNotBlank()) -> "saved · untested"
            else -> "not set up"
        }
        s.statusLabel?.text = text
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
        body("Vision (screenshot) usage: AUTO = only when needed (recommended), ALWAYS = every step (expensive), OFF = only on explicit agent request")
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

package com.cometx.browser.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.cometx.browser.ai.ModelRouter
import com.cometx.browser.ai.SettingsRepository
import com.cometx.browser.memory.MemoryStore
import com.cometx.browser.security.SecureStore

/**
 * SettingsActivity — provider configuration (keys, base URLs, models per role),
 * agent behavior, memory management. Built programmatically for compactness.
 */
class SettingsActivity : Activity() {

    private lateinit var settings: SettingsRepository
    private lateinit var secure: SecureStore
    private lateinit var memory: MemoryStore
    private lateinit var root: LinearLayout

    private val providerIds = listOf("groq", "openrouter", "huggingface", "custom")
    private val providerNames = mapOf(
        "groq" to "Groq",
        "openrouter" to "OpenRouter",
        "huggingface" to "Hugging Face",
        "custom" to "Custom (OpenAI-compatible)"
    )
    private val defaultBaseUrls = mapOf(
        "groq" to "https://api.groq.com/openai/v1",
        "openrouter" to "https://openrouter.ai/api/v1",
        "huggingface" to "https://router.huggingface.co/v1",
        "custom" to "https://api.openai.com/v1"
    )

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

        title("Comet-X Settings")

        header("AI Providers")
        body("Keys are encrypted with the Android Keystore and never leave the device. Enter at least one provider's key; the active provider serves all agent roles.")
        for (id in providerIds) addProviderBlock(id)

        header("Agent behavior")
        addNumberField("Max steps per task (4–60)", settings.maxSteps()) { settings.setMaxSteps(it) }
        addCheck("Confirm high-risk actions (purchases, deletes, sends)", settings.confirmHighRisk()) { settings.setConfirmHighRisk(it) }
        addCheck("Agent memory enabled (can be cleared below)", settings.memoryEnabled()) { settings.setMemoryEnabled(it) }
        addVisionMode()

        header("Browser")
        addTextField("Homepage", settings.homepage()) { settings.setHomepage(it) }
        addCheck("Allow third-party cookies", settings.thirdPartyCookies()) { settings.setThirdPartyCookies(it) }

        header("Memory")
        body(memorySummary())
        row(Button(this).apply {
            text = "View memory"
            setOnClickListener { showMemory() }
        }, Button(this).apply {
            text = "Clear memory"
            setOnClickListener {
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("Clear all memory?")
                    .setMessage("User memory, recent tasks and browser state will be deleted.")
                    .setPositiveButton("Clear") { _, _ -> memory.clearAll(); recreate() }
                    .setNegativeButton("Cancel", null).show()
            }
        })
    }

    private fun addProviderBlock(id: String) {
        card {
            title(providerNames[id] ?: id, small = true)
            val active = CheckBox(this)
            active.text = "Active provider"
            active.isChecked = settings.activeProviderId() == id
            active.setOnCheckedChangeListener { _, checked -> if (checked) settings.setActiveProvider(id) }
            rootOfCard.addView(active)

            addTextField("API key${if (settings.apiKey(id) != null) " (saved — retype to replace)" else " (not set)"}",
                settings.apiKey(id) ?: "", isPassword = true) { settings.setApiKey(id, it) }

            if (id == "custom") {
                addTextField("Base URL (must end with /v1)", settings.baseUrl(id) ?: defaultBaseUrls[id]) { settings.setBaseUrl(id, it) }
            }

            val roles = listOf(
                ModelRouter.Role.FAST to "Fast model",
                ModelRouter.Role.REASONING to "Reasoning model",
                ModelRouter.Role.VISION to "Vision model",
                ModelRouter.Role.STRONG to "Strong model",
                ModelRouter.Role.CHEAP to "Cheap model"
            )
            body("Model IDs (leave blank for provider defaults):")
            for ((role, label) in roles) {
                addTextField("$label (${role.name})",
                    settings.modelFor(id, role) ?: defaultModelHint(id, role), isPassword = false, persist = false) { v ->
                    settings.setModel(id, role, v)
                }
            }
        }
    }

    private fun defaultModelHint(id: String, role: ModelRouter.Role): String = when (id) {
        "groq" -> when (role) {
            ModelRouter.Role.VISION -> "meta-llama/llama-4-scout-17b-16e-instruct"
            ModelRouter.Role.FAST, ModelRouter.Role.CHEAP -> "llama-3.1-8b-instant"
            else -> "llama-3.3-70b-versatile"
        }
        "openrouter" -> when (role) {
            ModelRouter.Role.STRONG -> "anthropic/claude-3.5-sonnet"
            else -> "openai/gpt-4o-mini"
        }
        "huggingface" -> when (role) {
            ModelRouter.Role.VISION -> "Qwen/Qwen2.5-VL-7B-Instruct"
            ModelRouter.Role.FAST, ModelRouter.Role.CHEAP -> "meta-llama/Llama-3.1-8B-Instruct"
            else -> "meta-llama/Llama-3.3-70B-Instruct"
        }
        else -> when (role) {
            ModelRouter.Role.VISION -> "gpt-4o-mini"
            ModelRouter.Role.FAST, ModelRouter.Role.CHEAP -> "gpt-4o-mini"
            else -> "gpt-4o"
        }
    }

    private fun addVisionMode() {
        body("Vision (screenshot) usage: AUTO = only when needed (recommended), ALWAYS = every step (expensive), OFF = only on explicit agent request")
        val input = EditText(this)
        input.setText(settings.visionMode().name)
        input.hint = "AUTO | ALWAYS | OFF"
        input.inputType = android.text.InputType.TYPE_CLASS_TEXT
        input.textSize = 13f
        root.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(4), 0, dp(8)) })
        input.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val mode = try { SettingsRepository.VisionMode.valueOf(input.text.toString().trim().uppercase()) } catch (_: Exception) { null }
                if (mode != null) { settings.setVisionMode(mode); toast("Vision mode: ${mode.name}") } else input.setText(settings.visionMode().name)
            }
        }
    }

    private fun addTextField(label: String, value: String?, isPassword: Boolean = false, persist: Boolean = true, onDone: (String) -> Unit) {
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
        input.inputType = if (isPassword) android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        else android.text.InputType.TYPE_CLASS_TEXT
        root.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp(8)) })
        input.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && persist) onDone(input.text.toString().trim())
        }
    }

    private fun addNumberField(label: String, value: Int, onDone: (Int) -> Unit) {
        addTextField(label, value.toString()) { v ->
            v.toIntOrNull()?.let(onDone)
        }
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

    // ---- little layout helpers ----

    private var cardLayout: LinearLayout? = null

    private fun card(content: () -> Unit) {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(12), dp(10), dp(12), dp(10))
        card.background = getDrawable(com.cometx.browser.R.drawable.bg_panel_input)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, dp(6), 0, dp(6))
        root.addView(card, lp)
        cardLayout = card
        content()
        cardLayout = null
    }

    private val rootOfCard: LinearLayout get() = cardLayout ?: root

    private fun header(text: String, small: Boolean = false) {
        cardLayout = null
        val tv = TextView(this)
        tv.text = text
        tv.textSize = if (small) 15f else 19f
        tv.setTextColor(getColor(com.cometx.browser.R.color.accent_bright))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, dp(if (small) 8 else 18), 0, dp(6))
        root.addView(tv, lp)
    }

    private fun title(text: String, small: Boolean = false) {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = if (small) 15f else 19f
        tv.setTextColor(getColor(com.cometx.browser.R.color.text_primary))
        (rootOfCard).addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(4), 0, dp(6)) })
    }

    private fun body(text: String) {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 12f
        tv.setTextColor(getColor(com.cometx.browser.R.color.text_secondary))
        root.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp(8)) })
    }

    private fun row(a: Button, b: Button) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        lp.setMargins(0, dp(4), dp(8), 0)
        row.addView(a, lp)
        row.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)
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
            .setNeutralButton("Clear all") { _, _ -> memory.clearAll(); recreate() }
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()
}

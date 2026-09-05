package com.cometx.browser.ai

import android.content.Context
import android.content.SharedPreferences
import com.cometx.browser.security.SecureStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * SettingsRepository — the single source of truth for user configuration.
 * API keys are persisted only through SecureStore (Keystore-encrypted);
 * everything else lives in plain prefs (no secrets among them).
 *
 * Phase 2: modelMode(AUTO|MANUAL) per provider — AUTO is the default and the
 * normal user NEVER configures a model (§12/§22). Legacy per-role overrides
 * from v1.1.0 installations remain valid as ADVANCED overrides (§34 migration).
 */
open class SettingsRepository(context: Context, private val secure: SecureStore) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("cometx_settings", Context.MODE_PRIVATE)

    companion object {
        val ALL_PROVIDERS = listOf("groq", "openrouter", "huggingface", "custom")
    }

    // ---------- Providers ----------

    fun activeProviderId(): String = prefs.getString("active_provider", "groq") ?: "groq"
    fun setActiveProvider(id: String) = prefs.edit().putString("active_provider", id).apply()

    fun providerEnabled(id: String): Boolean =
        prefs.getBoolean("prov_enabled_$id", id == "groq" || id == "openrouter")

    fun setProviderEnabled(id: String, enabled: Boolean) =
        prefs.edit().putBoolean("prov_enabled_$id", enabled).apply()

    // ---------- Fallback chain (user-ordered priority) ----------

    fun chainOrder(): List<String> {
        val raw = prefs.getString("chain_order", null) ?: return listOf("groq", "openrouter", "huggingface", "custom")
        return try {
            val arr = JSONArray(raw)
            val ids = (0 until arr.length()).mapNotNull { i ->
                val s = arr.optString(i); s.takeIf { it in ALL_PROVIDERS }
            }.distinct()
            // append any provider missing from stored order
            ids + (ALL_PROVIDERS.filter { it !in ids })
        } catch (_: Exception) {
            listOf("groq", "openrouter", "huggingface", "custom")
        }
    }

    fun setChainOrder(order: List<String>) {
        val arr = JSONArray()
        for (id in order) arr.put(id)
        prefs.edit().putString("chain_order", arr.toString()).apply()
    }

    /** Moves a provider one position up (-1) or down (+1) in the chain. */
    fun moveInChain(id: String, dir: Int) {
        val order = chainOrder().toMutableList()
        val i = order.indexOf(id)
        val j = i + dir
        if (i < 0 || j < 0 || j >= order.size) return
        order[i] = order[j].also { order[j] = order[i] }
        setChainOrder(order)
    }

    /** Ordered ids of providers that are enabled AND configured (the live chain).
     *  Self-run/custom counts when either a base URL or a key is present. */
    fun liveChain(): List<String> = chainOrder().filter { id ->
        if (!providerEnabled(id)) return@filter false
        if (id == "custom") !baseUrl(id).isNullOrBlank() || !apiKey(id).isNullOrBlank()
        else !apiKey(id).isNullOrBlank()
    }

    /** Result of the last connectivity test for a provider ("ok|fail|timestamp|message"). */
    fun lastTest(id: String): String? = prefs.getString("lasttest_$id", null)

    fun setLastTest(id: String, ok: Boolean, message: String) {
        val v = "${if (ok) "ok" else "fail"}|${System.currentTimeMillis()}|${message.take(180)}"
        prefs.edit().putString("lasttest_$id", v).apply()
    }

    open fun apiKey(id: String): String? = secure.getString("apikey_$id")
    fun setApiKey(id: String, key: String) {
        if (key.isBlank()) secure.remove("apikey_$id") else secure.putString("apikey_$id", key)
    }

    fun baseUrl(id: String): String? = prefs.getString("baseurl_$id", null)
    fun setBaseUrl(id: String, url: String?) =
        prefs.edit().putString("baseurl_$id", url?.takeIf { it.isNotBlank() }).apply()

    // ---------- Model routing ----------

    enum class ModelMode { AUTO, MANUAL }

    /** AUTO (default): the app discovers, ranks and selects models itself. */
    fun modelMode(providerId: String): ModelMode =
        try {
            ModelMode.valueOf(prefs.getString("model_mode_$providerId", ModelMode.AUTO.name) ?: ModelMode.AUTO.name)
        } catch (_: Exception) { ModelMode.AUTO }

    fun setModelMode(providerId: String, mode: ModelMode) =
        prefs.edit().putString("model_mode_$providerId", mode.name).apply()

    /** ADVANCED override (MANUAL mode only). Never required for normal users. */
    fun modelFor(providerId: String, role: ModelRouter.Role): String? =
        prefs.getString("model_${providerId}_${role.name}", null)

    fun setModel(providerId: String, role: ModelRouter.Role, model: String) =
        prefs.edit().putString("model_${providerId}_${role.name}", model.takeIf { it.isNotBlank() }).apply()

    /** Migration (§34): v1.1.0 installs had implicit manual models; from v1.2.0
     *  they become optional Advanced overrides and AUTO drives selection. */
    fun runModeMigration() {
        if (prefs.getBoolean("migrated_v2", false)) return
        // nothing destructive: stored role models stay as Advanced values;
        // explicit AUTO is now the mode for every provider
        for (id in ALL_PROVIDERS) if (prefs.getString("model_mode_$id", null) == null) {
            setModelMode(id, ModelMode.AUTO)
        }
        prefs.edit().putBoolean("migrated_v2", true).apply()
    }

    // ---------- AI event log (§37 observability; never contains secrets) ----------

    fun appendAiLog(line: String) {
        val arr = prefs.getString("ai_log", null)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: JSONArray()
        val entry = JSONArray().put(System.currentTimeMillis()).put(line.take(240))
        arr.put(entry)
        while (arr.length() > 50) arr.remove(0)
        prefs.edit().putString("ai_log", arr.toString()).apply()
    }

    fun aiLog(): List<Pair<Long, String>> {
        val arr = prefs.getString("ai_log", null)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return emptyList()
        val out = ArrayList<Pair<Long, String>>(arr.length())
        for (i in 0 until arr.length()) {
            val e = arr.optJSONArray(i) ?: continue
            out.add(e.optLong(0) to e.optString(1))
        }
        return out
    }

    fun clearAiLog() = prefs.edit().remove("ai_log").apply()

    // ---------- Connection diagnostics (last Test & Enable checklist, §21) ----------

    fun lastDiagnostics(providerId: String): JSONObject? =
        prefs.getString("diag_$providerId", null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    fun setLastDiagnostics(providerId: String, report: JSONObject) =
        prefs.edit().putString("diag_$providerId", report.toString()).apply()

    // ---------- Agent behavior ----------

    fun maxSteps(): Int = prefs.getInt("max_steps", 24)
    fun setMaxSteps(n: Int) = prefs.edit().putInt("max_steps", n.coerceIn(4, 60)).apply()

    enum class VisionMode { AUTO, ALWAYS, OFF }

    fun visionMode(): VisionMode =
        VisionMode.valueOf(prefs.getString("vision_mode", VisionMode.AUTO.name) ?: VisionMode.AUTO.name)

    fun setVisionMode(m: VisionMode) = prefs.edit().putString("vision_mode", m.name).apply()

    fun confirmHighRisk(): Boolean = prefs.getBoolean("confirm_high_risk", true)
    fun setConfirmHighRisk(v: Boolean) = prefs.edit().putBoolean("confirm_high_risk", v).apply()

    fun memoryEnabled(): Boolean = prefs.getBoolean("memory_enabled", true)
    fun setMemoryEnabled(v: Boolean) = prefs.edit().putBoolean("memory_enabled", v).apply()

    /** Skill replay: when selectors miss, may the model re-locate the element? */
    fun skillAiFallback(): Boolean = prefs.getBoolean("skill_ai_fallback", true)
    fun setSkillAiFallback(v: Boolean) = prefs.edit().putBoolean("skill_ai_fallback", v).apply()

    /** Set-of-Marks (v1.5.0): number visible elements on agent screenshots. */
    fun somOverlay(): Boolean = prefs.getBoolean("som_overlay", true)
    fun setSomOverlay(v: Boolean) = prefs.edit().putBoolean("som_overlay", v).apply()

    fun thirdPartyCookies(): Boolean = prefs.getBoolean("third_party_cookies", false)
    fun setThirdPartyCookies(v: Boolean) = prefs.edit().putBoolean("third_party_cookies", v).apply()

    // ---------- Browser ----------

    fun homepage(): String = prefs.getString("homepage", "https://www.google.com") ?: "https://www.google.com"
    fun setHomepage(url: String) = prefs.edit().putString("homepage", url).apply()

    /** Port of the embedded local test-page server; 0 = disabled. */
    fun testServerPort(): Int = prefs.getInt("test_server_port", 0)
    fun setTestServerPort(port: Int) = prefs.edit().putInt("test_server_port", port).apply()
}

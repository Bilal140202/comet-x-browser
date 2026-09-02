package com.cometx.browser.ai

import android.content.Context
import android.content.SharedPreferences
import com.cometx.browser.security.SecureStore
import org.json.JSONArray

/**
 * SettingsRepository — the single source of truth for user configuration.
 * API keys are persisted only through SecureStore (Keystore-encrypted);
 * everything else lives in plain prefs (no secrets among them).
 */
class SettingsRepository(context: Context, private val secure: SecureStore) {

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

    fun apiKey(id: String): String? = secure.getString("apikey_$id")
    fun setApiKey(id: String, key: String) {
        if (key.isBlank()) secure.remove("apikey_$id") else secure.putString("apikey_$id", key)
    }

    fun baseUrl(id: String): String? = prefs.getString("baseurl_$id", null)
    fun setBaseUrl(id: String, url: String?) =
        prefs.edit().putString("baseurl_$id", url?.takeIf { it.isNotBlank() }).apply()

    // ---------- Model routing ----------

    fun modelFor(providerId: String, role: ModelRouter.Role): String? =
        prefs.getString("model_${providerId}_${role.name}", null)

    fun setModel(providerId: String, role: ModelRouter.Role, model: String) =
        prefs.edit().putString("model_${providerId}_${role.name}", model.takeIf { it.isNotBlank() }).apply()

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

    fun thirdPartyCookies(): Boolean = prefs.getBoolean("third_party_cookies", false)
    fun setThirdPartyCookies(v: Boolean) = prefs.edit().putBoolean("third_party_cookies", v).apply()

    // ---------- Browser ----------

    fun homepage(): String = prefs.getString("homepage", "https://www.google.com") ?: "https://www.google.com"
    fun setHomepage(url: String) = prefs.edit().putString("homepage", url).apply()

    /** Port of the embedded local test-page server; 0 = disabled. */
    fun testServerPort(): Int = prefs.getInt("test_server_port", 0)
    fun setTestServerPort(port: Int) = prefs.edit().putInt("test_server_port", port).apply()
}

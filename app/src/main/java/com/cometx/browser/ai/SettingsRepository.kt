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
        // v2.2.0: "nvidia" joins the chain ADDITIVELY (position after openrouter).
        // chainOrder() appends it to stored orders from older installs, so no
        // migration is needed and pre-2.2.0 behavior is preserved when unused.
        val ALL_PROVIDERS = listOf("groq", "openrouter", "nvidia", "huggingface", "custom")

        /** v1.6.0: the on-device llama.cpp provider lives OUTSIDE ALL_PROVIDERS —
         *  it is never key-configured; it is ready when a model file is downloaded. */
        const val LOCAL_PROVIDER_ID = "local"

        /** v2.1.0: the in-browser Transformers.js provider — also outside
         *  ALL_PROVIDERS; ready when a web model is selected in Settings. */
        const val WEB_PROVIDER_ID = "webtransformers"
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
        val raw = prefs.getString("chain_order", null)
            ?: return listOf("groq", "openrouter", "nvidia", "huggingface", "custom")
        return try {
            val arr = JSONArray(raw)
            val ids = (0 until arr.length()).mapNotNull { i ->
                val s = arr.optString(i); s.takeIf { it in ALL_PROVIDERS }
            }.distinct()
            // append any provider missing from stored order (v2.2.0: "nvidia")
            ids + (ALL_PROVIDERS.filter { it !in ids })
        } catch (_: Exception) {
            listOf("groq", "openrouter", "nvidia", "huggingface", "custom")
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

    // ---------- Discord agent-results push (v2.2.0, additive) ----------
    // The bot token lives in SecureStore (same protection as provider keys);
    // the channel id is not a secret and lives in plain prefs.

    /** Push a result embed when a background agent task reaches COMPLETED/FAILED. */
    fun discordEnabled(): Boolean = prefs.getBoolean("discord_enabled", false)
    fun setDiscordEnabled(v: Boolean) = prefs.edit().putBoolean("discord_enabled", v).apply()

    fun discordChannelId(): String = prefs.getString("discord_channel_id", "") ?: ""
    fun setDiscordChannelId(v: String) =
        prefs.edit().putString("discord_channel_id", v.trim().takeIf { it.isNotBlank() } ?: "").apply()

    open fun discordToken(): String? = secure.getString("discord_bot_token")
    fun setDiscordToken(token: String) {
        if (token.isBlank()) secure.remove("discord_bot_token")
        else secure.putString("discord_bot_token", token.trim())
    }

    fun discordConfigured(): Boolean =
        discordEnabled() && !discordToken().isNullOrBlank() && discordChannelId().isNotBlank()

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

    // ---------- On-device AI (v1.6.0, additive) ----------

    /** Local-first mode: when ON, the on-device model is tried BEFORE cloud providers. */
    fun localAiPreferred(): Boolean = prefs.getBoolean("local_ai_preferred", false)
    fun setLocalAiPreferred(v: Boolean) = prefs.edit().putBoolean("local_ai_preferred", v).apply()

    /** Last activated local model id (catalog id or "imported:<file>"). */
    fun localModelId(): String? = prefs.getString("local_model_id", null)
    fun setLocalModelId(id: String?) = prefs.edit().putString("local_model_id", id).apply()

    /** v2.1.0: selected in-browser transformer model (HF repo id), or null. */
    fun webModelId(): String? = prefs.getString("web_model_id", null)
    fun setWebModelId(id: String?) = prefs.edit().putString("web_model_id", id?.takeIf { it.isNotBlank() }).apply()

    /** Runtime context window for the local model (clamped at use to ≤4096). */
    fun localContext(): Int = prefs.getInt("local_context", 4096)
    fun setLocalContext(n: Int) = prefs.edit().putInt("local_context", n.coerceIn(1024, 8192)).apply()

    /** Inference threads; 0 = auto (physical performance cores via sysfs). */
    fun localThreads(): Int = prefs.getInt("local_threads", 0)
    fun setLocalThreads(n: Int) = prefs.edit().putInt("local_threads", n.coerceIn(0, 8)).apply()

    /** Idle minutes before the local model is unloaded (0 = never). */
    fun localUnloadMin(): Int = prefs.getInt("local_unload_min", 10)
    fun setLocalUnloadMin(n: Int) = prefs.edit().putInt("local_unload_min", n.coerceIn(0, 240)).apply()

    // ---------- Browser ----------

    fun homepage(): String = prefs.getString("homepage", "https://www.google.com") ?: "https://www.google.com"
    fun setHomepage(url: String) = prefs.edit().putString("homepage", url).apply()

    /** Port of the embedded local test-page server; 0 = disabled. */
    fun testServerPort(): Int = prefs.getInt("test_server_port", 0)
    fun setTestServerPort(port: Int) = prefs.edit().putInt("test_server_port", port).apply()

    // ---------- Browsing & privacy (v2.0.0, additive) ----------
    // Defaults are privacy-first; every getter tolerates a missing key so old
    // installs keep working. Keys are NEW — no F-11 key is renamed or removed.

    /** Network-level ad/tracker blocking (hosts list + URL pattern rules). */
    fun blockAds(): Boolean = prefs.getBoolean("block_ads", true)
    fun setBlockAds(v: Boolean) = prefs.edit().putBoolean("block_ads", v).apply()

    /** Cosmetic (element-hiding) filtering. */
    fun blockCosmetic(): Boolean = prefs.getBoolean("block_cosmetic", true)
    fun setBlockCosmetic(v: Boolean) = prefs.edit().putBoolean("block_cosmetic", v).apply()

    /** YouTube ad suppression (prune-before-load + auto-skip). */
    fun youtubeSuppress(): Boolean = prefs.getBoolean("youtube_suppress", true)
    fun setYoutubeSuppress(v: Boolean) = prefs.edit().putBoolean("youtube_suppress", v).apply()

    /** DNT + Sec-GPC headers on main-frame navigations. */
    fun privacyHeaders(): Boolean = prefs.getBoolean("privacy_headers", true)
    fun setPrivacyHeaders(v: Boolean) = prefs.edit().putBoolean("privacy_headers", v).apply()

    /** HTTPS-first main-frame upgrades (local hosts skipped). */
    fun httpsUpgrade(): Boolean = prefs.getBoolean("https_upgrade", true)
    fun setHttpsUpgrade(v: Boolean) = prefs.edit().putBoolean("https_upgrade", v).apply()

    /** First-party cookies (third-party governed by thirdPartyCookies()). */
    fun cookiesEnabled(): Boolean = prefs.getBoolean("cookies", true)
    fun setCookiesEnabled(v: Boolean) = prefs.edit().putBoolean("cookies", v).apply()

    /** Media autoplay without a user gesture (default OFF). */
    fun mediaAutoplay(): Boolean = prefs.getBoolean("media_autoplay", false)
    fun setMediaAutoplay(v: Boolean) = prefs.edit().putBoolean("media_autoplay", v).apply()

    /** Pull-to-refresh gesture. */
    fun pullToRefresh(): Boolean = prefs.getBoolean("pull_to_refresh", true)
    fun setPullToRefresh(v: Boolean) = prefs.edit().putBoolean("pull_to_refresh", v).apply()

    /** Force-enable zoom (viewport rewrite at document start). */
    fun forceZoom(): Boolean = prefs.getBoolean("force_zoom", false)
    fun setForceZoom(v: Boolean) = prefs.edit().putBoolean("force_zoom", v).apply()

    /** Web text zoom percent (WebSettings.setTextZoom); clamped 50..200. */
    fun textZoom(): Int = prefs.getInt("text_zoom", 100)
    fun setTextZoom(v: Int) = prefs.edit().putInt("text_zoom", v.coerceIn(50, 200)).apply()

    /** Selected search engine: index into SearchEngines.allEngines() (0 = Google). */
    fun searchEngine(): Int = prefs.getInt("search_engine", 0)
    fun setSearchEngine(v: Int) = prefs.edit().putInt("search_engine", v).apply()

    /** Custom search engines JSON (SearchEngines format). */
    fun customEngines(): String = prefs.getString("custom_engines", "") ?: ""
    fun setCustomEngines(v: String) = prefs.edit().putString("custom_engines", v).apply()

    /** Start-page shortcut tiles JSON ({"name","url"} array; empty = automatic). */
    fun homeTiles(): String = prefs.getString("home_tiles", "") ?: ""
    fun setHomeTiles(v: String) = prefs.edit().putString("home_tiles", v).apply()

    /** App theme: 0 system, 1 light, 2 dark. */
    fun appTheme(): Int = prefs.getInt("app_theme", 0)
    fun setAppTheme(v: Int) = prefs.edit().putInt("app_theme", v.coerceIn(0, 2)).apply()

    /** Material You dynamic color (Android 12+); fallback is the Comet palette. */
    fun materialYou(): Boolean = prefs.getBoolean("material_you", true)
    fun setMaterialYou(v: Boolean) = prefs.edit().putBoolean("material_you", v).apply()

    /** Algorithmic darkening for web content (WebView algorithmic theme). */
    fun webForceDark(): Boolean = prefs.getBoolean("web_force_dark", false)
    fun setWebForceDark(v: Boolean) = prefs.edit().putBoolean("web_force_dark", v).apply()

    /** Weekly automatic filter-list refresh. */
    fun autoUpdateLists(): Boolean = prefs.getBoolean("auto_update_lists", true)
    fun setAutoUpdateLists(v: Boolean) = prefs.edit().putBoolean("auto_update_lists", v).apply()

    /** Last successful filter-list update (ms epoch; 0 = never). */
    fun listLastUpdate(): Long = prefs.getLong("list_last_update", 0L)
    fun setListLastUpdate(v: Long) = prefs.edit().putLong("list_last_update", v).apply()

    /** Per-site blocking exemption: newline separated hosts. */
    fun allowlist(): String = prefs.getString("blocking_allowlist", "") ?: ""
    fun setAllowlist(v: String) = prefs.edit().putString("blocking_allowlist", v ?: "").apply()

    /** All-time blocked-request counter (feed the stat card + security dialog). */
    fun totalBlocked(): Long = prefs.getLong("total_blocked", 0L)
    fun addTotalBlocked(n: Long) {
        if (n <= 0) return
        prefs.edit().putLong("total_blocked", totalBlocked() + n).apply()
    }

    fun resetTotalBlocked() = prefs.edit().putLong("total_blocked", 0L).apply()

    /** Restored session: "||"-separated non-incognito tab URLs + current index. */
    fun savedTabs(): String = prefs.getString("saved_tabs", "") ?: ""
    fun setSavedTabs(v: String) = prefs.edit().putString("saved_tabs", v ?: "").apply()
    fun savedTabIndex(): Int = prefs.getInt("saved_tab_index", 0)
    fun setSavedTabIndex(v: Int) = prefs.edit().putInt("saved_tab_index", v).apply()

    /** True when the user picked a homepage other than the untouched default. */
    fun hasCustomHomepage(): Boolean {
        val v = prefs.getString("homepage", null) ?: return false
        return v.isNotBlank() && v != "https://www.google.com"
    }
}

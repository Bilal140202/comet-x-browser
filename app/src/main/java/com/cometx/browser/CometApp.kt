package com.cometx.browser

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.cometx.browser.ai.SettingsRepository
import com.cometx.browser.ai.local.LocalModelManager
import com.cometx.browser.background.BackgroundAgentStore
import com.cometx.browser.browse.AdBlocker
import com.cometx.browser.browse.CosmeticFilter
import com.cometx.browser.security.SecureStore
import java.io.File

class CometApp : Application() {
    lateinit var localAI: LocalModelManager
        private set

    /** v1.8.0: single source of truth for the background agent task. */
    lateinit var agentStore: BackgroundAgentStore
        private set

    /**
     * v2.0.0: app-scoped blocking engine — one domain set shared by the
     * browser tabs, the background agent's headless WebView and Settings.
     * Loads off the main thread; `ready` flips when the list is parsed.
     */
    lateinit var adBlocker: AdBlocker
        private set

    override fun onCreate() {
        super.onCreate()
        app = this

        // v2.0.0: app theme (system/light/dark) honored from the very first frame
        val settings = SettingsRepository(this, SecureStore(this))
        applyTheme(settings.appTheme())

        // v1.6.0 on-device AI (llama.cpp). Safe on every device: when the native
        // runtime is unavailable (x86 emulator etc.) the manager no-ops and the
        // provider drops out of the router chain — zero behavioral change.
        localAI = LocalModelManager(this)
        localAI.startIdleWatchdog()
        localAI.autoReloadIfPreferred()
        // v1.7.0: surface downloads that are queued in WorkManager's persistent
        // queue (process death / reboot while a background download was active)
        localAI.reconcileQueuedWork()
        // v1.8.0: background agent store — reconcile a task orphaned by a
        // reboot or a system kill into INTERRUPTED (no receiver runs at boot,
        // nothing auto-resumes, nothing can crash-loop)
        agentStore = BackgroundAgentStore(File(filesDir, "agent"))
        agentStore.reconcileInterrupted()

        // v2.0.0: blocklist load (updated download preferred over bundled asset)
        adBlocker = AdBlocker()
        adBlocker.rebuildAllowlist(settings.allowlist())
        adBlocker.loadAsync(filesDir) {
            assets.open(AdBlocker.ASSET_FILE).bufferedReader().use { it.readLines() }
        }
        // cosmetic rules cache is invalidated whenever lists update (Settings)
        runCatching { CosmeticFilter.invalidate() }

        // v2.0.0: wire optional network blocking into the background agent's
        // headless WebView (same engine, same user setting — additive)
        com.cometx.browser.background.HeadlessWebViewFactory.networkBlock = { url ->
            settings.blockAds() && adBlocker.isReady() &&
                adBlocker.shouldBlock(url, null)
        }
    }

    private fun applyTheme(mode: Int) {
        AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                1 -> AppCompatDelegate.MODE_NIGHT_NO
                2 -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    companion object {
        lateinit var app: CometApp
            private set
    }
}

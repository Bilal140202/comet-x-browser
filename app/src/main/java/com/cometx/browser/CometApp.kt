package com.cometx.browser

import android.app.Application
import com.cometx.browser.ai.local.LocalModelManager
import com.cometx.browser.background.BackgroundAgentStore
import java.io.File

class CometApp : Application() {
    lateinit var localAI: LocalModelManager
        private set

    /** v1.8.0: single source of truth for the background agent task. */
    lateinit var agentStore: BackgroundAgentStore
        private set

    override fun onCreate() {
        super.onCreate()
        app = this
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
    }

    companion object {
        lateinit var app: CometApp
            private set
    }
}

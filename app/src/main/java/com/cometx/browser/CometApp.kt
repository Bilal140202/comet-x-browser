package com.cometx.browser

import android.app.Application
import com.cometx.browser.ai.local.LocalModelManager

class CometApp : Application() {
    lateinit var localAI: LocalModelManager
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
    }

    companion object {
        lateinit var app: CometApp
            private set
    }
}

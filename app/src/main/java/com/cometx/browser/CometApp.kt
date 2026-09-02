package com.cometx.browser

import android.app.Application

class CometApp : Application() {
    override fun onCreate() {
        super.onCreate()
        app = this
    }

    companion object {
        lateinit var app: CometApp
            private set
    }
}

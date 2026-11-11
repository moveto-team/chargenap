package com.chargenap.app

import android.app.Application

class ChargeNapApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this).also { it.start() }
    }
}

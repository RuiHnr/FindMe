package com.ruirui.orbit.android.app

import android.app.Application

class OrbitApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        container = AppContainer(this)
    }

    companion object {
        lateinit var instance: OrbitApp
            private set
    }
}
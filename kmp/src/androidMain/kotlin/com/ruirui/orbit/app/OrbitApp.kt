package com.ruirui.orbit.app

import android.app.Application
import com.ruirui.orbit.repository.LocationRepository
import com.ruirui.orbit.sync.SyncOrchestrator

class OrbitApp : Application() {

    lateinit var locationRepository: LocationRepository
    lateinit var syncOrchestrator: SyncOrchestrator

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: OrbitApp
        private set
    }
}
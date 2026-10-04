package com.ruirui.findme.app

import android.app.Application
import com.ruirui.findme.repository.LocationRepository
import com.ruirui.findme.sync.SyncOrchestrator

class FindMeApp : Application() {

    lateinit var locationRepository: LocationRepository
    lateinit var syncOrchestrator: SyncOrchestrator

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: FindMeApp
        private set
    }
}
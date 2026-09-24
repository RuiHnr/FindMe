package com.ruirui.findme.sync

import com.ruirui.findme.network.api.PresenceApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * Manages the user's "actively watching" presence with the backend.
 *
 * When started, sends a heartbeat every HEARTBEAT_INTERVAL_MS milliseconds
 * so the server knows this user has the app open. The backend triggers
 * silent pushes to friends to wake up their GPS.
 *
 * Call [start] when the map screen becomes visible.
 * Call [stop] when the app backgrounds or navigates away from the map.
 */
class PresenceManager(
    private val presenceApi: PresenceApi,
    private val scope: CoroutineScope
) {

    companion object {
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
    }
    private var heartbeatJob: Job? = null

    fun start() {
        if (heartbeatJob?.isActive == true) return

        heartbeatJob = scope.launch {
            while (isActive) {
                presenceApi.heartbeat()
                delay(HEARTBEAT_INTERVAL_MS.milliseconds)
            }
        }
    }

    fun stop() {
        heartbeatJob?.cancel()
        heartbeatJob = null

        scope.launch {
            presenceApi.remove()
        }
    }
}
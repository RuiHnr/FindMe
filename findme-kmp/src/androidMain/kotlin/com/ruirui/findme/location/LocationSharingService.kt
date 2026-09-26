package com.ruirui.findme.location

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.location.Location
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.ruirui.findme.repository.LocationRepository
import com.ruirui.findme.sync.SyncOrchestrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Android Foreground Service that continuously shares the user's location with friends.
 *
 * Operates in two modes, switched dynamically via Intents from the Push Receiver:
 *
 * - **HIGH mode**: GPS every 5 seconds via FusedLocationProviderClient time-based interval.
 * - **LOW mode**: 5-minute interval with balanced accuracy (cell/WiFi, no GPS hardware).
 *
 * Note: Android's FusedLocationProviderClient supports time-based intervals natively.
 * iOS uses distance-based triggers instead — see LocationSharingManager.swift.
 */
class LocationSharingService : Service() {
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var currentMode: Mode = Mode.LOW
    private var lastSentLocation: Location? = null

    private enum class Mode { HIGH, LOW }

    companion object {
        private const val CHANNEL_ID = "findme_location_channel"
        private const val NOTIFICATION_ID = 1
        private const val HIGH_INTERVAL_MS = 5_000L
        private const val LOW_INTERVAL_MS = 300_000L  // 5 minutes
        private const val DISPLACEMENT_THRESHOLD_METERS = 5f
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        startForegroundNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val forcedMode = intent?.getStringExtra("mode")
        val targetMode = if (forcedMode == "HIGH") Mode.HIGH else Mode.LOW

        if (targetMode != currentMode || locationCallback == null) {
            TODO("startLocationUpdates()")
        }
    }

    private fun startForegroundNotification() {
        val channel = NotificationChannel(CHANNEL_ID, "FindMe Location Sharing",
            NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FindMe is sharing your location")
            .setContentTitle("Your friends can see where you are")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun startLocationUpdates(mode: Mode) {
        locationCallback?.let { fusedLocationClient.removeLocationUpdates { it } }

        val (priority, interval) = when (mode) {
            Mode.HIGH -> Priority.PRIORITY_HIGH_ACCURACY to HIGH_INTERVAL_MS
            Mode.LOW -> Priority.PRIORITY_BALANCED_POWER_ACCURACY to LOW_INTERVAL_MS
        }

        val request = LocationRequest.Builder(priority, interval).build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                handleNewLocation(location)
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
            locationCallback = callback
            currentMode = mode
        } catch (e: SecurityException) {
            Log.e("FindMe", "Lost location permissions", e)
        }
    }

    private fun handleNewLocation(location: Location) {
        val last = lastSentLocation
        if (last != null && last.distanceTo(location) < DISPLACEMENT_THRESHOLD_METERS) {
            return
        }
        lastSentLocation = location

        val repo: LocationRepository
        val orchestrator: SyncOrchestrator

        serviceScope.launch {
            repo.submitLocalLocation(location.latitude, location.longitude)
            orchestrator.syncInboxOnly()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
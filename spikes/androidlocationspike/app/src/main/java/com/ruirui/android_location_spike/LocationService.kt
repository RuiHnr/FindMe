package com.ruirui.android_location_spike

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
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

class LocationService : Service() {

    // connection to hardware
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    // gets called when service is started for the first time
    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

    }

    // Gets called every time the service gets started
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("FindMe", "Service onStartCommand called")

        // 1. Create Notification and make the service "unkillable"
        startForegroundService()

        // 2. Start the actual location tracking
        startLocationUpdates()

        // STICKY: if the system kills us cuz it is low on RAM, it restarts us as soon as RAM is back
        return START_STICKY
    }

    private fun startForegroundService() {
        val channelId = "findme_location_channel"

        // Starting from Android 8.0, notifications must be posted on a channel
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "FindMe Background Tracking",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        // Construct the notification
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("FindMe Spike is running")
            .setContentText("Your location is being tracked efficiently")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()

        // promote us from normal service to foreground service!
        startForeground(1, notification)
    }

    private fun startLocationUpdates() {

        // configure how we track
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            10000 // Interval: every 10s an update
        ).build()

        // what happens when a new location is available
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                super.onLocationResult(locationResult)
                for (location in locationResult.locations) {
                    Log.d("FindMe", "New Location: Lat: ${location.latitude}, Lng: ${location.longitude}")
                }
            }
        }

        // Security Check: Do we have the permissions?
        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
            Log.d("FindMe", "Location Updates requested successfully")
        } catch (unlikely: SecurityException) {
            Log.e("FindMe", "Lost location permissions. Couldn't request updates. $unlikely")
        }
    }

    // Gets called when the service is stopped
    override fun onDestroy() {
        super.onDestroy()
        Log.d("FindMe", "Service onDestroy called, stopping Updates.")
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    // only needed for bound services (not used here)
    override fun onBind(p0: Intent?): IBinder? {
        return null
    }

}
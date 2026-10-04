package com.ruirui.findme.location

import android.content.Intent
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.ruirui.findme.models.DeviceToken
import com.ruirui.findme.network.api.DeviceApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch


/**
 * Service for handling Firebase Cloud Messaging (FCM) push notifications.
 *
 * Responsible for:
 * 1. Receiving new FCM registration tokens
 * 2. Handling incoming push notifications
 * 3. Triggering location sharing mode changes
 *
 * @property deviceApi Interface for device token registration
 */
class FindMeMessagingService(
    private val deviceApi: DeviceApi
) : FirebaseMessagingService() {

    /**
     * Recognized sync modes for location sharing
     */
    object SyncMode {
        /** Frequent location updates (when friends are actively watching) */
        const val HIGH = "high"

        /** Less frequent updates (when no friends are watching) */
        const val LOW = "low"
    }

    /**
     * Called when a new FCM registration token is generated.
     *
     * @param token The new FCM token
     */
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // Since Firebase's onNewToken is synchronous, launch a coroutine to send the token
        CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                deviceApi.postTokens(DeviceToken(
                    fcmToken = token,
                    apnsToken = null
                ))
            } catch (e: Exception) {
                Log.e("FindMe", "Failed to send FCM token", e)
            }
        }
    }

    /**
     * Called when a new FCM message is received.
     *
     * @param message The received FCM message
     */
    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["action"] == "sync") {
            // If it's a sync mode sync notification
            if (message.data["mode"] == SyncMode.HIGH || message.data["mode"] == SyncMode.LOW) {
                val intent = Intent(this, LocationSharingService::class.java).apply {
                    putExtra("mode", message.data["mode"])
                }
                startForegroundService(intent)
            }
        }
    }
}
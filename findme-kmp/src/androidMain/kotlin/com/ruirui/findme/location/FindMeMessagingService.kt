package com.ruirui.findme.location

import android.content.Intent
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage


class FindMeMessagingService : FirebaseMessagingService() {

    object SyncMode {
        const val HIGH = "high"
        const val LOW = "low"
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        TODO("Send token to backend via DeviceApi")
    }

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
package com.ruirui.findme.location

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
            // If it's a high mode sync notification
            if (message.data["mode"] == SyncMode.HIGH) {
                TODO("Set LocationSharingService in HIGH mode")
            }
            else if (message.data["mode"] == SyncMode.LOW) {
                TODO("Set LocationSharingService in LOW mode")
            }
        }
    }
}
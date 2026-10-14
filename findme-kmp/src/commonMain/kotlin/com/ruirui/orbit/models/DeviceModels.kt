package com.ruirui.orbit.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DeviceToken(
    @SerialName("fcm_token")
    val fcmToken: String?,
    @SerialName("apns_token")
    val apnsToken: String?
)
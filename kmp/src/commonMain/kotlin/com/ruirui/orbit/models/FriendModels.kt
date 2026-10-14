package com.ruirui.orbit.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class RequestDirection {
    @SerialName("inbound")
    INBOUND,
    @SerialName("outbound")
    OUTBOUND
}

@Serializable
data class FriendDto(
    @SerialName("user_id")
    val userId: String,
    val username: String = "",
    @SerialName("identity_key_dh")
    val identityKeyDh: String = "",
    @SerialName("identity_key_sign")
    val identityKeySign: String = ""
)

/**
 * Payload sent by an authenticated user to initiate a friend request by username,
 * or received when syncing pending friend requests.
 */
@Serializable
data class FriendRequest(
    @SerialName("target_username")
    val targetUsername: String,
    val direction: RequestDirection = RequestDirection.INBOUND
)


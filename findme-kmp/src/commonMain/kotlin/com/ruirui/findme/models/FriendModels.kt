package com.ruirui.findme.models

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
    val userId: String,
    val username: String = ""
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

/**
 * Payload sent by a user to accept a pending friend request from a requester UUID.
 */
@Serializable
data class FriendAcceptPayload(
    @SerialName("requester_id")
    val requesterId: String
)

package com.ruirui.findme.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Payload sent by an authenticated user to initiate a friend request by username.
 */
@Serializable
data class FriendRequest(
    @SerialName("target_username")
    val targetUsername: String
)

/**
 * Payload sent by a user to accept a pending friend request from a requester UUID.
 */
@Serializable
data class FriendAcceptPayload(
    @SerialName("requester_id")
    val requesterId: String
)

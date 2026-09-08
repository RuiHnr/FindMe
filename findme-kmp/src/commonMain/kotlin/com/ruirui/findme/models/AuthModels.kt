package com.ruirui.findme.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Request sent by the client to register a new user on the backend.
 *
 * @param username The unique username chosen by the user.
 * @param pubKey The user's long-term Signal Identity Public Key (Base64 encoded).
 */
@Serializable
data class RegisterRequest(
    val username: String,
    @SerialName("pub_key")
    val pubKey: String
)

/**
 * Response returned by the server upon successful user registration.
 *
 * @param userId The auto-generated UUID assigned to the registered user.
 * @param token The signed JWT Bearer authentication token.
 */
@Serializable
data class RegisterResponse(
    @SerialName("user_id")
    val userId: String,
    val token: String
)

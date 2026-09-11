package com.ruirui.findme.network.api

import com.ruirui.findme.models.FriendRequest
import com.ruirui.findme.network.safeApiCall
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode

/**
 * Handles sending and accepting friend requests.
 */
class FriendsApi(private val client: HttpClient) {

    /**
     * Sends a new friend request to another user.
     * 
     * @param request The target user's details.
     * @return [Result] containing the HTTP status code on success.
     */
    suspend fun request(request: FriendRequest): Result<HttpStatusCode> {
        return safeApiCall {
            client.post("/friends/requests") {
                setBody(request)
            }
        }
    }

    /**
     * Accepts a pending friend request.
     * 
     * @param requesterId The UUID of the user who sent the friend request.
     * @return [Result] containing the HTTP status code on success.
     */
    suspend fun accept(requesterId: String): Result<HttpStatusCode> {
        return safeApiCall {
            client.put("/friends/requests/$requesterId/accept")
        }
    }
}
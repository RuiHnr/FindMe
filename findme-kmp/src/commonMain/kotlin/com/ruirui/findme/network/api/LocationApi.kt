package com.ruirui.findme.network.api

import com.ruirui.findme.models.InboxMessage
import com.ruirui.findme.models.SubmitLocationRequest
import com.ruirui.findme.network.safeApiCall
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode

/**
 * Handles sending encrypted locations and fetching incoming location updates.
 */
class LocationApi(private val client: HttpClient) {

    /**
     * Fetches and consumes all pending location messages queued in the user's inbox.
     * 
     * @param receiverId The authenticated user's UUID.
     * @return [Result] containing a list of pending [InboxMessage]s.
     */
    suspend fun getInbox(receiverId: String): Result<List<InboxMessage>> {
        return safeApiCall {
            client.get("/inbox/$receiverId")
        }
    }

    /**
     * Submits an encrypted location payload to a friend's inbox.
     * 
     * @param request The encrypted location blob and the recipient's ID.
     * @return [Result] containing the HTTP status code on success.
     */
    suspend fun submitLocation(request: SubmitLocationRequest): Result<HttpStatusCode> {
        return safeApiCall {
            client.post("/inbox") {
                setBody(request)
            }
        }
    }
}
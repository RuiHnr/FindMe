package com.ruirui.findme.network.api

import com.ruirui.findme.models.InboxMessage
import com.ruirui.findme.models.InboxResponse
import com.ruirui.findme.models.SubmitMessageRequest
import com.ruirui.findme.network.safeApiCall
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.http.isSuccess
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody

/**
 * Handles sending encrypted locations and fetching incoming location updates.
 */
class LocationApi(private val client: HttpClient) {

    /**
     * Fetches and consumes all pending location messages queued in the user's inbox.
     * @return [Result] containing the [InboxResponse].
     */
    suspend fun getInbox(): Result<InboxResponse> {
        return try {
            val response = client.get("/inbox")
            if (response.status.isSuccess()) {
                val messages = response.body<List<InboxMessage>>()
                val remainingKeys = response.headers["X-Remaining-PreKeys"]?.toIntOrNull()
                Result.success(InboxResponse(messages, remainingKeys))
            } else {
                Result.failure(Exception("HTTP Error: ${response.status.value} - ${response.status.description}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Submits an encrypted location payload to a friend's inbox.
     * 
     * @param request The encrypted location blob and the recipient's ID.
     * @return [Result] containing the HTTP status code on success.
     */
    suspend fun submitMessage(request: SubmitMessageRequest): Result<Unit> {
        return safeApiCall {
            client.post("/inbox") {
                setBody(request)
            }
        }
    }
}
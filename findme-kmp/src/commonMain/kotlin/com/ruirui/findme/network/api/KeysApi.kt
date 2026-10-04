package com.ruirui.findme.network.api

import com.ruirui.findme.models.KeyCountResponse
import com.ruirui.findme.models.PreKeyBundleResponse
import com.ruirui.findme.models.UploadKeysRequest
import com.ruirui.findme.network.safeApiCall
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode

/**
 * Handles Signal Protocol key distribution and management.
 */
class KeysApi(private val client: HttpClient) {

    /**
     * Uploads the user's initial or replenished pre-keys to the server.
     * 
     * @param request The bundle of signed and one-time pre-keys.
     * @return [Result] containing the HTTP status code on success.
     */
    suspend fun upload(request: UploadKeysRequest): Result<HttpStatusCode> {
        return safeApiCall {
            client.post("/keys") {
                setBody(request)
            }
        }
    }

    /**
     * Fetches the number of remaining one-time pre-keys currently stored on the server.
     * 
     * @return [Result] containing the remaining count.
     */
    suspend fun getKeyCount(): Result<KeyCountResponse> {
        return safeApiCall {
            client.get("/keys/count")
        }
    }

    /**
     * Retrieves a pre-key bundle for a target user to establish a new Signal session.
     * 
     * @param userId The target user's UUID.
     * @return [Result] containing the pre-key bundle for the user.
     */
    suspend fun getPreKeyBundle(userId: String): Result<PreKeyBundleResponse> {
        return safeApiCall {
            client.get("/keys/$userId")
        }
    }
}
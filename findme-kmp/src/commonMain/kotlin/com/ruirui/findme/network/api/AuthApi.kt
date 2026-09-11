package com.ruirui.findme.network.api

import com.ruirui.findme.models.RegisterRequest
import com.ruirui.findme.models.RegisterResponse
import com.ruirui.findme.network.safeApiCall
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody

/**
 * Handles user authentication and registration API calls.
 */
class AuthApi(private val client: HttpClient) {

    /**
     * Registers a new user with their public key on the backend.
     * 
     * @param request The username and public key of the new user.
     * @return [Result] containing the generated User ID on success.
     */
    suspend fun register(request: RegisterRequest): Result<RegisterResponse> {
        return safeApiCall {
            client.post("/users/register") {
                setBody(request)
            }
        }
    }
}
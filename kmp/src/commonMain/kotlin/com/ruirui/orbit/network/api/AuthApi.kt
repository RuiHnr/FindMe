package com.ruirui.orbit.network.api

import com.ruirui.orbit.models.RegisterRequest
import com.ruirui.orbit.models.RegisterResponse
import com.ruirui.orbit.network.safeApiCall
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
    suspend fun register(request: RegisterRequest): Result<RegisterResponse> = safeApiCall {
        client.post("/users/register") {
            setBody(request)
        }
    }
}
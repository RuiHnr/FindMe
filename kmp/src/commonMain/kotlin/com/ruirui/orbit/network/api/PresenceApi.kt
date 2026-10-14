package com.ruirui.orbit.network.api

import com.ruirui.orbit.network.safeApiCall
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.post

interface PresenceApi {
    suspend fun heartbeat(): Result<Unit>
    suspend fun remove(): Result<Unit>
}

class HttpPresenceApi(private val client: HttpClient) : PresenceApi {

    override suspend fun heartbeat(): Result<Unit> = safeApiCall {
        client.post("/presence")
    }

    override suspend fun remove(): Result<Unit> = safeApiCall {
        client.delete("/presence")
    }
}
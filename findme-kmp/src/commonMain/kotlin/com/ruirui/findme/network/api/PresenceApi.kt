package com.ruirui.findme.network.api

import com.ruirui.findme.network.safeApiCall
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.post

class PresenceApi(private val client: HttpClient) {

    suspend fun heartbeat(): Result<Unit> = safeApiCall {
        client.post("/presence")
    }

    suspend fun remove(): Result<Unit> = safeApiCall {
        client.delete("/presence")
    }
}
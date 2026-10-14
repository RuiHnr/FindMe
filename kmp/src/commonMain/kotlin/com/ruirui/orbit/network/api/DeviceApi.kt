package com.ruirui.orbit.network.api

import com.ruirui.orbit.models.DeviceToken
import com.ruirui.orbit.network.safeApiCall
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody

class DeviceApi(private val client: HttpClient) {

    suspend fun postTokens(deviceToken: DeviceToken) : Result<Unit> {
        return safeApiCall {
            client.post("/device_token") {
                setBody(deviceToken)
            }
        }
    }
}
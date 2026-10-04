package com.ruirui.findme.network.api

import com.ruirui.findme.models.DeviceToken
import com.ruirui.findme.network.safeApiCall
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
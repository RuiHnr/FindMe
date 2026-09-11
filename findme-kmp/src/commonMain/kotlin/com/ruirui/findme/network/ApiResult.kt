package com.ruirui.findme.network

import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess

suspend inline fun <reified T> safeApiCall(
    apiCall: () -> HttpResponse
): Result<T> {
    return try {
        val response = apiCall()
        if (response.status.isSuccess()) {
            Result.success(response.body<T>())
        } else {
            Result.failure(Exception("HTTP Error: ${response.status.value} - ${response.status.description}"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

}
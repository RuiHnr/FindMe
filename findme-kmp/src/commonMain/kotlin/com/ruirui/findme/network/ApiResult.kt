package com.ruirui.findme.network

import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess

/**
 * A wrapper function for safely executing Ktor HTTP calls and parsing their responses.
 * Catches network exceptions and non-2xx status codes, mapping them into Kotlin's standard [Result] type.
 *
 * @param T The expected response body type to deserialize. Use [Unit] if no body is expected.
 * @param apiCall The suspend block containing the Ktor HTTP request.
 * @return [Result.success] containing the deserialized body if HTTP 2xx, otherwise [Result.failure].
 */
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
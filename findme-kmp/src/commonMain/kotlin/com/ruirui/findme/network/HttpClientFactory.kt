package com.ruirui.findme.network

import com.ruirui.findme.storage.SecureStorage
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Factory for creating configured Ktor HTTP clients across KMP targets.
 * Installs JSON content negotiation and the Auth plugin to proactively attach 
 * JWT Bearer tokens from SecureStorage on every outgoing request.
 */
object HttpClientFactory {

    fun create(secureStorage: SecureStorage, baseUrl: String): HttpClient {
        return HttpClient {
            applyConfig(secureStorage, baseUrl)
        }
    }

    // Overload for testing with MockEngine
    fun create(
        engine: io.ktor.client.engine.HttpClientEngine,
        secureStorage: SecureStorage,
        baseUrl: String
    ): HttpClient {
        return HttpClient(engine) {
            applyConfig(secureStorage, baseUrl)
        }
    }

    private fun HttpClientConfig<*>.applyConfig(secureStorage: SecureStorage, baseUrl: String) {
        defaultRequest {
            url(baseUrl)
            contentType(ContentType.Application.Json)
        }

        // Configure JSON Serialization
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                isLenient = true
                ignoreUnknownKeys = true
            })
        }

        // Configure JWT Bearer Authentication
        install(Auth) {
            bearer {
                loadTokens {
                    val token = secureStorage.getString(com.ruirui.findme.storage.SecureStorageKeys.AUTH_TOKEN)
                    if (token != null) {
                        BearerTokens(accessToken = token, refreshToken = "")
                    } else {
                        null
                    }
                }
                sendWithoutRequest { true }
            }
        }
    }
}


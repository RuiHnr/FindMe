package com.ruirui.findme.network

import com.ruirui.findme.storage.SecureStorage
import io.ktor.client.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

object HttpClientFactory {

    fun create(secureStorage: SecureStorage, baseUrl: String): HttpClient {
        return HttpClient {
            applyConfig(secureStorage, baseUrl)
        }
    }

    // Overload for testing with MockEngine
    fun create(engine: io.ktor.client.engine.HttpClientEngine, secureStorage: SecureStorage, baseUrl: String): HttpClient {
        return HttpClient(engine) {
            applyConfig(secureStorage, baseUrl)
        }
    }

    private fun io.ktor.client.HttpClientConfig<*>.applyConfig(secureStorage: SecureStorage, baseUrl: String) {
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
                    val token = secureStorage.getString("jwt_token")
                    if (token != null) {
                        BearerTokens(accessToken = token, refreshToken = "")
                    } else {
                        null
                    }
                }
            }
        }
    }
}


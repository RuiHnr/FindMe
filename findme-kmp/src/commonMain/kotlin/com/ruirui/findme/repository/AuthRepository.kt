@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package com.ruirui.findme.repository

import com.ruirui.findme.crypto.Crypto
import com.ruirui.findme.models.RegisterRequest
import com.ruirui.findme.network.api.AuthApi
import com.ruirui.findme.storage.SecureStorage
import com.ruirui.findme.storage.SecureStorageKeys.AUTH_TOKEN
import com.ruirui.findme.storage.SecureStorageKeys.IDENTITY_PRIVATE_KEY_DH
import com.ruirui.findme.storage.SecureStorageKeys.IDENTITY_PRIVATE_KEY_SIGN
import com.ruirui.findme.storage.SecureStorageKeys.IDENTITY_PUBLIC_KEY_DH
import com.ruirui.findme.storage.SecureStorageKeys.IDENTITY_PUBLIC_KEY_SIGN
import com.ruirui.findme.storage.SecureStorageKeys.USER_ID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64

sealed interface AuthState {
    data object Loading : AuthState
    data object Unauthenticated : AuthState
    data class Authenticated(val userId: String) : AuthState
}

interface AuthRepository {
    val authState: StateFlow<AuthState>
    suspend fun register(username: String): Result<Unit>
    suspend fun logout()
}

class AuthRepositoryImpl(
    private val apiClient: AuthApi,
    private val secureStorage: SecureStorage,
    private val crypto: Crypto,
    appScope: CoroutineScope
) : AuthRepository {
    private val _authState = MutableStateFlow<AuthState>(AuthState.Loading)
    override val authState: StateFlow<AuthState> = _authState.asStateFlow()

    init {
        appScope.launch {
            val token = secureStorage.getString(AUTH_TOKEN)
            val userId = secureStorage.getString(USER_ID)

            _authState.value = if (token != null && userId != null) {
                AuthState.Authenticated(userId)
            } else {
                AuthState.Unauthenticated
            }
        }
    }

    override suspend fun register(username: String): Result<Unit> = runCatching {
        // 1. Generate cryptographic identities
        val identityKeyPairDh = crypto.generateX25519KeyPair()
        val identityKeyPairSign = crypto.generateEd25519KeyPair()

        val publicKeyDhBase64 = Base64.encode(identityKeyPairDh.publicKey)
        val privateKeyDhBase64 = Base64.encode(identityKeyPairDh.privateKey)

        val publicKeySignBase64 = Base64.encode(identityKeyPairSign.publicKey)
        val privateKeySignBase64 = Base64.encode(identityKeyPairSign.privateKey)

        // 2. Send Registration Request
        val request = RegisterRequest(
            username = username,
            identityKeyDh = publicKeyDhBase64,
            identityKeySign = publicKeySignBase64
        )
        val response = apiClient.register(request).getOrThrow()

        // 3. Persist secrets securely
        secureStorage.putString(AUTH_TOKEN, response.token)
        secureStorage.putString(USER_ID, response.userId)
        secureStorage.putString(IDENTITY_PRIVATE_KEY_DH, privateKeyDhBase64)
        secureStorage.putString(IDENTITY_PUBLIC_KEY_DH, publicKeyDhBase64)
        secureStorage.putString(IDENTITY_PRIVATE_KEY_SIGN, privateKeySignBase64)
        secureStorage.putString(IDENTITY_PUBLIC_KEY_SIGN, publicKeySignBase64)

        // 4. Update UI State
        _authState.value = AuthState.Authenticated(response.userId)
    }

    override suspend fun logout() {
        runCatching {
            secureStorage.remove(AUTH_TOKEN)
            secureStorage.remove(USER_ID)
            secureStorage.remove(IDENTITY_PRIVATE_KEY_DH)
            secureStorage.remove(IDENTITY_PRIVATE_KEY_SIGN)
            // Future: clear all ratchet session states here too
        }
        _authState.value = AuthState.Unauthenticated
    }
}
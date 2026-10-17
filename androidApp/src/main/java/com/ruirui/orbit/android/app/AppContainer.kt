package com.ruirui.orbit.android.app

import android.content.Context
import com.ruirui.orbit.crypto.CryptoKotlinAdapter
import com.ruirui.orbit.crypto.PreKeyManagerImpl
import com.ruirui.orbit.crypto.SessionStore
import com.ruirui.orbit.crypto.SessionStoreImpl
import com.ruirui.orbit.db.DriverFactory
import com.ruirui.orbit.db.OrbitDatabase
import com.ruirui.orbit.network.HttpClientFactory
import com.ruirui.orbit.network.api.AuthApi
import com.ruirui.orbit.network.api.DeviceApi
import com.ruirui.orbit.network.api.FriendsApi
import com.ruirui.orbit.network.api.KeysApi
import com.ruirui.orbit.network.api.LocationApi
import com.ruirui.orbit.repository.AuthRepository
import com.ruirui.orbit.repository.AuthRepositoryImpl
import com.ruirui.orbit.repository.FriendRepository
import com.ruirui.orbit.repository.FriendRepositoryImpl
import com.ruirui.orbit.repository.LocationRepository
import com.ruirui.orbit.repository.LocationRepositoryImpl
import com.ruirui.orbit.storage.AndroidSecureStorage
import com.ruirui.orbit.sync.SyncOrchestrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AppContainer(private val context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Storage & Cryptography
    val secureStorage by lazy { AndroidSecureStorage(context) }

    val crypto by lazy { CryptoKotlinAdapter() }

    val sessionStore: SessionStore by lazy { SessionStoreImpl(secureStorage, crypto) }
    val database by lazy { OrbitDatabase(DriverFactory(context).createDriver()) }

    // Networking
    // TODO: Point to backend URL
    val baseUrl = "http://192.168.178.27:3000"
    val httpClient by lazy {
        HttpClientFactory.create(
            secureStorage = secureStorage,
            baseUrl = baseUrl
        )
    }

    // APIs
    val authApi by lazy { AuthApi(httpClient) }
    val keysApi by lazy { KeysApi(httpClient) }
    val friendsApi by lazy { FriendsApi(httpClient) }
    val locationApi by lazy { LocationApi(httpClient) }
    val deviceApi by lazy { DeviceApi(httpClient) }

    // Managers and Repositories
    val preKeyManager by lazy { PreKeyManagerImpl(crypto, secureStorage, keysApi, database) }

    val authRepository: AuthRepository by lazy {
        AuthRepositoryImpl(
            authApi, preKeyManager, secureStorage, crypto, appScope
        )
    }

    val friendRepository: FriendRepository by lazy {
        FriendRepositoryImpl(
            friendsApi, keysApi, database, appScope
        )
    }

    val locationRepository: LocationRepository by lazy {
        LocationRepositoryImpl(
            locationApi,
            keysApi,
            crypto,
            secureStorage,
            sessionStore,
            preKeyManager,
            database,
            appScope
        )
    }

    val syncOrchestrator by lazy { SyncOrchestrator(locationRepository, friendRepository) }
}
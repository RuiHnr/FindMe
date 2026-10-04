package com.ruirui.findme.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File

class AndroidSecureStorage(
    private val context: Context,
    private val cryptoHelper: AndroidCryptoHelper = AndroidCryptoHelper(),
    dataStoreFileName: String = "findme_secure_prefs.preferences_pb"
) : SecureStorage {

    // Lazy init of Jetpack DataStore
    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        produceFile = { File(context.filesDir, "datastore/$dataStoreFileName") }
    )

    override suspend fun putString(key: String, value: String) {
        val prefKey = stringPreferencesKey(key)
        val encryptedValue = cryptoHelper.encrypt(value)

        // edit() is atomic, thread-safe transactional update in DataStore
        dataStore.edit { preferences -> preferences[prefKey] = encryptedValue }
    }

    override suspend fun getString(key: String): String? {
        val prefKey = stringPreferencesKey(key)

        // Read first emission from DataStore flow
        val encryptedValue = dataStore.data.map { preferences ->
            preferences[prefKey]
        }.first() ?: return null

        return try {
            cryptoHelper.decrypt(encryptedValue)
        } catch (e: Exception) { null }
    }

    override suspend fun remove(key: String) {
        val prefKey = stringPreferencesKey(key)
        dataStore.edit { preferences -> preferences.remove(prefKey) }
    }
}
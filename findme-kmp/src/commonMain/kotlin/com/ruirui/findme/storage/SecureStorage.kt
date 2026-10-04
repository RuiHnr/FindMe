package com.ruirui.findme.storage

interface SecureStorage {
    suspend fun putString(key: String, value: String)
    suspend fun getString(key: String): String?
    suspend fun remove(key: String)
}
package com.ruirui.findme.storage

class InMemorySecureStorage : SecureStorage {
    private val map = mutableMapOf<String, String>()

    override suspend fun putString(key: String, value: String) {
        map[key] = value
    }

    override suspend fun getString(key: String): String? {
        return map[key]
    }

    override suspend fun remove(key: String) {
        map.remove(key)
    }

    fun clear() {
        map.clear()
    }
}

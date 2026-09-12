package com.ruirui.findme.crypto

interface SessionStore {
    suspend fun loadSession(remoteUserId: String): DoubleRatchetSession?
    suspend fun saveSession(remoteUserId: String, session: DoubleRatchetSession)
}
package com.ruirui.findme.sync

import com.ruirui.findme.repository.FriendRepository
import com.ruirui.findme.repository.LocationRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Coordinates all data synchronization in a single, mutex-protected entry point.
 * Called from:
 *   - Background location service (after submitting own location)
 *   - Foreground polling timer
 *   - Periodic background tasks (WorkManager / BGTaskScheduler)
 *   - Manual pull-to-refresh
 *
 * Uses a [Mutex] to prevent concurrent sync runs from racing on the
 * Double Ratchet state machine, which is NOT safe for parallel decryption.
 */
class SyncOrchestrator(
    private val locationRepository: LocationRepository,
    private val friendRepository: FriendRepository,
) {
    private val syncMutex = Mutex()

    /**
     * Runs a full sync cycle: friends list + inbox decryption.
     * Returns [Result.success] if both succeed, or the first failure.
     *
     * Thread-safe: concurrent calls queue behind the mutex rather than
     * corrupting ratchet state.
     */
    suspend fun syncAll(): Result<Unit> = syncMutex.withLock {
        // Sync friends first — inbox decryption may need the friend list
        friendRepository.syncFriends()
            .onFailure { return Result.failure(it) }

        locationRepository.syncInbox()
    }

    /**
     * Lightweight sync that only fetches inbox messages.
     * Use when the friends list is known to be fresh (e.g., app just opened).
     */
    suspend fun syncInboxOnly(): Result<Unit> = syncMutex.withLock {
        locationRepository.syncInbox()
    }
}
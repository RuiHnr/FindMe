package com.ruirui.findme.sync

import com.ruirui.findme.network.api.PresenceApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PresenceManagerTest {

    private lateinit var presenceApi: PresenceApi
    private lateinit var testScope: CoroutineScope
    private lateinit var presenceManager: PresenceManager

    @BeforeTest
    fun setup() {
        presenceApi = object : PresenceApi {
            var heartbeatCalled = 0
            var removeCalled = 0

            override suspend fun heartbeat(): Result<Unit> {
                heartbeatCalled++
                return Result.success(Unit)
            }

            override suspend fun remove(): Result<Unit> {
                removeCalled++
                return Result.success(Unit)
            }
        }
        testScope = CoroutineScope(Dispatchers.Default)
        presenceManager = PresenceManager(presenceApi, testScope)
    }

    @AfterTest
    fun cleanup() {
        testScope.cancel()
    }

    @Test
    fun `start creates and manages heartbeat job`() = runTest {
        // When
        presenceManager.start()

        // Then
        assertNotNull(presenceManager.heartbeatJob)
        assertTrue(presenceManager.heartbeatJob?.isActive == true)
    }

    @Test
    fun `start idempotent - calling multiple times doesn't create duplicate jobs`() = runTest {
        // When
        presenceManager.start()
        val firstJob = presenceManager.heartbeatJob
        presenceManager.start()

        // Then
        assertEquals(firstJob, presenceManager.heartbeatJob)
    }

    @Test
    fun `stop cancels heartbeat job`() = runTest {
        // Given
        presenceManager.start()
        assertNotNull(presenceManager.heartbeatJob)

        // When
        presenceManager.stop()

        // Then
        assertNull(presenceManager.heartbeatJob)
    }

    @Test
    fun `stop is idempotent - calling multiple times works`() = runTest {
        // Given
        presenceManager.start()

        // When
        presenceManager.stop()
        presenceManager.stop()

        // Then
        assertNull(presenceManager.heartbeatJob)
    }

    @Test
    fun `heartbeat is called every 30 seconds`() = runTest {
        // Given
        var heartbeatCount = 0
        presenceApi = object : PresenceApi {
            override suspend fun heartbeat(): Result<Unit> {
                heartbeatCount++
                return Result.success(Unit)
            }
            override suspend fun remove(): Result<Unit> = Result.success(Unit)
        }
        // Use backgroundScope so the heartbeat coroutine runs on the test virtual-time scheduler
        presenceManager = PresenceManager(presenceApi, backgroundScope)

        // When
        presenceManager.start()
        advanceTimeBy(29_000)
        runCurrent()
        assertEquals(1, heartbeatCount)

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, heartbeatCount)

        // Cleanup
        presenceManager.stop()
    }

    @Test
    fun `api remove is called when stop is invoked`() = runTest {
        // Given
        var removeCount = 0
        presenceApi = object : PresenceApi {
            override suspend fun heartbeat(): Result<Unit> = Result.success(Unit)
            override suspend fun remove(): Result<Unit> {
                removeCount++
                return Result.success(Unit)
            }
        }
        // Use backgroundScope so the coroutine launched in stop() is drained before we assert
        presenceManager = PresenceManager(presenceApi, backgroundScope)

        // When
        presenceManager.start()
        presenceManager.stop()
        runCurrent() // drain the scope.launch { remove() } enqueued by stop()

        // Then
        assertEquals(1, removeCount)
    }
}

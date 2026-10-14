package com.ruirui.orbit.db

import app.cash.sqldelight.driver.native.NativeSqliteDriver

actual fun createTestDatabase(): OrbitDatabase {
    val driver = NativeSqliteDriver(
        schema = OrbitDatabase.Schema,
        name = "test.db",
        onConfiguration = { config ->
            config.copy(inMemory = true)
        }
    )
    return OrbitDatabase(driver)
}

package com.ruirui.findme.db

import app.cash.sqldelight.driver.native.NativeSqliteDriver

actual fun createTestDatabase(): FindMeDatabase {
    val driver = NativeSqliteDriver(
        schema = FindMeDatabase.Schema,
        name = "test.db",
        onConfiguration = { config ->
            config.copy(inMemory = true)
        }
    )
    return FindMeDatabase(driver)
}

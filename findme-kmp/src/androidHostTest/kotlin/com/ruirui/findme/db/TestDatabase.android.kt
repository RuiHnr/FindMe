package com.ruirui.findme.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver

actual fun createTestDatabase(): FindMeDatabase {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    FindMeDatabase.Schema.create(driver)
    return FindMeDatabase(driver)
}

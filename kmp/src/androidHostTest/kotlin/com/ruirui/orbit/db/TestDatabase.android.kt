package com.ruirui.orbit.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver

actual fun createTestDatabase(): OrbitDatabase {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    OrbitDatabase.Schema.create(driver)
    return OrbitDatabase(driver)
}

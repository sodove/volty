package ru.sodovaya.volty.data.db

import app.cash.sqldelight.db.SqlDriver

class VoltyDatabaseProvider(driver: SqlDriver) {
    val database: VoltyDatabase = VoltyDatabase(driver)
}

package ru.sodovaya.volty.data.db

import app.cash.sqldelight.db.SqlDriver

expect class SqlDriverFactory {
    fun create(): SqlDriver
}

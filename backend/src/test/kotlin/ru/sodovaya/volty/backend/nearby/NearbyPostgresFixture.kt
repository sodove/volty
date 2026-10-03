package ru.sodovaya.volty.backend.nearby

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import javax.sql.DataSource

/** Actual disposable PostgreSQL, with reusable sockets and independent transactions. */
internal object NearbyPostgresFixture {
    private val pool: HikariDataSource by lazy {
        HikariDataSource(HikariConfig().apply {
            jdbcUrl = checkNotNull(System.getenv("VOLTY_NEARBY_TEST_JDBC_URL")) { "Disposable PostgreSQL URL required" }
            username = "nearby_test"; password = "nearby-disposable-test-only"
            maximumPoolSize = 8; minimumIdle = 0; connectionTimeout = 5000
            validationTimeout = 2000; schema = "public"; poolName = "nearby-disposable-postgres"
        }).also { owned -> Runtime.getRuntime().addShutdownHook(Thread { owned.close() }) }
    }

    fun source(schema: String? = null): DataSource {
        if (schema == null) return pool
        require(schema.matches(Regex("[a-z][a-z0-9_]{0,62}")))
        return object : DataSource by pool {
            override fun getConnection(): Connection {
                val connection = pool.connection
                try { connection.schema = schema; return connection }
                catch (failure: Throwable) { connection.close(); throw failure }
                // Hikari's proxy resets dirty schema/autocommit when this connection is closed.
            }
        }
    }
}

package ru.sodovaya.volty.backend.nearby

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/** Pauses after the real revision SELECT has established the transaction's snapshot. */
internal class RevisionReadBarrierSource(private val delegate: DataSource) : DataSource by delegate {
    val readIssued = CountDownLatch(1)
    val release = CountDownLatch(1)
    override fun getConnection(): Connection {
        val connection = delegate.connection
        return Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
            val result = try { method.invoke(connection, *(args ?: emptyArray())) } catch (failure: InvocationTargetException) { throw failure.targetException }
            if (method.name == "prepareStatement" && (args?.firstOrNull() as? String)?.contains("SELECT revision FROM nearby_roster_clock") == true) {
                val statement = result as PreparedStatement
                Proxy.newProxyInstance(PreparedStatement::class.java.classLoader, arrayOf(PreparedStatement::class.java)) { _, call, parameters ->
                    val rows = try { call.invoke(statement, *(parameters ?: emptyArray())) } catch (failure: InvocationTargetException) { throw failure.targetException }
                    if (call.name == "executeQuery") { readIssued.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
                    rows
                }
            } else result
        } as Connection
    }
}

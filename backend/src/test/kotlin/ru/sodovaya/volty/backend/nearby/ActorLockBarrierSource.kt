package ru.sodovaya.volty.backend.nearby

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/** Holds the first real PostgreSQL actor lock until the competing query was issued. */
internal class ActorLockBarrierSource(private val delegate: DataSource) : DataSource by delegate {
    val held = CountDownLatch(1)
    val competingQueryIssued = CountDownLatch(1)
    val release = CountDownLatch(1)
    private val attempts = AtomicInteger()

    override fun getConnection(): Connection {
        val connection = delegate.connection
        return Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
            val result = invoke(connection, method, args)
            if (method.name == "prepareStatement" && (args?.firstOrNull() as? String)?.contains("FOR UPDATE") == true) {
                val statement = result as PreparedStatement
                Proxy.newProxyInstance(PreparedStatement::class.java.classLoader, arrayOf(PreparedStatement::class.java)) { _, statementMethod, statementArgs ->
                    if (statementMethod.name == "executeQuery") {
                        val attempt = attempts.incrementAndGet()
                        if (attempt == 2) competingQueryIssued.countDown()
                        val rows = invoke(statement, statementMethod, statementArgs)
                        if (attempt == 1) { held.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
                        rows
                    } else invoke(statement, statementMethod, statementArgs)
                }
            } else result
        } as Connection
    }

    private fun invoke(target: Any, method: Method, args: Array<out Any?>?): Any? =
        try { method.invoke(target, *(args ?: emptyArray())) } catch (failure: InvocationTargetException) { throw failure.targetException }
}

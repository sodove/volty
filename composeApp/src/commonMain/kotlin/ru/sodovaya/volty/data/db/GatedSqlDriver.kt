package ru.sodovaya.volty.data.db

import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement

/**
 * The driver every repository holds. Building it, and everything built on it, touches nothing;
 * the first statement or transaction waits through [awaitReady] until the database is open.
 *
 * This is the backstop, not the plan: the UI, the recorder and the startup work wait for the gate
 * explicitly. A caller that forgot is stalled here instead of running a query that would open
 * (and migrate) the database on its own thread. It stalls rather than throws on purpose: a throw
 * inside a coroutine nobody guards crashes the process, and a process that crashes on every start
 * never finishes the migration.
 *
 * Listener bookkeeping is in memory in the wrapped driver and needs no open database.
 */
class GatedSqlDriver(private val delegate: SqlDriver, private val awaitReady: () -> Unit) : SqlDriver {
    override fun <R> executeQuery(
        identifier: Int?, sql: String, mapper: (SqlCursor) -> QueryResult<R>, parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> { awaitReady(); return delegate.executeQuery(identifier, sql, mapper, parameters, binders) }

    override fun execute(identifier: Int?, sql: String, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<Long> {
        awaitReady(); return delegate.execute(identifier, sql, parameters, binders)
    }

    override fun newTransaction(): QueryResult<Transacter.Transaction> { awaitReady(); return delegate.newTransaction() }
    override fun currentTransaction(): Transacter.Transaction? = delegate.currentTransaction()
    override fun addListener(vararg queryKeys: String, listener: Query.Listener) = delegate.addListener(*queryKeys, listener = listener)
    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) = delegate.removeListener(*queryKeys, listener = listener)
    override fun notifyListeners(vararg queryKeys: String) = delegate.notifyListeners(*queryKeys)
    override fun close() = delegate.close()
}

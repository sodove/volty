package ru.sodovaya.volty.backend.nearby

import javax.sql.DataSource

/** Cutover withdraws legacy consent; account/friend/group history is retained. */
internal class JdbcLegacySharingRetirement(private val source: DataSource) {
    fun retire(now: () -> Long) = communityTransaction(source, write = true) { c ->
        val at = now().also { require(it >= 0) }
        // Serialize against old writers during this transaction. Other backend nodes
        // must be retired in the coordinated rollout; this lock is not a cluster gate.
        c.createStatement().use {
            it.execute("LOCK TABLE sharing_sessions, live_updates IN SHARE ROW EXCLUSIVE MODE")
        }
        c.prepareStatement("UPDATE sharing_sessions SET revoked_at=? WHERE revoked_at IS NULL").use {
            it.setLong(1, at); it.executeUpdate()
        }
        // Includes stale orphan payloads: no legacy live position is authorized after cutover.
        c.createStatement().use { it.executeUpdate("DELETE FROM live_updates") }
        Unit
    }
}

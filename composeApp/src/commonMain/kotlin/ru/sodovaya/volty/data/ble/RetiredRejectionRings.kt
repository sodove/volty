package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.domain.model.RetiredLinkRing
import ru.sodovaya.volty.domain.plausibility.RejectedFrame

/**
 * Rejection rings of links that went away. A protocol (and so its ring) is rebuilt on every session start, so the
 * evidence of a torn read would vanish exactly when the rider opens the debug screen after the disconnect. This keeps
 * a snapshot per address: bounded to the last [MAX_ADDRESSES] addresses (least recently retired evicted) and
 * [MAX_CONNECTIONS_PER_ADDRESS] connections each, every connection tagged with a per-address sequence number so the
 * dump can tell them apart (`atFrame` restarts at 0 on each). A reconnect KEEPS the earlier entries: they are merged,
 * never replaced. Retiring an empty ring, or the very same ring twice, is a no-op. Not thread-safe: the caller holds its own lock.
 */
internal class RetiredRejectionRings {
    private val byAddress = LinkedHashMap<String, MutableList<RetiredLinkRing>>()
    private val nextConnection = HashMap<String, Int>()

    fun retire(address: String, ring: List<RejectedFrame>) {
        if (ring.isEmpty()) return
        val segments = byAddress.remove(address) ?: mutableListOf()
        if (segments.lastOrNull()?.frames != ring) {
            val seq = (nextConnection[address] ?: 0) + 1
            nextConnection[address] = seq
            segments += RetiredLinkRing(address, seq, ring)
            while (segments.size > MAX_CONNECTIONS_PER_ADDRESS) segments.removeAt(0)
        }
        byAddress[address] = segments // re-inserted last: most recently retired
        while (byAddress.size > MAX_ADDRESSES) {
            val eldest = byAddress.keys.first()
            byAddress.remove(eldest); nextConnection.remove(eldest)
        }
    }

    fun snapshot(): List<RetiredLinkRing> = byAddress.values.flatten()

    fun clear() { byAddress.clear(); nextConnection.clear() }

    companion object {
        const val MAX_ADDRESSES = 4
        const val MAX_CONNECTIONS_PER_ADDRESS = 4
    }
}

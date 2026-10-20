package ru.sodovaya.volty.data.radio

/**
 * Network names per community, keyed by the UTC day they were derived for. The names change at
 * UTC midnight, so an entry from another day is a miss rather than a stale answer.
 */
class RadioNameCache {
    private class Entry(val day: Long, val names: Set<String>)

    private val entries = HashMap<String, Entry>()

    @Synchronized
    fun get(community: String, day: Long): Set<String>? = entries[community]?.takeIf { it.day == day }?.names

    @Synchronized
    fun put(community: String, day: Long, names: Set<String>) {
        entries[community] = Entry(day, names)
    }
}

package ru.sodovaya.volty.presentation.root.nearby

internal enum class NearbyPermissionKind { MICROPHONE, LOCATION }
internal data class NearbyPermissionRequest(val kind: NearbyPermissionKind, val id: String)
internal data class NearbyPermissionSlot(val request: NearbyPermissionRequest, val granted: Boolean? = null)
internal data class NearbyPermissionDecision(val request: NearbyPermissionRequest, val granted: Boolean)

/** One native dialog at a time; cancellation of a UI request does not retarget its callback. Main only. */
internal class NearbyPermissionGate(restored: NearbyPermissionSlot? = null) {
    var slot: NearbyPermissionSlot? = restored
        private set
    private var closed = false
    fun reserve(request: NearbyPermissionRequest): Boolean {
        if (closed || slot != null || request.id.isBlank()) return false
        slot = NearbyPermissionSlot(request)
        return true
    }
    fun complete(granted: Boolean): Boolean {
        val original = slot ?: return false
        if (closed || original.granted != null) return false
        slot = original.copy(granted = granted)
        return true
    }
    fun takeReady(visible: Boolean): NearbyPermissionDecision? {
        if (closed || !visible) return null
        val original = slot ?: return null
        val granted = original.granted ?: return null
        slot = null
        return NearbyPermissionDecision(original.request, granted)
    }
    fun close() { closed = true; slot = null }
}

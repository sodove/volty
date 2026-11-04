package ru.sodovaya.volty.domain.video

/** Host visibility and recorder ownership are independent: never replace a session owning an unfinished file. */
class VideoCameraSessionLifecycle(
    private val busy: () -> Boolean,
    private val bind: () -> Boolean,
    private val release: () -> Unit,
    private val stop: () -> Unit,
) {
    var foreground: Boolean = false
        private set
    private var bound = false
    private var needsBind = true

    fun started() {
        foreground = true
        restore()
    }

    fun attached() {
        needsBind = true
        restore()
    }

    fun stopped() {
        foreground = false
        needsBind = true
        if (busy()) stop() else suspend()
    }

    fun finalized() {
        if (busy()) return
        if (foreground) restore() else suspend()
    }

    private fun restore() {
        if (foreground && needsBind && !busy() && bind()) {
            bound = true
            needsBind = false
        }
    }

    private fun suspend() {
        if (bound) release()
        bound = false
    }
}

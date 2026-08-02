package ru.sodovaya.volty.presentation.vehicle

/**
 * A retained component that owns an unsaved vehicle draft.
 *
 * Root navigation needs only these two facts: whether destroying its stack
 * would lose rider work, and how to ask that exact component for permission.
 * Both the full editor and first-setup wizard implement this contract, so a
 * new creation flow does not grow a second root-level discard mechanism.
 */
interface DraftExitComponent {
    val hasUnsavedDraft: Boolean
    fun requestExit(onDiscarded: () -> Unit)
}

/**
 * The G2 Task 9 discard transaction, shared by every draft-owning component.
 *
 * The pending continuation and visible prompt are one state-machine fact. A
 * second request cannot replace the action described by an already-visible
 * prompt, and save completion either keeps both or retires both.
 */
internal class DraftExitCoordinator(
    private val isDirty: () -> Boolean,
    private val publishPrompt: (Boolean) -> Unit
) {
    private var pendingExit: (() -> Unit)? = null

    fun requestExit(onDiscarded: () -> Unit) {
        if (pendingExit != null) return
        if (!isDirty()) {
            onDiscarded()
            return
        }
        pendingExit = onDiscarded
        publishPrompt(true)
    }

    fun confirm() {
        val exit = pendingExit
        pendingExit = null
        publishPrompt(false)
        exit?.invoke()
    }

    fun dismiss() {
        pendingExit = null
        publishPrompt(false)
    }

    /** Reconcile a completed write with a request that may have arrived during it. */
    fun afterSave() {
        val keep = isDirty() && pendingExit != null
        if (!keep) pendingExit = null
        publishPrompt(keep)
    }
}

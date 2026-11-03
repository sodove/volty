package ru.sodovaya.volty.presentation.root

import ru.sodovaya.volty.data.db.DatabaseGateAction
import ru.sodovaya.volty.data.db.DatabaseOpenFailure
import ru.sodovaya.volty.data.db.DatabaseOpenState
import ru.sodovaya.volty.data.db.databaseGateActions
import ru.sodovaya.volty.diagnostics.DatabaseShareOutcome
import ru.sodovaya.volty.diagnostics.SnapshotPlan
import ru.sodovaya.volty.diagnostics.SnapshotRefusal
import ru.sodovaya.volty.diagnostics.databaseSnapshotPlan

/** What the full-screen database gate shows. Everything the screen decides is decided here. */
sealed interface DatabaseGateScreenModel {
    /** The database is open: the app itself is shown. */
    data object Hidden : DatabaseGateScreenModel
    /** Opening, before it is known whether a migration is needed: a blank surface, no text. */
    data object Blank : DatabaseGateScreenModel
    /**
     * The ride history is being converted. The conversion reports no progress (it is a few SQL
     * statements in one transaction), so the bar is indeterminate and the text says it can take
     * minutes and that the app must stay open.
     */
    data object Updating : DatabaseGateScreenModel
    /**
     * Not enough storage; [freeUpMegabytes] is how much more the rider has to free. Retry, the logs and the database can
     * be shared, and [actions] offers emptying the ride history (owner request 2026-11-03).
     */
    data class NoSpace(
        val freeUpMegabytes: Long,
        val shareBusy: Boolean = false,
        val shareOutcome: DatabaseShareOutcome? = null,
        val actions: Set<DatabaseGateAction> = setOf(DatabaseGateAction.CLEAR_RIDE_HISTORY),
        val recoveryFailed: Boolean = false,
        /**
         * False when a copy of the database cannot be made on this disk (the share's own space rule, or a share already
         * refused for space): the clear dialog then must not advise "share first" as if it could work.
         */
        val copyPossible: Boolean = true,
    ) : DatabaseGateScreenModel {
        /** Retry is off while a share copies the files: the probe writes (re-review F2). */
        val retryEnabled: Boolean get() = !shareBusy
    }
    /**
     * Any other failure. The data is untouched; logs and the database can be shared, and [actions] offers starting with
     * an empty database. Retraction: this screen used to promise it never offers to delete; the owner asked for a way
     * out (2026-11-03), and it stays behind a confirmation that says what is lost.
     */
    data class Error(
        val shareBusy: Boolean,
        val shareOutcome: DatabaseShareOutcome?,
        val actions: Set<DatabaseGateAction> = setOf(DatabaseGateAction.START_WITH_EMPTY_DATABASE),
        val recoveryFailed: Boolean = false,
    ) : DatabaseGateScreenModel {
        /** Retry is off while a share copies the files: the probe writes (re-review F2). */
        val retryEnabled: Boolean get() = !shareBusy
    }
    /** A confirmed action is running: a title and a bar instead of a blank screen for the ~30 s of a clear. */
    data class Working(val action: DatabaseGateAction) : DatabaseGateScreenModel
}

private const val MEGABYTE = 1024L * 1024

fun databaseGateScreenModel(
    state: DatabaseOpenState,
    shareBusy: Boolean = false,
    shareOutcome: DatabaseShareOutcome? = null,
    recoveryFailed: Boolean = false,
): DatabaseGateScreenModel = when (state) {
    DatabaseOpenState.Ready -> DatabaseGateScreenModel.Hidden
    DatabaseOpenState.Opening -> DatabaseGateScreenModel.Blank
    is DatabaseOpenState.Migrating -> DatabaseGateScreenModel.Updating
    is DatabaseOpenState.Recovering -> DatabaseGateScreenModel.Working(state.action)
    is DatabaseOpenState.Failed -> when (val failure = state.failure) {
        is DatabaseOpenFailure.NotEnoughSpace -> DatabaseGateScreenModel.NoSpace(
            freeUpMegabytes = megabytesToFree(failure.requiredBytes, failure.freeBytes),
            shareBusy = shareBusy, shareOutcome = shareOutcome, actions = offered(state, shareBusy), recoveryFailed = recoveryFailed,
            copyPossible = copyPossible(failure, shareOutcome),
        )
        is DatabaseOpenFailure.Other ->
            DatabaseGateScreenModel.Error(shareBusy, shareOutcome, offered(state, shareBusy), recoveryFailed)
    }
}

/** Whole megabytes missing, rounded up, never below 1: "free 0 MB" would be no instruction. */
fun megabytesToFree(requiredBytes: Long, freeBytes: Long): Long =
    ((requiredBytes - freeBytes).coerceAtLeast(0L) + MEGABYTE - 1).div(MEGABYTE).coerceAtLeast(1L)

/** No destructive action while a share is copying the files: deleting them under the copy would ship a torn file. */
private fun offered(state: DatabaseOpenState, shareBusy: Boolean): Set<DatabaseGateAction> =
    if (shareBusy) emptySet() else databaseGateActions(state)

/**
 * Whether «Отправить базу данных» could copy the file here: the share's own rule ([databaseSnapshotPlan]: the file plus
 * a margin must fit), and not already refused for space. An unmeasured size (0, the SQLITE_FULL path) is not a refusal.
 */
private fun copyPossible(failure: DatabaseOpenFailure.NotEnoughSpace, shareOutcome: DatabaseShareOutcome?): Boolean =
    shareOutcome != DatabaseShareOutcome.Refused(SnapshotRefusal.NO_SPACE) &&
        databaseSnapshotPlan(recording = false, dbBytes = failure.databaseBytes, freeBytes = failure.freeBytes) is SnapshotPlan.Copy

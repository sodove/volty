package ru.sodovaya.volty.presentation.root

import ru.sodovaya.volty.data.db.DatabaseOpenFailure
import ru.sodovaya.volty.data.db.DatabaseOpenState
import ru.sodovaya.volty.diagnostics.DatabaseShareOutcome

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
    /** Not enough storage; [freeUpMegabytes] is how much more the rider has to free. Retry only. */
    data class NoSpace(val freeUpMegabytes: Long) : DatabaseGateScreenModel
    /** Any other failure. The data is untouched; logs and the database can be shared. */
    data class Error(val shareBusy: Boolean, val shareOutcome: DatabaseShareOutcome?) : DatabaseGateScreenModel
}

private const val MEGABYTE = 1024L * 1024

fun databaseGateScreenModel(
    state: DatabaseOpenState,
    shareBusy: Boolean = false,
    shareOutcome: DatabaseShareOutcome? = null,
): DatabaseGateScreenModel = when (state) {
    DatabaseOpenState.Ready -> DatabaseGateScreenModel.Hidden
    DatabaseOpenState.Opening -> DatabaseGateScreenModel.Blank
    is DatabaseOpenState.Migrating -> DatabaseGateScreenModel.Updating
    is DatabaseOpenState.Failed -> when (val failure = state.failure) {
        is DatabaseOpenFailure.NotEnoughSpace ->
            DatabaseGateScreenModel.NoSpace(freeUpMegabytes = megabytesToFree(failure.requiredBytes, failure.freeBytes))
        is DatabaseOpenFailure.Other -> DatabaseGateScreenModel.Error(shareBusy, shareOutcome)
    }
}

/** Whole megabytes missing, rounded up, never below 1: "free 0 MB" would be no instruction. */
fun megabytesToFree(requiredBytes: Long, freeBytes: Long): Long =
    ((requiredBytes - freeBytes).coerceAtLeast(0L) + MEGABYTE - 1).div(MEGABYTE).coerceAtLeast(1L)

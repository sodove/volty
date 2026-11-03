package ru.sodovaya.volty.presentation.root

import ru.sodovaya.volty.data.db.DatabaseGateAction
import ru.sodovaya.volty.data.db.DatabaseOpenFailure
import ru.sodovaya.volty.data.db.DatabaseOpenState
import ru.sodovaya.volty.diagnostics.DatabaseShareOutcome
import ru.sodovaya.volty.diagnostics.SnapshotRefusal
import kotlin.test.Test
import kotlin.test.assertEquals

class DatabaseGateScreenModelTest {
    private val mb = 1024L * 1024

    @Test fun eachStateHasItsOwnScreen() {
        assertEquals(DatabaseGateScreenModel.Blank, databaseGateScreenModel(DatabaseOpenState.Opening))
        assertEquals(DatabaseGateScreenModel.Updating, databaseGateScreenModel(DatabaseOpenState.Migrating(27, 29)))
        assertEquals(DatabaseGateScreenModel.Hidden, databaseGateScreenModel(DatabaseOpenState.Ready))
    }

    @Test fun noSpaceNamesTheMissingMegabytesRoundedUp() {
        val state = DatabaseOpenState.Failed(DatabaseOpenFailure.NotEnoughSpace(150 * mb, 100 * mb - 1))
        assertEquals(DatabaseGateScreenModel.NoSpace(51), databaseGateScreenModel(state), "50 MB and one byte is 51 MB")
        assertEquals(DatabaseGateScreenModel.NoSpace(50),
            databaseGateScreenModel(DatabaseOpenState.Failed(DatabaseOpenFailure.NotEnoughSpace(150 * mb, 100 * mb))))
    }

    @Test fun noSpaceNeverAsksForZeroEvenWhenTheNumbersContradict() {
        // Incoherent on purpose: free is above required, yet the gate reported not enough.
        assertEquals(1, megabytesToFree(requiredBytes = 150 * mb, freeBytes = 400 * mb))
        assertEquals(1, megabytesToFree(requiredBytes = 400 * mb + 1, freeBytes = 400 * mb))
    }

    // Retraction: this test was named "...AndNeverOffersToDelete". The owner asked for a way out (2026-11-03); the offer is
    // pinned below instead, and it is withdrawn while a share is copying the files.
    @Test fun errorCarriesTheShareProgressAndOutcome() {
        val failed = DatabaseOpenState.Failed(DatabaseOpenFailure.Other("SQLiteException"))
        assertEquals(DatabaseGateScreenModel.Error(false, null), databaseGateScreenModel(failed))
        assertEquals(DatabaseGateScreenModel.Error(true, null, actions = emptySet()), databaseGateScreenModel(failed, shareBusy = true))
        assertEquals(DatabaseGateScreenModel.Error(false, DatabaseShareOutcome.Failed),
            databaseGateScreenModel(failed, shareOutcome = DatabaseShareOutcome.Failed))
    }

    @Test fun shareStateIsIgnoredOutsideTheErrorScreen() {
        assertEquals(DatabaseGateScreenModel.Updating,
            databaseGateScreenModel(DatabaseOpenState.Migrating(27, 29), shareBusy = true, shareOutcome = DatabaseShareOutcome.Failed))
    }

    @Test fun eachFailureScreenOffersItsOwnDestructiveActionAndNoOther() {
        val noSpace = databaseGateScreenModel(DatabaseOpenState.Failed(DatabaseOpenFailure.NotEnoughSpace(930 * mb, 500 * mb)))
        assertEquals(setOf(DatabaseGateAction.CLEAR_RIDE_HISTORY), (noSpace as DatabaseGateScreenModel.NoSpace).actions)
        val error = databaseGateScreenModel(DatabaseOpenState.Failed(DatabaseOpenFailure.Other("SQLiteDatabaseCorruptException")))
        assertEquals(setOf(DatabaseGateAction.START_WITH_EMPTY_DATABASE), (error as DatabaseGateScreenModel.Error).actions)
    }

    @Test fun noDestructiveActionWhileAShareIsCopyingTheFiles() {
        val noSpace = DatabaseOpenState.Failed(DatabaseOpenFailure.NotEnoughSpace(930 * mb, 500 * mb))
        val model = databaseGateScreenModel(noSpace, shareBusy = true) as DatabaseGateScreenModel.NoSpace
        assertEquals(emptySet(), model.actions)
        assertEquals(true, model.shareBusy, "NoSpace carries the share state too: sharing first is what the dialog recommends")
        val error = databaseGateScreenModel(DatabaseOpenState.Failed(DatabaseOpenFailure.Other("X")), shareBusy = true)
        assertEquals(emptySet(), (error as DatabaseGateScreenModel.Error).actions)
    }

    @Test fun aFailedActionIsShownOnBothFailureScreens() {
        val noSpace = databaseGateScreenModel(DatabaseOpenState.Failed(DatabaseOpenFailure.NotEnoughSpace(930 * mb, 500 * mb)), recoveryFailed = true)
        assertEquals(true, (noSpace as DatabaseGateScreenModel.NoSpace).recoveryFailed)
        val error = databaseGateScreenModel(DatabaseOpenState.Failed(DatabaseOpenFailure.Other("X")), recoveryFailed = true)
        assertEquals(true, (error as DatabaseGateScreenModel.Error).recoveryFailed)
        assertEquals(DatabaseGateScreenModel.Hidden, databaseGateScreenModel(DatabaseOpenState.Ready, recoveryFailed = true))
    }

    @Test fun retryIsOffWhileAShareCopiesTheFiles() {
        val noSpace = DatabaseOpenState.Failed(DatabaseOpenFailure.NotEnoughSpace(930 * mb, 500 * mb, 600 * mb))
        assertEquals(false, (databaseGateScreenModel(noSpace, shareBusy = true) as DatabaseGateScreenModel.NoSpace).retryEnabled)
        assertEquals(true, (databaseGateScreenModel(noSpace) as DatabaseGateScreenModel.NoSpace).retryEnabled)
        val error = DatabaseOpenState.Failed(DatabaseOpenFailure.Other("X"))
        assertEquals(false, (databaseGateScreenModel(error, shareBusy = true) as DatabaseGateScreenModel.Error).retryEnabled)
        assertEquals(true, (databaseGateScreenModel(error) as DatabaseGateScreenModel.Error).retryEnabled)
    }

    @Test fun aRunningActionHasItsOwnScreen() {
        assertEquals(DatabaseGateScreenModel.Working(DatabaseGateAction.CLEAR_RIDE_HISTORY),
            databaseGateScreenModel(DatabaseOpenState.Recovering(DatabaseGateAction.CLEAR_RIDE_HISTORY)))
        assertEquals(DatabaseGateScreenModel.Working(DatabaseGateAction.START_WITH_EMPTY_DATABASE),
            databaseGateScreenModel(DatabaseOpenState.Recovering(DatabaseGateAction.START_WITH_EMPTY_DATABASE)))
    }

    @Test fun theClearDialogKnowsWhenNoCopyCanBeMade() {
        fun copy(db: Long, free: Long, outcome: DatabaseShareOutcome? = null) = (databaseGateScreenModel(
            DatabaseOpenState.Failed(DatabaseOpenFailure.NotEnoughSpace(930 * mb, free, db)), shareOutcome = outcome,
        ) as DatabaseGateScreenModel.NoSpace).copyPossible
        // The share's rule: the file plus 16 MB must fit.
        assertEquals(true, copy(db = 600 * mb, free = 616 * mb))
        assertEquals(false, copy(db = 600 * mb, free = 616 * mb - 1), "one byte short: say a copy cannot be made")
        assertEquals(false, copy(db = 600 * mb, free = 500 * mb), "500 MB free cannot hold a copy of a 600 MB file")
        // Incoherent on purpose: the numbers say it fits, yet a share was already refused for space. Believe the refusal.
        assertEquals(false, copy(db = 10 * mb, free = 900 * mb, outcome = DatabaseShareOutcome.Refused(SnapshotRefusal.NO_SPACE)))
        assertEquals(true, copy(db = 10 * mb, free = 900 * mb, outcome = DatabaseShareOutcome.Failed))
    }
}


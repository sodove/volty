package ru.sodovaya.volty.presentation.root

import ru.sodovaya.volty.data.db.DatabaseGateAction
import ru.sodovaya.volty.data.db.DatabaseOpenFailure
import ru.sodovaya.volty.data.db.DatabaseOpenState
import ru.sodovaya.volty.diagnostics.DatabaseShareOutcome
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
}


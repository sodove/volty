package ru.sodovaya.volty.presentation.root

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

    @Test fun errorCarriesTheShareProgressAndOutcomeAndNeverOffersToDelete() {
        val failed = DatabaseOpenState.Failed(DatabaseOpenFailure.Other("SQLiteException"))
        assertEquals(DatabaseGateScreenModel.Error(false, null), databaseGateScreenModel(failed))
        assertEquals(DatabaseGateScreenModel.Error(true, null), databaseGateScreenModel(failed, shareBusy = true))
        assertEquals(DatabaseGateScreenModel.Error(false, DatabaseShareOutcome.Failed),
            databaseGateScreenModel(failed, shareOutcome = DatabaseShareOutcome.Failed))
    }

    @Test fun shareStateIsIgnoredOutsideTheErrorScreen() {
        assertEquals(DatabaseGateScreenModel.Updating,
            databaseGateScreenModel(DatabaseOpenState.Migrating(27, 29), shareBusy = true, shareOutcome = DatabaseShareOutcome.Failed))
    }
}

package ru.sodovaya.volty.data.bms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BegodeCurrentLearnerTest {
    @Test
    fun learnsEitherRawPolarityFromSagAndRecoveryDuringAnOrdinaryRide() {
        for (sign in listOf(-1, 1)) {
            val learner = BegodeCurrentLearner()
            val outputs = (0..30).map { step ->
                val draw = if (step % 8 < 4) 4f else 14f
                learner.onControllerSample(
                    step * 400L, draw * sign, 150f - draw * 0.07f, 20f, step * 0.002f,
                    effectiveSign = null, learnOffset = true,
                )
            }
            val pending = outputs.mapNotNull { it.sign }
            assertEquals(listOf(sign), pending.distinct())
            assertTrue(pending.size > 1) // The caller has not acknowledged the result yet.
            assertTrue(outputs.all { it.offsetA == null })
        }
    }

    @Test
    fun aSingleSpikeAndItsRecoveryCannotSetPolarity() {
        val learner = BegodeCurrentLearner()
        val outputs = (0..50).map { step ->
            val draw = if (step == 14) 19f else 4f
            learner.onControllerSample(
                step * 400L, -draw, 150f - draw * 0.07f, 20f, step * 0.002f,
                effectiveSign = null, learnOffset = true,
            )
        }
        assertTrue(outputs.all { it.sign == null && it.offsetA == null })
    }

    @Test
    fun standstillAndSteadyRailCannotSetPolarity() {
        for ((speed, withSag) in listOf(0f to true, 20f to false)) {
            val learner = BegodeCurrentLearner()
            val outputs = (0..50).map { step ->
                val draw = if (step % 8 < 4) 4f else 14f
                val volts = if (withSag) 150f - draw * 0.07f else 150f
                learner.onControllerSample(
                    step * 400L, -draw, volts, speed, 0f,
                    effectiveSign = null, learnOffset = true,
                )
            }
            assertTrue(outputs.all { it.sign == null && it.offsetA == null })
        }
    }

    @Test
    fun offsetUsesBothFreshBmsBranchesEvenWithManualControllerSign() {
        val outputs = runOffsetCase(reportSecondBranch = true, learnOffset = true)
        assertTrue(outputs.all { it.sign == null })
        assertEquals(listOf(-1), outputs.mapNotNull { it.bmsSign }.distinct())
        assertTrue(outputs.count { it.bmsSign != null } > 1)
        val offset = assertNotNull(outputs.lastOrNull { it.offsetA != null }?.offsetA)
        assertEquals(-1.9f, offset, absoluteTolerance = 0.2f)
        assertTrue(outputs.count { it.offsetA != null } > 1) // Offset persistence may need a retry.
    }

    @Test
    fun independentlyAnchoredSmartBmsCanSetSignWhenControllerRailIsFlat() {
        val outputs = runOffsetCase(reportSecondBranch = true, learnOffset = true, controllerSign = null)
        assertEquals(listOf(-1), outputs.mapNotNull { it.sign }.distinct())
        assertEquals(listOf(-1), outputs.mapNotNull { it.bmsSign }.distinct())
    }

    @Test
    fun matchingCurrentSignalsWithNoVoltageWitnessCannotSetSign() {
        val outputs = runOffsetCase(
            reportSecondBranch = true, learnOffset = true, controllerSign = null, branchSag = false,
        )
        assertTrue(outputs.all { it.sign == null && it.bmsSign == null && it.offsetA == null })
    }

    @Test
    fun conflictingPhysicalWitnessesWithholdUnacknowledgedPolarity() {
        val outputs = runOffsetCase(
            reportSecondBranch = true, learnOffset = true, controllerSign = null, incorrectRailAfter = 80,
        )
        // The BMS witness had earned a result. The caller never acknowledged it, so a
        // later conflicting rail witness must stop that result from being reissued.
        assertTrue(outputs.take(80).any { it.sign == -1 })
        assertTrue(outputs.takeLast(20).all { it.sign == null && it.offsetA == null })
    }

    @Test
    fun offsetRequiresFullFreshPairAndAnEnabledAutomaticMode() {
        val missing = runOffsetCase(reportSecondBranch = false, learnOffset = true)
        val manual = runOffsetCase(reportSecondBranch = true, learnOffset = false)
        assertTrue((missing + manual).all { it.offsetA == null })
        assertTrue(missing.all { it.bmsSign == null })
        assertEquals(listOf(-1), manual.mapNotNull { it.bmsSign }.distinct())
    }

    @Test
    fun conflictingBranchesCannotChooseABmsSignOrOffset() {
        val outputs = runOffsetCase(reportSecondBranch = true, learnOffset = true, conflictingBranches = true)
        assertTrue(outputs.all { it.bmsSign == null && it.offsetA == null })
    }

    @Test
    fun manualSignChangeStartsNewBmsEvidence() {
        val learner = BegodeCurrentLearner()
        val first = runOffsetCase(reportSecondBranch = true, learnOffset = true, learner = learner)
        val second = runOffsetCase(
            reportSecondBranch = true, learnOffset = true, learner = learner,
            startStep = 176, controllerSign = 1,
        )
        assertEquals(listOf(-1), first.mapNotNull { it.bmsSign }.distinct())
        assertEquals(listOf(1), second.mapNotNull { it.bmsSign }.distinct())
        assertEquals(1.9f, second.mapNotNull { it.offsetA }.distinct().single(), absoluteTolerance = 0.2f)
    }

    @Test
    fun stationaryControllerByItselfCannotDeclareItsCurrentAnOffset() {
        val learner = BegodeCurrentLearner()
        val offset = (0..90).firstNotNullOfOrNull { step ->
            learner.onControllerSample(
                step * 400L, -1.9f, 149.9f, 0f, 0f,
                effectiveSign = -1, learnOffset = true,
            ).offsetA
        }
        assertNull(offset)
    }

    private fun runOffsetCase(
        reportSecondBranch: Boolean,
        learnOffset: Boolean,
        conflictingBranches: Boolean = false,
        learner: BegodeCurrentLearner = BegodeCurrentLearner(),
        startStep: Int = 0,
        controllerSign: Int? = -1,
        branchSag: Boolean = true,
        incorrectRailAfter: Int = Int.MAX_VALUE,
    ): List<BegodeCurrentEvidence> {
        fun draw(step: Int): Float {
            if (step < 0 || step >= 120) return 0.4f // Real low auxiliary draw survives the offset.
            return listOf(0f, 12f, 3f, 19f, -4f, 9f)[step / 10 % 6]
        }
        return (0..175).map { step ->
            val time = (startStep + step) * 400L
            val draw = draw(step)
            val delayedDraw = draw(step - 10)
            // This BMS pair reports discharge negative. An initial branch reading exists,
            // but the missing-branch fixture must let it go stale before any learning.
            val branchVoltage = if (branchSag) 150f - delayedDraw * 0.05f else 150f
            learner.onBmsSample(0, time, -delayedDraw / 2f, branchVoltage)
            if (reportSecondBranch || step == 0) {
                val second = if (conflictingBranches) delayedDraw / 2f else -delayedDraw / 2f
                learner.onBmsSample(1, time, second, branchVoltage)
            }
            val noise = if (step % 2 == 0) 0.6f else -0.6f
            val rail = if (step >= incorrectRailAfter) 150f + draw * 0.07f else 150f
            learner.onControllerSample(
                time, -(draw + 1.9f + noise), rail, if (step < 120) 20f else 0f,
                (startStep + step) * 0.002f, effectiveSign = controllerSign, learnOffset = learnOffset,
            )
        }
    }
}

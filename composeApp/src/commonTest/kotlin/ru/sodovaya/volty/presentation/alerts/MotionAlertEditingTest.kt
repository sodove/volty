package ru.sodovaya.volty.presentation.alerts

import ru.sodovaya.volty.domain.alert.AlarmDefaults
import ru.sodovaya.volty.domain.alert.AlertAvailability
import ru.sodovaya.volty.domain.alert.AlertLevel
import ru.sodovaya.volty.domain.alert.AlertRule
import ru.sodovaya.volty.domain.alert.AlertUnavailableReason
import ru.sodovaya.volty.domain.alert.MotionAlertKind
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.Controller
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.motionAlertRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * The alert editor's decisions, tested where they actually live.
 *
 * Compose UI is not unit-testable in this repo, so `MotionAlertEditing.kt` holds
 * everything the screen appears to decide and this file is the proof. Every test
 * below corresponds to a way the screen could be wrong on a real phone.
 */
@OptIn(ExperimentalTime::class)
class MotionAlertEditingTest {

    private fun vehicle(
        controllerType: ControllerType = ControllerType.VESC,
        motionAlerts: List<AlertRule>? = null
    ) = Vehicle(
        id = "v1",
        name = "Wheel",
        iconKey = "generic",
        packs = emptyList(),
        controllers = listOf(
            Controller(index = 0, label = "ESC", controllerType = controllerType, address = "AA:BB")
        ),
        chemistry = Chemistry.LI_ION_NMC,
        createdAt = Clock.System.now(),
        motionAlerts = motionAlerts
    )

    private fun draft(
        kind: MotionAlertKind = MotionAlertKind.DUTY,
        availability: AlertAvailability = AlertAvailability.Available,
        levels: List<AlertLevelDraft> = emptyList(),
        stashed: List<AlertLevelDraft> = emptyList()
    ) = AlertKindDraft(kind, availability, levels, stashed)

    // ---------------------------------------------------------------- parsing

    /**
     * [AlertLevel] throws on a non-finite threshold, and every string below is
     * something a rider can put in the field with one keystroke or one paste.
     * If any of them survived as a `Float`, the screen would crash *while typing*.
     */
    @Test
    fun `no keyboard or clipboard input can produce a threshold AlertLevel would refuse`() {
        val corpus = listOf(
            "", "   ", "-", ".", ",", "+", "abc", "8 0", "80%", "1/2", "０８０",
            "NaN", "nan", "Infinity", "-Infinity", "1e999", "-1e999", "1e-999",
            "-5", "-0.1", "80", "80.5", "80,5", "0"
        )
        corpus.forEach { raw ->
            val parsed = parseThreshold(raw)
            if (parsed != null) {
                assertTrue(parsed.isFinite(), "parseThreshold(\"$raw\") returned a non-finite $parsed")
                assertTrue(parsed >= 0f, "parseThreshold(\"$raw\") returned a negative $parsed")
                // The real proof: this is the constructor that throws.
                AlertLevel(parsed)
            }
        }
    }

    @Test
    fun `a half-typed or empty field is not a threshold`() {
        assertNull(parseThreshold(""))
        assertNull(parseThreshold("   "))
        assertNull(parseThreshold("-"))
        assertNull(parseThreshold("abc"))
    }

    /**
     * `toFloatOrNull` accepts these — they are in `Float.valueOf`'s grammar — so
     * a null-check alone lets them straight through to [AlertLevel]'s `require`.
     */
    @Test
    fun `NaN and the infinities are rejected rather than passed to AlertLevel`() {
        assertNull(parseThreshold("NaN"))
        assertNull(parseThreshold("Infinity"))
        assertNull(parseThreshold("-Infinity"))
    }

    /** Overflow, not a parse failure: `"1e999".toFloatOrNull()` is `+Infinity`. */
    @Test
    fun `an overflowing exponent is rejected rather than becoming infinity`() {
        assertEquals(Float.POSITIVE_INFINITY, "1e999".toFloatOrNull(), "fixture check: this must overflow, not fail")
        assertNull(parseThreshold("1e999"))
    }

    /** All four kinds are upper limits; a −5 °C motor alarm would sound forever. */
    @Test
    fun `a negative threshold is refused`() {
        assertNull(parseThreshold("-5"))
        assertEquals(0f, parseThreshold("0"))
    }

    /** The Russian keyboard's decimal key types a comma. */
    @Test
    fun `a comma decimal separator is accepted`() {
        assertEquals(80.5f, parseThreshold("80,5"))
        assertEquals(80.5f, parseThreshold("80.5"))
    }

    /**
     * A paste from a web page carries a **non-breaking** space, which is outside
     * `toFloatOrNull`'s own whitespace grammar — this is the case that makes the
     * `trim()` a real gate rather than decoration.
     */
    @Test
    fun `a pasted non-breaking space is stripped rather than failing the parse`() {
        // U+00A0 written as an escape so the fixture cannot be lost to an editor.
        val pasted = "\u00A080\u00A0"
        assertNull(
            pasted.toFloatOrNull(),
            "fixture check: toFloatOrNull allows only 0x00..0x20 around a number, so this must fail without trim()"
        )
        assertEquals(80f, parseThreshold(pasted))
        assertEquals(80f, parseThreshold(" 80 "))
    }

    @Test
    fun `a whole threshold renders without a trailing point zero`() {
        assertEquals("80", formatThreshold(80f))
        assertEquals("110", formatThreshold(110f))
        assertEquals("80.5", formatThreshold(80.5f))
    }

    // ------------------------------------------------------ switch <-> list

    /**
     * The mapping F §10.2 demands: the switch reads and writes the level list,
     * and there is no second flag to disagree with it.
     */
    @Test
    fun `switching a kind off empties its list — the only representation of off`() {
        val on = draft(levels = listOf(AlertLevelDraft("80"), AlertLevelDraft("90")))
        assertTrue(on.isOn)

        val off = on.withEnabled(false)

        assertFalse(off.isOn)
        assertEquals(emptyList<AlertLevelDraft>(), off.levels)
        assertTrue(off.toRule().isOff, "and the committed rule must read off")
    }

    /** The stash is an undo buffer, not a second "on": tuned numbers come back. */
    @Test
    fun `switching back on restores the riders own numbers, not the defaults`() {
        val tuned = draft(levels = listOf(AlertLevelDraft("72"), AlertLevelDraft("88")))

        val roundTrip = tuned.withEnabled(false).withEnabled(true)

        assertEquals(listOf("72", "88"), roundTrip.levels.map { it.text })
    }

    /** With nothing stashed there is nothing to restore, so the shipped defaults open the kind. */
    @Test
    fun `switching on a never-configured kind opens on its defaults`() {
        val off = draft(kind = MotionAlertKind.MOTOR_TEMP, levels = emptyList())

        val on = off.withEnabled(true)

        assertEquals(
            AlarmDefaults.rule(MotionAlertKind.MOTOR_TEMP).levels.map { formatThreshold(it.thresholdValue) },
            on.levels.map { it.text }
        )
    }

    /**
     * Speed ships with **no** default (F §10.1 — a limit is meaningless without a
     * rider-chosen number), so turning it on must produce a row that asks for the
     * number. A row that never appeared would leave the switch bouncing back off;
     * a row with an invented number would be the app deciding for the rider.
     */
    @Test
    fun `switching on a kind with no defaults asks for a number instead of inventing one`() {
        assertTrue(AlarmDefaults.rule(MotionAlertKind.SPEED).levels.isEmpty(), "fixture check: speed ships off")

        val on = draft(kind = MotionAlertKind.SPEED).withEnabled(true)

        assertTrue(on.isOn, "the switch must actually stay on")
        assertEquals(1, on.levels.size)
        assertEquals("", on.levels.single().text)
        assertTrue(on.hasInvalidThreshold, "and the save must wait for the number")
    }

    @Test
    fun `re-affirming a switch that is already on does not overwrite tuned numbers`() {
        val tuned = draft(levels = listOf(AlertLevelDraft("72")))

        assertEquals(tuned, tuned.withEnabled(true))
    }

    @Test
    fun `removing the last level is how an alert reads off`() {
        val one = draft(levels = listOf(AlertLevelDraft("80")))

        val gone = one.withLevelRemoved(0)

        assertFalse(gone.isOn)
        assertTrue(gone.toRule().isOff)
    }

    // ------------------------------------------------------------ availability

    /**
     * **The single easiest mistake in this task.** A rider opening settings while
     * disconnected resolves every sensor-backed kind to
     * [AlertAvailability.Unknown]; treating that as unavailable makes the whole
     * screen read-only in the normal case.
     */
    @Test
    fun `Unknown is fully editable — a disconnected vehicle is not a missing sensor`() {
        val unknown = draft(kind = MotionAlertKind.MOTOR_TEMP, availability = AlertAvailability.Unknown)

        assertTrue(unknown.isEditable)

        val edited = unknown.withEnabled(true).withThreshold(0, "125").withLevelAdded()

        assertEquals(listOf("125", "125"), edited.levels.map { it.text })
        assertTrue(edited.canAddLevel, "a third level must still be addable")
    }

    /**
     * Availability is a fact, not a preference (F §10) — and the pure layer, not
     * the screen, is what enforces it, so a mis-wired button cannot corrupt data.
     */
    @Test
    fun `an unavailable kind refuses every edit`() {
        val blocked = draft(
            kind = MotionAlertKind.DUTY,
            availability = AlertAvailability.Unavailable(
                AlertUnavailableReason.ControllerReportsNoDuty(ControllerType.KELLY)
            ),
            levels = listOf(AlertLevelDraft("80"))
        )

        assertFalse(blocked.isEditable)
        assertFalse(blocked.canAddLevel)
        assertEquals(blocked, blocked.withEnabled(false))
        assertEquals(blocked, blocked.withEnabled(true))
        assertEquals(blocked, blocked.withThreshold(0, "99"))
        assertEquals(blocked, blocked.withLevelEnabled(0, false))
        assertEquals(blocked, blocked.withLevelAdded())
        assertEquals(blocked, blocked.withLevelRemoved(0))
    }

    // ----------------------------------------------------------------- levels

    @Test
    fun `a fourth level cannot be added`() {
        val full = draft(
            levels = listOf(AlertLevelDraft("70"), AlertLevelDraft("80"), AlertLevelDraft("90"))
        )
        assertEquals(AlertRule.MAX_LEVELS, full.levels.size, "fixture check: this is the cap")

        assertFalse(full.canAddLevel)
        assertEquals(full, full.withLevelAdded())
    }

    /**
     * A blank new row would be invalid the instant it appeared and would block
     * the save on a field the rider had not looked at yet. Seeding from the row
     * above is valid and already in order.
     */
    @Test
    fun `an added level is seeded from the row above so it is valid and in order`() {
        val two = draft(levels = listOf(AlertLevelDraft("80"), AlertLevelDraft("90")))

        val three = two.withLevelAdded()

        assertEquals(listOf("80", "90", "90"), three.levels.map { it.text })
        assertFalse(three.hasInvalidThreshold)
    }

    @Test
    fun `muting one level keeps its number and does not shift the others`() {
        val three = draft(
            levels = listOf(AlertLevelDraft("70"), AlertLevelDraft("80"), AlertLevelDraft("90"))
        ).withLevelEnabled(1, false)

        val rule = three.toRule()

        assertEquals(listOf(70f, 80f, 90f), rule.levels.map { it.thresholdValue })
        assertEquals(listOf(true, false, true), rule.levels.map { it.enabled })
    }

    // ------------------------------------------------------------- committing

    /**
     * F §10.2 left "reorder or refuse" open; Task 2 chose reorder and this is the
     * caller that depends on it. Nothing is rejected and nothing is dropped.
     */
    @Test
    fun `out-of-order input is sorted, not rejected, and carries its mute flags along`() {
        val typed = draft(
            levels = listOf(
                AlertLevelDraft("90", enabled = false),
                AlertLevelDraft("80"),
                AlertLevelDraft("100")
            )
        )

        val rule = typed.toRule()

        assertEquals(listOf(80f, 90f, 100f), rule.levels.map { it.thresholdValue })
        assertEquals(
            listOf(true, false, true),
            rule.levels.map { it.enabled },
            "the mute must follow the number it was typed against, not the position"
        )
    }

    /** The invariant that makes the save button's block worth having. */
    @Test
    fun `a half-typed row is flagged rather than silently dropped`() {
        val typing = draft(levels = listOf(AlertLevelDraft("80"), AlertLevelDraft("")))

        assertTrue(typing.hasInvalidThreshold)
        assertEquals(
            listOf(80f),
            typing.toRule().levels.map { it.thresholdValue },
            "toRule drops it — which is exactly why the screen must not let it get here"
        )
    }

    @Test
    fun `an all-valid draft is not flagged`() {
        assertFalse(draft(levels = listOf(AlertLevelDraft("80"), AlertLevelDraft("90"))).hasInvalidThreshold)
    }

    // ------------------------------------------------------------- assembling

    @Test
    fun `every kind gets a row, in enum order, always`() {
        val drafts = alertDraftsFor(vehicle(), availability = emptyMap())

        assertEquals(MotionAlertKind.entries.toList(), drafts.map { it.kind })
    }

    /**
     * A gate that was never evaluated must not read as "your hardware lacks
     * this" — and must not lock the rider out either. Unknown is both.
     */
    @Test
    fun `a kind missing from the availability map falls back to Unknown, not Unavailable`() {
        val drafts = alertDraftsFor(vehicle(), availability = emptyMap())

        assertTrue(drafts.all { it.availability == AlertAvailability.Unknown })
        assertTrue(drafts.all { it.isEditable })
    }

    @Test
    fun `a never-configured vehicle opens on the shipped defaults, not on silence`() {
        val drafts = alertDraftsFor(vehicle(motionAlerts = null), availability = emptyMap())

        val duty = drafts.single { it.kind == MotionAlertKind.DUTY }
        assertEquals(
            AlarmDefaults.rule(MotionAlertKind.DUTY).levels.map { formatThreshold(it.thresholdValue) },
            duty.levels.map { it.text }
        )
        assertTrue(duty.isOn)
    }

    @Test
    fun `a rider who silenced everything opens on silence, not on the defaults resurrected`() {
        val silenced = MotionAlertKind.entries.map { AlertRule(it, emptyList()) }

        val drafts = alertDraftsFor(vehicle(motionAlerts = silenced), availability = emptyMap())

        assertTrue(drafts.none { it.isOn })
    }

    /**
     * The rider tuned these numbers before swapping to hardware without the
     * sensor. Dropping them on save would erase work they cannot get back, and
     * nothing can sound off them anyway — `armedRules` is the gate.
     */
    @Test
    fun `committing keeps an unavailable kind's numbers`() {
        val drafts = listOf(
            draft(
                kind = MotionAlertKind.DUTY,
                availability = AlertAvailability.Unavailable(
                    AlertUnavailableReason.ControllerReportsNoDuty(ControllerType.KELLY)
                ),
                levels = listOf(AlertLevelDraft("77"))
            )
        )

        val rules = commitRules(drafts)

        assertEquals(1, rules.size)
        assertEquals(listOf(77f), rules.single().levels.map { it.thresholdValue })
    }

    @Test
    fun `an untouched screen commits exactly what it opened with`() {
        val v = vehicle(motionAlerts = null)
        val drafts = alertDraftsFor(v, availability = emptyMap())

        val committed = commitRules(drafts)

        assertEquals(v.motionAlertRules, committed)
        assertNotNull(committed.singleOrNull { it.kind == MotionAlertKind.SPEED })
    }
}

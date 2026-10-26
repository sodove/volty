package ru.sodovaya.volty.presentation.alerts

import ru.sodovaya.volty.domain.alert.AlarmDefaults
import ru.sodovaya.volty.domain.alert.AlertAvailability
import ru.sodovaya.volty.domain.alert.AlertLevel
import ru.sodovaya.volty.domain.alert.AlertRule
import ru.sodovaya.volty.domain.alert.MotionAlertKind
import ru.sodovaya.volty.domain.alert.isConfigurable
import ru.sodovaya.volty.domain.alert.sortedLevels
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.motionAlertRules
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The whole decision content of the per-vehicle alert editor (F §10.2, §11.2),
 * kept out of `@Composable` on purpose: Compose UI is not unit-testable in this
 * repo (no Robolectric, no instrumented tests), so anything that *decides*
 * something lives here where a test can reach it and the screen stays a renderer.
 *
 * ### The one representation of "off"
 *
 * `F §10.2` forbids two ways to say the same thing, so this editor has **no
 * per-kind on/off flag**. A kind is on exactly when [AlertKindDraft.levels] is
 * non-empty ([isOn]) — the switch reads that and writes it, and nothing else can
 * disagree with it because nothing else exists.
 *
 * [AlertKindDraft.stashed] is not a second answer to that question: it holds the
 * rows the switch just cleared so flipping back restores the rider's tuned
 * numbers instead of the app's defaults. It is never persisted, never consulted
 * by [isOn], and a draft whose [AlertKindDraft.levels] are empty reads as off no
 * matter what is stashed.
 *
 * ### Text, not Float
 *
 * A level's threshold is held as the rider's **raw text**, all the way to the
 * save button. Holding a parsed `Float?` would make "3." and "-" unrepresentable
 * mid-typing, and — worse — [AlertLevel] throws on a non-finite value, so a
 * screen that parsed eagerly could crash on a keystroke. [parseThreshold] is the
 * single boundary where text becomes a number, and it is the only thing that ever
 * constructs an [AlertLevel] here.
 */

/**
 * Text → threshold, or null when the text is not a usable threshold.
 *
 * **This is the guarantee that [AlertLevel]'s `require(isFinite())` never fires.**
 * Every rejection below is reachable from a real keyboard or clipboard:
 *
 *  - `""` / `"  "` — the field the rider just cleared to retype;
 *  - `"-"`, `"."`, `"12ab"`, a pasted word — `toFloatOrNull` says no;
 *  - `"NaN"`, `"Infinity"` — accepted by `toFloatOrNull` (they are in the
 *    `Float.valueOf` grammar) and rejected here. A NaN level can never activate
 *    and never release while [AlertRule.isOff] stays false: the screen would
 *    show the alert armed and it would be permanently, silently dead;
 *  - `"1e999"` — parses to `+Infinity` rather than failing, which is exactly the
 *    overflow a `toFloatOrNull()` null-check alone would wave through;
 *  - a negative number — all four kinds are *upper* limits on quantities that do
 *    not go negative in practice, and a motor-temperature alarm set to −5 °C
 *    would sound permanently from the moment it was saved.
 *
 * A comma is accepted as a decimal separator: the Russian keyboard's decimal key
 * types one, and rejecting it would make the field unusable for its own audience.
 *
 * [String.trim] is doing real work despite `toFloatOrNull` tolerating ASCII
 * spaces of its own: its grammar allows only `\u0000`..` `, while `trim`
 * also strips the **non-breaking** space a paste from a web page brings with it.
 * There is deliberately no `isEmpty` short-circuit — `"".toFloatOrNull()` is
 * already null, and a second gate saying the same thing is a branch no test could
 * ever tell from its absence.
 */
fun parseThreshold(raw: String): Float? {
    val value = raw.trim().replace(',', '.').toFloatOrNull() ?: return null
    if (!value.isFinite()) return null
    if (value < 0f) return null
    return value
}

/**
 * Threshold → the text the field starts out showing. Whole numbers lose the
 * `.0` that `Float.toString()` insists on, because "80.0 %" is not how a rider
 * writes a duty limit and they would have to delete two characters to edit it.
 *
 * No non-finite guard: `roundToInt` saturates rather than throwing, the
 * comparison below is false for NaN, and the only callers pass an
 * [AlertLevel.thresholdValue] that is finite by construction — a guard nothing
 * could reach is a guard no test could kill.
 */
fun formatThreshold(value: Float): String {
    val rounded = value.roundToInt()
    return if (abs(value - rounded) < 1e-4f) rounded.toString() else value.toString()
}

/** One editable step: the rider's raw text plus its own mute switch. */
data class AlertLevelDraft(
    val text: String,
    val enabled: Boolean = true
) {
    /** The parsed threshold, or null while the text is not (yet) a usable number. */
    val threshold: Float? get() = parseThreshold(text)

    val isValid: Boolean get() = threshold != null
}

/**
 * One alert kind as the editor holds it: what the hardware says about it, and
 * the rider's rows.
 *
 * Every kind gets one of these, **including the ones the hardware cannot supply**
 * (F §10): an absent row leaves the rider wondering, a greyed row with its reason
 * teaches them what their hardware measures.
 */
data class AlertKindDraft(
    val kind: MotionAlertKind,
    val availability: AlertAvailability,
    val levels: List<AlertLevelDraft>,
    /** See the file header: an undo buffer for the switch, never an "off" flag. */
    val stashed: List<AlertLevelDraft> = emptyList(),
    /**
     * The number this kind compares against is the app's per-wheel ESTIMATE ([estimatedKinds]), which under load
     * can read LOWER than the truth. Display-only: never part of [editedLevels] or of what is saved.
     */
    val estimated: Boolean = false
)

/** The «оценка: под нагрузкой может занижать» caption: an estimate that is actually on offer ([AlertAvailability.Available]). */
val AlertKindDraft.showsEstimateCaption: Boolean get() = estimated && availability == AlertAvailability.Available

/**
 * May the rider touch this kind at all?
 *
 * [AlertAvailability.isConfigurable], **not** `is Available`: a rider opening
 * settings while disconnected — the normal case — resolves every sensor-backed
 * kind to [AlertAvailability.Unknown], and reading that as "unavailable" would
 * make the whole screen read-only whenever the vehicle is not connected.
 */
val AlertKindDraft.isEditable: Boolean get() = availability.isConfigurable

/** On iff there is at least one row. The only definition of on/off in this editor. */
val AlertKindDraft.isOn: Boolean get() = levels.isNotEmpty()

val AlertKindDraft.canAddLevel: Boolean get() = isEditable && levels.size < AlertRule.MAX_LEVELS

/** A row the rider has not finished typing. Blocks the save — see this file's header. */
val AlertKindDraft.hasInvalidThreshold: Boolean get() = levels.any { !it.isValid }

/**
 * The switch.
 *
 * On → the rider's stashed rows if the switch itself cleared them, else this
 * kind's [AlarmDefaults]. Speed ships with *no* default (F §10.1: a speed limit
 * is meaningless without a rider-chosen number), so it gets one **blank** row —
 * which is invalid, blocks the save, and makes the screen ask for the number
 * instead of inventing one. Off → clear the rows, keeping them for the way back.
 *
 * Already-on stays untouched: re-affirming a switch must not overwrite tuned
 * numbers with defaults.
 */
fun AlertKindDraft.withEnabled(on: Boolean): AlertKindDraft {
    if (!isEditable) return this
    if (on == isOn) return this
    return if (on) {
        copy(
            levels = stashed.ifEmpty { defaultLevelDrafts(kind) }.ifEmpty { listOf(AlertLevelDraft("")) },
            stashed = emptyList()
        )
    } else {
        copy(levels = emptyList(), stashed = levels)
    }
}

// The three index-taking editors below range-check nothing on purpose: an index
// outside `levels` simply matches no row, so `mapIndexed`/`filterIndexed` return
// an equal draft. An explicit bounds guard would be a branch no test could tell
// from its absence.

fun AlertKindDraft.withThreshold(index: Int, text: String): AlertKindDraft {
    if (!isEditable) return this
    return copy(levels = levels.mapIndexed { i, level -> if (i == index) level.copy(text = text) else level })
}

fun AlertKindDraft.withLevelEnabled(index: Int, enabled: Boolean): AlertKindDraft {
    if (!isEditable) return this
    return copy(levels = levels.mapIndexed { i, level -> if (i == index) level.copy(enabled = enabled) else level })
}

/**
 * Add a step, seeded with the last row's text so the new row is immediately
 * valid and already in order. [AlertRule] allows two equal thresholds, so
 * 80 / 90 / 90 is a legal intermediate state the rider then edits — where a blank
 * seed would silently block the save until they noticed the empty field.
 *
 * With no rows to copy (the switch just turned a defaults-less kind on) the seed
 * is blank, which is the honest state: there is no number yet.
 */
fun AlertKindDraft.withLevelAdded(): AlertKindDraft {
    if (!canAddLevel) return this
    return copy(levels = levels + AlertLevelDraft(levels.lastOrNull()?.text ?: ""))
}

/** Remove a step. Removing the last one leaves an empty list — which *is* "off". */
fun AlertKindDraft.withLevelRemoved(index: Int): AlertKindDraft {
    if (!isEditable) return this
    return copy(levels = levels.filterIndexed { i, _ -> i != index })
}

/**
 * Commit — **through [sortedLevels]**, so a rider who typed 90 / 80 / 100 gets
 * 80 / 90 / 100 rather than a rejection or a dropped row (F §10.2 left reorder
 * vs refuse open; Task 2 chose reorder and this is the caller that relies on it).
 *
 * Unparseable rows are dropped rather than crashing, but the screen never lets
 * one reach here: [hasInvalidThreshold] blocks the save first, precisely so that
 * a half-typed row cannot silently turn a kind off by emptying its list.
 */
fun AlertKindDraft.toRule(): AlertRule = AlertRule(
    kind = kind,
    levels = sortedLevels(levels.mapNotNull { row -> row.threshold?.let { AlertLevel(it, row.enabled) } })
)

/** This kind's shipped opening position, as editor rows. */
fun defaultLevelDrafts(kind: MotionAlertKind): List<AlertLevelDraft> =
    AlarmDefaults.rule(kind).levels.map { AlertLevelDraft(formatThreshold(it.thresholdValue), it.enabled) }

/**
 * The editor's initial state for [vehicle] — **one draft per kind, in enum
 * order, always**, so the screen renders the full list without deciding anything.
 *
 * Rules come from [motionAlertRules], so a never-configured vehicle opens on
 * [AlarmDefaults] rather than on an empty (= silenced) screen.
 *
 * A kind missing from [availability] falls back to [AlertAvailability.Unknown],
 * which is the only safe answer to "we did not evaluate this": editable, so the
 * rider is never locked out, and never armable, so nothing can sound off the back
 * of an unevaluated gate.
 */
fun alertDraftsFor(
    vehicle: Vehicle,
    availability: Map<MotionAlertKind, AlertAvailability>,
    estimated: Set<MotionAlertKind> = emptySet()
): List<AlertKindDraft> {
    val configured = vehicle.motionAlertRules.associateBy { it.kind }
    return MotionAlertKind.entries.map { kind ->
        AlertKindDraft(
            kind = kind,
            availability = availability[kind] ?: AlertAvailability.Unknown,
            estimated = kind in estimated,
            levels = configured[kind]?.levels.orEmpty().map {
                AlertLevelDraft(formatThreshold(it.thresholdValue), it.enabled)
            }
        )
    }
}

/**
 * The part of the drafts a Save would actually write, keyed by kind — the value a
 * dirty check compares against its baseline.
 *
 * Deliberately narrower than the drafts themselves:
 *
 *  - **availability is excluded.** It is a fact about the hardware that the
 *    screen re-derives whenever a link comes up mid-edit, and including it would
 *    report an untouched screen as edited the moment the vehicle connected;
 *  - **[AlertKindDraft.stashed] is excluded.** It is the switch's undo buffer,
 *    never persisted, so a rider who toggles a kind off and back on has changed
 *    nothing and must read as clean.
 *
 * The rider's raw *text* is what is compared, not the parsed threshold: "80" and
 * "80.0" are the same rule but not the same screen, and a half-typed "8" is an
 * edit in progress that has to count as one — otherwise backing out of it would
 * be silent again.
 */
fun editedLevels(drafts: List<AlertKindDraft>): Map<MotionAlertKind, List<AlertLevelDraft>> =
    drafts.associate { it.kind to it.levels }

/**
 * What gets persisted: every kind's rule, including the unavailable ones.
 *
 * An unavailable kind keeps the numbers it was loaded with rather than being
 * dropped — the rider tuned them, and swapping to hardware that *does* have the
 * sensor must not have quietly erased them in the meantime. Nothing can sound off
 * them either way: `armedRules` is the gate, and it drops anything not
 * `isArmable`.
 */
fun commitRules(drafts: List<AlertKindDraft>): List<AlertRule> = drafts.map { it.toRule() }

package ru.sodovaya.volty.presentation.ride

import kotlin.math.min

/**
 * Grows a session's high-water mark from a stream of individual samples, but will not COMMIT a
 * rising reading as the new peak until [CONFIRM_SAMPLES] consecutive samples have corroborated
 * it — the guard `ClassicDialSpecs`'s current/power auto-scale needs and does not get from the
 * plain `if (sample > max) max = sample` the speed tracker still uses (`RideDashboardScreen`'s
 * `sessionMaxSpeedKmh`).
 *
 * ## Why a debounce, not just `ClassicDialSpecs`'s ceiling
 *
 * `ClassicDialSpecs.currentDisplayMax`/`powerDisplayMax` cap how far the DISPLAYED scale can grow
 * (see their `tickCapCeiling`), but a ceiling alone still lets ONE bad decode — a single spurious
 * frame reporting 5000 A — immediately peg the dial at that ceiling for the rest of the ride.
 * Capped is better than uncapped, but it is still a false peg nothing short of switching vehicles
 * clears. This tracker keeps a rising reading from being committed to [committed] AT ALL until it
 * has survived [CONFIRM_SAMPLES] consecutive samples, so a one-frame glitch never reaches the
 * scale in the first place — `ClassicDialSpecs` never even sees it.
 *
 * ## Why "N consecutive samples", not a percentile or a decay
 *
 * A percentile needs a retained window and a choice of its length; a decay turns "never shrinks"
 * into "shrinks slowly", which this project keeps as a hard, deliberate invariant (a scale that
 * moves during a ride is unreadable — see `ClassicDialSpecs`'s own class doc). Consecutive-sample
 * confirmation needs neither: it holds no history beyond one counter and one running value, commits
 * in whole numbers of samples (easy to reason about at the VESC's own ~5-10 Hz poll rate,
 * `B-vesc-dashboard.md` §3), and — like every other tracker on this screen — never lets [committed]
 * decrease.
 *
 * ## Why the confirmed value is the MINIMUM of the run, not its maximum
 *
 * The naive version of "N consecutive samples above the current peak" — track the largest sample
 * seen during the streak, commit that once the streak reaches N — does not actually reject a
 * single outlier: two ordinary elevated readings plus one wild spike is still three consecutive
 * samples above the old peak, and the naive version would commit the SPIKE. Committing the
 * SMALLEST value seen during the confirmed run instead means every one of the N samples has to
 * independently clear that number — a lone spike can raise the running minimum only if it is
 * itself the smallest of the three, i.e. only if the other two are just as high, which is no
 * longer a "spurious single sample". The trade-off is conservatism: a genuinely climbing reading
 * is confirmed a step behind its true instantaneous peak rather than a step ahead of it, which is
 * the safe direction to be wrong in for a display that must never lie high.
 *
 * [CONFIRM_SAMPLES] = 3: long enough that one corrupt frame — the concrete failure mode this
 * exists for — cannot alone raise [committed]; short enough (three poll intervals, a few hundred
 * milliseconds at the VESC's own poll rate) that a genuine acceleration or regen event is still
 * confirmed within a ride-relevant instant rather than lagging visibly.
 */
data class SessionPeakTracker(
    /** The confirmed session high-water mark — what a caller feeds to `ClassicDialSpecs`. */
    val committed: Float = 0f,
    private val runMinimum: Float = 0f,
    private val runLength: Int = 0
) {

    /** Folds one new (already non-negative, e.g. `abs`-ed) sample in. */
    fun accept(sample: Float): SessionPeakTracker {
        if (sample <= committed) {
            // Back at or under the confirmed peak — whatever run was building is no longer
            // consecutive, so it is discarded rather than allowed to resume later.
            return if (runLength == 0) this else SessionPeakTracker(committed = committed)
        }
        val nextRunMinimum = if (runLength == 0) sample else min(runMinimum, sample)
        val nextRunLength = runLength + 1
        return if (nextRunLength >= CONFIRM_SAMPLES) {
            SessionPeakTracker(committed = nextRunMinimum)
        } else {
            SessionPeakTracker(committed = committed, runMinimum = nextRunMinimum, runLength = nextRunLength)
        }
    }

    companion object {
        const val CONFIRM_SAMPLES = 3
    }
}

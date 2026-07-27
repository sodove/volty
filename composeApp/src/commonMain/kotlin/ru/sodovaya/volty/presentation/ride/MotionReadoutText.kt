package ru.sodovaya.volty.presentation.ride

/**
 * **The one marker every gauge shows for a value nobody has observed** — the
 * rendering half of [ru.sodovaya.volty.domain.stats.MotionReadings]' contract
 * (`G §9`).
 *
 * An em dash rather than `0`, `?`, `n/a` or an empty string, because the whole
 * point is to be *visibly different from a real low reading*: `0`, `0.0` and
 * `0.0 kW` are exactly the readings this contract exists to stop us claiming,
 * and a blank looks like a rendering bug rather than a statement. It is also
 * already what [ru.sodovaya.volty.domain.model.BmsData.socKnown] and the two
 * temperature flags render, so the new cases arrive looking like the ones a
 * rider has seen since Part B rather than like a fourth convention.
 *
 * Language-neutral on purpose: it needs no `values/` + `values-ru/` pair, and a
 * translated "нет данных" would not fit inside a dial's number ring.
 */
const val UNKNOWN_READOUT: String = "—"

/**
 * [format] this reading, or [UNKNOWN_READOUT] when it is null.
 *
 * The single expression all three renderers share, so "unknown renders as a
 * dash" is one decision rather than one per gauge — the shape the three
 * confident zeros of `G §9` grew in.
 */
inline fun Float?.readoutOr(format: (Float) -> String): String =
    if (this == null) UNKNOWN_READOUT else format(this)

package ru.sodovaya.volty.domain.history

/** Missing evidence never acquires a value from a producer's placeholder. */
fun historyValue(value: Double, known: Boolean): Double? = value.takeIf { known && it.isFinite() }

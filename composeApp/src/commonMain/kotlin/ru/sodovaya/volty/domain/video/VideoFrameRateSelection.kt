package ru.sodovaya.volty.domain.video

/** Exact normal-session ranges only. High-speed capture cannot carry our CameraEffect. */
fun videoFrameRateChoices(ranges: List<Pair<Int, Int>>): List<Int> =
    listOf(60, 30).filter { fps -> ranges.any { it.first == fps && it.second == fps } }

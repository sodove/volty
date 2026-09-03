package ru.sodovaya.volty.domain.social

data class SharingDurationOption(
    val hours: Long,
    val ttlMillis: Long,
    val label: String,
)

object SharingDurationPolicy {
    val maxTtlMillis: Long = LocationSharePolicy.maxTtlMillis

    val options: List<SharingDurationOption> = listOf(1L, 2L, 4L, 8L, 24L).map { hours ->
        SharingDurationOption(
            hours = hours,
            ttlMillis = hours * HOUR_MILLIS,
            label = when {
                hours % 10L == 1L && hours % 100L != 11L -> "$hours час"
                hours % 10L in 2L..4L && hours % 100L !in 12L..14L -> "$hours часа"
                else -> "$hours часов"
            },
        )
    }

    private const val HOUR_MILLIS = 60L * 60L * 1_000L
}

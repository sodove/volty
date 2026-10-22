package ru.sodovaya.volty.presentation.picker

import kotlin.time.ExperimentalTime
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import ru.sodovaya.volty.domain.model.Vehicle

data class GarageLayout(val hero: Vehicle?, val others: List<Vehicle>)

/**
 * The hero is the vehicle the rider most likely means: a pinned one, else one in range, else the
 * last connected. The rest follow: in range first, then most recently connected.
 */
@OptIn(ExperimentalTime::class)
fun garageLayout(saved: List<Vehicle>, inRangeIds: Set<String>): GarageLayout {
    val ordered = saved.sortedWith(
        compareByDescending<Vehicle> { it.isPinned }
            .thenByDescending { it.id in inRangeIds }
            .thenByDescending { it.lastConnectedAt?.toEpochMilliseconds() ?: Long.MIN_VALUE }
    )
    return GarageLayout(ordered.firstOrNull(), ordered.drop(1))
}

sealed interface GarageWhen {
    data class Today(val hour: Int, val minute: Int) : GarageWhen
    data class Yesterday(val hour: Int, val minute: Int) : GarageWhen
    data class DaysAgo(val days: Int) : GarageWhen
    data class Date(val day: Int, val month: Int) : GarageWhen
}

/** Calendar days in the rider's zone, not 24-hour spans: 23:50 yesterday is "yesterday" at 00:10. */
fun garageWhen(epochMillis: Long, nowMillis: Long, zone: TimeZone = TimeZone.currentSystemDefault()): GarageWhen {
    val at = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(zone)
    val now = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(zone)
    val days = (now.date.toEpochDays() - at.date.toEpochDays()).toInt()
    return when {
        days <= 0 -> GarageWhen.Today(at.hour, at.minute)
        days == 1 -> GarageWhen.Yesterday(at.hour, at.minute)
        days < 7 -> GarageWhen.DaysAgo(days)
        else -> GarageWhen.Date(at.dayOfMonth, at.monthNumber)
    }
}

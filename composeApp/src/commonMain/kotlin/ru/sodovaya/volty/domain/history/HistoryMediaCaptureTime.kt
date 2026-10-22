package ru.sodovaya.volty.domain.history

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.toInstant

/** EXIF local clock is usable only with its explicit UTC offset. Never assume the phone's current zone. */
fun historyMediaCaptureTime(dateTimeOriginal: String?, offsetTimeOriginal: String?, subSeconds: String?): Long? {
    val date = dateTimeOriginal?.let(DATE_TIME_PATTERN::matchEntire) ?: return null
    val offsetText = offsetTimeOriginal?.takeIf(UTC_OFFSET_PATTERN::matches) ?: return null
    if (subSeconds != null && !SUBSECONDS_PATTERN.matches(subSeconds)) return null

    return runCatching {
        val localDateTime = LocalDateTime.parse(
            "${date.groupValues[1]}-${date.groupValues[2]}-${date.groupValues[3]}" +
                "T${date.groupValues[4]}:${date.groupValues[5]}:${date.groupValues[6]}"
        )
        val utcOffset = UtcOffset.parse(offsetText)
        val epochMillis = localDateTime.toInstant(utcOffset).toEpochMilliseconds()
        val subsecondMillis = subSeconds?.padEnd(3, '0')?.take(3)?.toInt() ?: 0
        epochMillis + subsecondMillis
    }.getOrNull()
}

private val DATE_TIME_PATTERN = Regex("([0-9]{4}):([0-9]{2}):([0-9]{2}) ([0-9]{2}):([0-9]{2}):([0-9]{2})")
private val UTC_OFFSET_PATTERN = Regex("[+-][0-9]{2}:[0-9]{2}")
private val SUBSECONDS_PATTERN = Regex("[0-9]{1,9}")

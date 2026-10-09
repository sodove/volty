package ru.sodovaya.volty.backend.nearby

import java.net.http.HttpHeaders
import java.time.Clock
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal fun fcmRetryDelay(headers: HttpHeaders, clock: Clock, minimum: Long): Long {
    val raw = headers.firstValue("Retry-After").orElse("").trim()
    val seconds = if (raw.isNotEmpty() && raw.all { it in '0'..'9' }) raw.toLongOrNull() ?: Long.MAX_VALUE else null
    // Excessive server delays expire short-lived hints rather than retrying early.
    val delay = if (seconds != null) {
        if (seconds > Long.MAX_VALUE / 1000) Long.MAX_VALUE else seconds * 1000
    }
    else runCatching {
        val deadline = ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        Math.subtractExact(deadline, clock.millis())
    }.getOrDefault(minimum)
    return maxOf(minimum, delay)
}

package ru.sodovaya.volty.data.history

import java.io.DataOutputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import ru.sodovaya.volty.domain.history.*

internal actual fun historyBatchFingerprint(batch: HistoryBatch): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val out = DataOutputStream(DigestOutputStream(object : OutputStream() {
        override fun write(value: Int) = Unit
        override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
    }, digest))
    fun text(value: String) {
        // Preserve every UTF-16 code unit. UTF-8 replacement aliases malformed surrogate IDs.
        out.writeInt(value.length)
        value.forEach { out.writeChar(it.code) }
    }
    fun number(value: Double?) {
        out.writeBoolean(value != null)
        if (value != null) out.writeDouble(if (value == 0.0) 0.0 else value)
    }
    fun tick(value: HistoryTick) {
        out.writeLong(value.epochMillis)
        out.writeLong(value.elapsedMillis)
        out.writeLong(value.clockGeneration)
    }
    fun key(value: HistorySeriesKey) {
        text(value.sourceId)
        text(value.metricId)
        out.writeInt(value.cellIndex ?: -1)
    }
    fun sample(value: HistorySample) {
        tick(value.tick)
        key(value.key)
        number(value.value)
        text(value.unknown?.name.orEmpty())
    }
    // Unscoped receipts retain their exact v1/v2 identity. The scoped format
    // always encodes GPS providers, including unknown, to keep one unambiguous v3 layout.
    val includesSourceScope = batch.markers.any { it.sourceId != null }
    val includesGpsProvider = includesSourceScope || batch.gps.any { it.provider != null }
    text(when {
        includesSourceScope -> "history-batch-v3-source-scope"
        includesGpsProvider -> "history-batch-v2-gps-provider"
        else -> "history-batch-v1"
    })
    text(batch.rideId)
    out.writeInt(batch.samples.size)
    batch.samples.forEach(::sample)
    out.writeInt(batch.gps.size)
    batch.gps.forEach {
        tick(it.tick)
        number(it.lat)
        number(it.lon)
        number(it.accuracyMeters)
        number(it.heading)
        if (includesGpsProvider) text(it.provider?.name.orEmpty())
    }
    out.writeInt(batch.markers.size)
    batch.markers.forEach {
        tick(it.tick); text(it.kind.name)
        if (includesSourceScope) text(it.sourceId.orEmpty())
    }
    out.writeInt(batch.buckets.size)
    batch.buckets.forEach {
        key(it.key)
        out.writeLong(it.slot)
        tick(it.first)
        tick(it.last)
        out.writeBoolean(it.minimum != null)
        it.minimum?.let(::sample)
        out.writeBoolean(it.maximum != null)
        it.maximum?.let(::sample)
        number(it.energyWh)
        out.writeLong(it.sampleCount)
        out.writeBoolean(it.complete)
    }
    out.flush()
    return digest.digest().joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
}

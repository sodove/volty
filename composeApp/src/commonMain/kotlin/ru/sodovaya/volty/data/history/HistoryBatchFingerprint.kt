package ru.sodovaya.volty.data.history

import ru.sodovaya.volty.domain.history.HistoryBatch

/** Durable payload identity, including mutable bucket snapshots, without storing a second payload. */
internal expect fun historyBatchFingerprint(batch: HistoryBatch): String

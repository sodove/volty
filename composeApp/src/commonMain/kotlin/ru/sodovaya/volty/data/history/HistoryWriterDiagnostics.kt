package ru.sodovaya.volty.data.history

data class HistoryWriterDiagnostics(
    val pendingEvents: Long = 0,
    val pendingBytes: Long = 0,
    val committedEvents: Long = 0,
    val droppedEvents: Long = 0,
    val failure: String? = null,
    val accepting: Boolean = false,
    val closed: Boolean = false,
)

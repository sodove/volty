package ru.sodovaya.volty.domain.model

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
data class ControllerState(
    val controller: Controller,
    val data: ControllerData,
    val isOnline: Boolean = false,
    val lastSeenAt: Instant? = null
)

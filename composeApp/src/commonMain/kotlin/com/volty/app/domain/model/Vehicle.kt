package com.volty.app.domain.model

import kotlinx.datetime.Instant

data class Vehicle(
    val id: String,
    val name: String,
    val iconKey: String,
    val bmsType: BmsType,
    val bmsAddress: String,
    val chemistry: Chemistry,
    val cellCount: Int? = null,
    val averagingWindowMin: Int = 5,
    val alertConfig: AlertConfig = AlertConfig(),
    val createdAt: Instant,
    val lastConnectedAt: Instant? = null,
    val isPinned: Boolean = false
)

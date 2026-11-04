package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.domain.history.HistorySource
import ru.sodovaya.volty.domain.history.HistorySourceKind

/**
 * The physical (BLE) sources of a recorded registry. Since 2026-11-04 a ride with no motion source also registers the
 * phone's GPS speed source (`HistoryGpsSpeed.kt`); these tests are about physical bindings, so they look past it.
 */
internal fun List<HistorySource>.physical(): List<HistorySource> = filterNot { it.kind == HistorySourceKind.GPS }

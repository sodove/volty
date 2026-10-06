package ru.sodovaya.volty.presentation.ride

import ru.sodovaya.volty.presentation.root.nearby.NearbyChannelMapState

internal data class LightChannelRideUiState(val channelId: String, val positionCount: Int?)
internal fun lightChannelRideUiState(map: NearbyChannelMapState): LightChannelRideUiState? {
    val channel = map.channelId ?: return null
    if (map.operation == null || map.session == null) return null
    return LightChannelRideUiState(channel,map.points.size.takeIf {map.observed})
}

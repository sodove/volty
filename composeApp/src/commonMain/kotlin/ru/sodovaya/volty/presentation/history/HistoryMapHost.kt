package ru.sodovaya.volty.presentation.history

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.sodovaya.volty.domain.history.*
import ru.sodovaya.volty.presentation.map.NavigationMapScene

data class HistoryHostedMap(val source: HistoryFavoriteSource, val range: HistoryRange, val scene: NavigationMapScene)

/** One root MapView; history contributes recorded geometry through this slot. */
class HistoryMapHost(private val owner: Any, val viewportGeneration: Long = 0) {
    init { require(viewportGeneration >= 0) }
    private val mutableState = MutableStateFlow<HistoryHostedMap?>(null)
    val state = mutableState.asStateFlow()
    fun publish(publisher: Any, frame: HistoryHostedMap) {
        if (publisher !== owner) return
        val scene = frame.scene
        mutableState.value = frame.takeIf {
            scene.history?.identity == HistoryMapIdentity(frame.source.rideId, viewportGeneration) && scene.ownFix == null &&
                scene.trail.isEmpty() && scene.participantMarkers.isEmpty() && scene.routes.isEmpty() && scene.destination == null
        }
    }
    fun clear(publisher: Any) { if (publisher === owner) mutableState.value = null }
}

fun qualifiedHistoryMap(frame: HistoryHostedMap?, source: HistoryFavoriteSource?, range: HistoryRange?): NavigationMapScene? =
    frame?.takeIf { source != null && range != null && it.source == source && it.range == range }?.scene

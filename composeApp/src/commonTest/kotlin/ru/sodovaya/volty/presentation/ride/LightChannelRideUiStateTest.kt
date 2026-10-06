package ru.sodovaya.volty.presentation.ride

import kotlin.test.*
import ru.sodovaya.volty.presentation.root.nearby.*
import ru.sodovaya.volty.domain.voice.*

class LightChannelRideUiStateTest {
    private val active=NearbyChannelMapState(VoiceOperationId("original"),"channel",
        VoiceSessionTag(VoiceSessionId("session"),VoiceGeneration(3)),true,
        listOf(NearbyChannelMapPoint("actor",55.0,60.0,4.0)))
    @Test fun actual_points_are_counted_only_after_an_observation() {
        assertEquals(LightChannelRideUiState("channel",1),lightChannelRideUiState(active))
        assertEquals(LightChannelRideUiState("channel",0),lightChannelRideUiState(active.copy(points=emptyList())))
    }
    @Test fun populated_but_unobserved_points_do_not_become_a_zero_or_real_count() {
        assertEquals(LightChannelRideUiState("channel",null),lightChannelRideUiState(active.copy(observed=false)))
    }
    @Test fun stale_points_without_current_identity_cannot_show_a_channel_card() {
        for(value in listOf(active.copy(operation=null),active.copy(channelId=null),active.copy(session=null))) {
            assertNull(lightChannelRideUiState(value))
        }
    }
}

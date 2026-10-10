package ru.sodovaya.volty.data.voice

import ru.sodovaya.volty.domain.nearby.ObservedChannelSharingPeer
import ru.sodovaya.volty.domain.nearby.ChannelSharingMode

internal fun channelSharingPeerAtTime(peer: ObservedChannelSharingPeer, now: Long): ObservedChannelSharingPeer? =
    peer.takeIf { now < it.deadlineMillis }?.let {
        val stale=it.stale || it.freshUntilMillis?.let { deadline -> now >= deadline } == true
        if(stale) it.copy(stale=true,mode=ChannelSharingMode.LOCATION,packet=it.packet.copy(telemetry=null)) else it
    }

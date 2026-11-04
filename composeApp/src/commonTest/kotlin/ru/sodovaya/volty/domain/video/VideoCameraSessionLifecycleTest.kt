package ru.sodovaya.volty.domain.video

import kotlin.test.Test
import kotlin.test.assertEquals

class VideoCameraSessionLifecycleTest {
    @Test fun idleSleepReleasesAndWakeCreatesANewSession() {
        val calls = mutableListOf<String>()
        val lifecycle = VideoCameraSessionLifecycle({ false }, { calls += "bind"; true }, { calls += "release" }, {})
        lifecycle.started()
        lifecycle.stopped()
        lifecycle.started()
        lifecycle.started()
        assertEquals(listOf("bind", "release", "bind"), calls)
    }

    @Test fun activeSegmentsFinishBeforeTheSessionIsReplaced() {
        val calls = mutableListOf<String>()
        var busy = false
        val lifecycle = VideoCameraSessionLifecycle({ busy }, { calls += "bind"; true }, { calls += "release" }, { calls += "stop" })
        lifecycle.started()
        busy = true
        lifecycle.stopped()
        lifecycle.started()
        lifecycle.finalized()
        assertEquals(listOf("bind", "stop"), calls)
        busy = false
        lifecycle.finalized()
        assertEquals(listOf("bind", "stop", "bind"), calls)
    }

    @Test fun finalizationInBackgroundReleasesWithoutOpeningCamera() {
        val calls = mutableListOf<String>()
        var busy = false
        val lifecycle = VideoCameraSessionLifecycle({ busy }, { calls += "bind"; true }, { calls += "release" }, {})
        lifecycle.started()
        busy = true
        lifecycle.stopped()
        busy = false
        lifecycle.finalized()
        assertEquals(listOf("bind", "release"), calls)
        lifecycle.started()
        assertEquals(listOf("bind", "release", "bind"), calls)
    }

    @Test fun lateProviderCannotOpenWhileBackgroundedAndRetriesWhenAttached() {
        val calls = mutableListOf<String>()
        var attached = false
        val lifecycle = VideoCameraSessionLifecycle({ false }, { if (attached) calls += "bind"; attached }, {}, {})
        lifecycle.started()
        lifecycle.stopped()
        attached = true
        lifecycle.attached()
        assertEquals(emptyList(), calls)
        lifecycle.started()
        assertEquals(listOf("bind"), calls)
    }
}

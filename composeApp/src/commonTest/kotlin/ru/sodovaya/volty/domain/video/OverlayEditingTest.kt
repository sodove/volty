package ru.sodovaya.volty.domain.video

import kotlin.test.*

class OverlayEditingTest {
    @Test fun opacityOnRgbAddsAlphaWithoutChangingAnyColorChannel() {
        assertEquals(255, overlayColorAlpha("#112233"))
        assertEquals("#80112233", overlayColorWithAlpha("#112233", 128))
        assertEquals(128, overlayColorAlpha("#80112233"))
        assertEquals("#40112233", overlayColorWithAlpha("#80112233", 64))
    }

    @Test fun typingDecimalSurvivesIntermediateNormalizedModelEchoes() {
        var draft = OverlayTextDraft("50.0").copy(focused = true)
        draft = draft.edited("1", accepted = true).synced("1.0")
        assertEquals("1", draft.text)
        draft = draft.edited("12.", accepted = true).synced("12.0")
        assertEquals("12.", draft.text)
        draft = draft.edited("12.5", accepted = true).synced("12.5")
        assertEquals("12.5", draft.text)
        assertFalse(draft.invalid)
    }

    @Test fun invalidDraftIsLocalAndExternalEditsRefreshUnfocusedFields() {
        val invalid = OverlayTextDraft("10.0").copy(focused = true).edited("-", accepted = false)
        assertTrue(invalid.invalid)
        assertEquals("-", invalid.synced("10.0").text)
        val external = invalid.copy(focused = false).synced("20.0")
        assertEquals("20.0", external.text)
        assertFalse(external.invalid)
    }
}

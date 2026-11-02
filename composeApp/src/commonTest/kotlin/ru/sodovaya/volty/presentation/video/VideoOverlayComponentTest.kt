package ru.sodovaya.volty.presentation.video

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import kotlin.test.*
import ru.sodovaya.volty.domain.video.*

class VideoOverlayComponentTest {
    @Test fun malformedInitialSettingsAreReplacedBeforeAnySubscriberCanReadThem() {
        val invalid = OverlayDocument().let { it.copy(selected = it.selected.copy(widgets = emptyList())) }
        val component = VideoOverlayComponent(DefaultComponentContext(LifecycleRegistry()), invalid) {}
        assertEquals(OverlayDocument(), component.document.value)
    }

    @Test fun editsPersistTheSameValidatedDocumentPublishedToTheRenderer() {
        val writes = mutableListOf<OverlayDocument>()
        val initial = OverlayDocument()
        val component = VideoOverlayComponent(DefaultComponentContext(LifecycleRegistry()), initial, writes::add)
        val changed = initial.copy(selected = OverlayTemplates.all().first())
        component.change(changed)
        assertEquals(listOf(changed), writes)
        assertEquals(changed, component.document.value)
    }

    @Test fun invalidOrOversizedEditKeepsThePreviousDocumentAndNeverWrites() {
        val writes = mutableListOf<OverlayDocument>()
        val initial = OverlayDocument()
        val component = VideoOverlayComponent(DefaultComponentContext(LifecycleRegistry()), initial, writes::add)
        component.change(initial.copy(selected = initial.selected.copy(widgets = emptyList())))
        component.change(initial.copy(version = 99))
        assertEquals(initial, component.document.value)
        assertTrue(writes.isEmpty())
    }
}

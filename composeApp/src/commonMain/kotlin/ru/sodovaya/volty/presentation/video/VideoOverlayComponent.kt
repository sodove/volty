package ru.sodovaya.volty.presentation.video

import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.sodovaya.volty.domain.video.*

/** Small retained settings owner; renderer and recorder only receive validated immutable presets. */
class VideoOverlayComponent(
    componentContext: ComponentContext,
    initial: OverlayDocument,
    private val persist: (OverlayDocument) -> Unit,
) : ComponentContext by componentContext {
    private val mutableDocument = MutableStateFlow(
        runCatching { OverlayCodec.decode(OverlayCodec.encode(initial)) }.getOrDefault(OverlayDocument())
    )
    val document = mutableDocument.asStateFlow()

    fun change(document: OverlayDocument) {
        // Reuse persistence validation at this boundary; editor drafts never reach the renderer.
        val validated = runCatching { OverlayCodec.decode(OverlayCodec.encode(document)) }.getOrNull() ?: return
        if (validated != document) return
        persist(validated)
        mutableDocument.value = validated
    }
}

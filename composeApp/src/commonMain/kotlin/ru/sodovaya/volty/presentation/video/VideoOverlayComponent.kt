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

    /**
     * False when [document] was refused (invalid, or too large to store). The caller must say so: an earlier revision
     * returned nothing, and the editor carried on as if the save had happened.
     */
    fun change(document: OverlayDocument): Boolean {
        // Reuse persistence validation at this boundary; editor drafts never reach the renderer.
        val validated = runCatching { OverlayCodec.decode(OverlayCodec.encode(document)) }.getOrNull() ?: return false
        if (validated != document) return false
        persist(validated)
        mutableDocument.value = validated
        return true
    }
}

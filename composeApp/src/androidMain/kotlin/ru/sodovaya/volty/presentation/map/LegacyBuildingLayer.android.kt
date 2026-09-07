package ru.sodovaya.volty.presentation.map

import android.graphics.Color
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionBase
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionColor
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionHeight
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionOpacity

internal object LegacyBuildingLayer {
    fun add(style: Style) {
        LegacyBuildingLayerPolicy.appendIfMissing(
            existingLayerIds = style.layers.map { it.id },
            darkTheme = style.uri == MapRenderSourcePolicy.styleUrl(darkTheme = true),
        ) { definition ->
            val buildings = FillExtrusionLayer(definition.id, definition.sourceId)
                .withSourceLayer(definition.sourceLayer)
                .withProperties(
                    fillExtrusionColor(Color.parseColor(definition.color)),
                    fillExtrusionHeight(Expression.get(definition.heightProperty)),
                    fillExtrusionBase(Expression.get(definition.baseProperty)),
                    fillExtrusionOpacity(definition.opacity),
                )
            buildings.minZoom = definition.minZoom
            // An earlier offline implementation placed buildings below roads. The original
            // OFM renderer appended them: retain that order and its unmodified height data.
            style.addLayer(buildings)
        }
    }
}

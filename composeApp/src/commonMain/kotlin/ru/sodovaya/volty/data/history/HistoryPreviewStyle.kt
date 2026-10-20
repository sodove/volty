package ru.sodovaya.volty.data.history

import kotlinx.serialization.json.*

/** Derive the tiny preview style from the already bundled OFM map style. */
fun historyPreviewStyle(template: String): String? {
    val root = Json.parseToJsonElement(template.replace("__TILEJSON_DOMAIN__","tiles.openfreemap.org")).jsonObject
    if (root["version"]?.jsonPrimitive?.intOrNull != 8) return null
    val sources = root["sources"]?.jsonObject ?: return null
    for (source in sources.values) {
        val fields=source.jsonObject
        val urls=fields["url"]?.jsonPrimitive?.content?.let(::listOf)
            ?: fields["tiles"]?.jsonArray?.map { it.jsonPrimitive.content } ?: return null
        if (urls.isEmpty() || urls.any { !it.startsWith("https://tiles.openfreemap.org/") }) return null
    }
    val layers = root.getValue("layers").jsonArray.filter { it.jsonObject["type"]?.jsonPrimitive?.content != "symbol" }.map { layer ->
        val fields=layer.jsonObject
        val paint=fields["paint"]?.jsonObject
        val patterns=paint?.keys?.filter { it.endsWith("-pattern") }.orEmpty()
        // Retraction: dark OFM's woodland pattern initially forced every dark
        // thumbnail to Canvas. Its explicit base color is sufficient at this
        // size; preserve that color instead of fetching a sprite atlas. A
        // texture without an earned base color still falls back entirely.
        if (patterns.any { pattern -> pattern.removeSuffix("-pattern") !in setOf("fill","line","background") ||
                paint?.containsKey(pattern.removeSuffix("-pattern")+"-color") != true }) return null
        val result=if(patterns.isEmpty())fields else JsonObject(fields + ("paint" to JsonObject(checkNotNull(paint).filterKeys { it !in patterns })))
        if(result.values.any { it is JsonObject && it.keys.any { key -> key.endsWith("-pattern") } }) return null
        result
    }
    return JsonObject(root.filterKeys { it != "glyphs" && it != "sprite" } + ("layers" to JsonArray(layers))).toString()
}

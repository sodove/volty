package ru.sodovaya.volty.presentation.map

import android.content.Context
import android.graphics.Color
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.isActive
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionBase
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionColor
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionHeight
import org.maplibre.android.style.layers.PropertyFactory.fillExtrusionOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.sources.GeoJsonSource
import org.koin.compose.koinInject
import ru.sodovaya.volty.data.navigation.offline.AndroidOfflineMapSource
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionPackageRepository
import ru.sodovaya.volty.domain.location.RideLocationFix
import ru.sodovaya.volty.domain.navigation.GeoCoordinate
import ru.sodovaya.volty.presentation.nearby.ParticipantMarker
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private const val DARK_MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/dark"
private const val LIGHT_MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/bright"
private const val TRAIL_SOURCE_ID = "volty-trail-source"
private const val TRAIL_LAYER_ID = "volty-trail-layer"
private const val OWN_SOURCE_ID = "volty-own-source"
private const val OWN_LAYER_ID = "volty-own-layer"
private const val GROUP_SOURCE_ID = "volty-group-source"
private const val GROUP_LAYER_ID = "volty-group-layer"
private const val GROUP_LABEL_LAYER_ID = "volty-group-label-layer"
private const val INACTIVE_ROUTE_SOURCE_ID = "volty-route-inactive-source"
private const val INACTIVE_ROUTE_LAYER_ID = "volty-route-inactive-layer"
private const val SELECTED_ROUTE_SOURCE_ID = "volty-route-selected-source"
private const val SELECTED_ROUTE_LAYER_ID = "volty-route-selected-layer"
private const val COMPLETED_ROUTE_SOURCE_ID = "volty-route-completed-source"
private const val COMPLETED_ROUTE_LAYER_ID = "volty-route-completed-layer"
private const val DESTINATION_SOURCE_ID = "volty-destination-source"
private const val DESTINATION_LAYER_ID = "volty-destination-layer"
private const val BUILDINGS_LAYER_ID = "volty-buildings-3d"
private const val RU_CITIES_SOURCE_ID = "volty-ru-cities-source"
private const val RU_CITIES_LAYER_ID = "volty-ru-cities-layer"
private const val MAX_TRAIL_POINTS = 240

/** Keeps the native GL surface and its loaded style alive between tab changes. */
private object MapViewCache {
    private val views = mutableMapOf<String, MapView>()

    fun obtain(key: String, context: Context): MapView = synchronized(this) {
        views[key] ?: run {
            MapLibre.getInstance(context.applicationContext)
            MapView(
                context,
                MapLibreMapOptions().textureMode(rideMapTextureModeRequiredForMapCapture),
            ).also { view ->
                view.onCreate(null)
                views[key] = view
            }
        }
    }
}

@Composable
actual fun PlatformRideMapLayer(
    scene: NavigationMapScene,
    darkTheme: Boolean,
    onCameraGesture: (Long) -> Unit,
    modifier: Modifier,
) {
    AndroidMapLibreView(
        cacheKey = "ride",
        darkTheme = darkTheme,
        scene = scene,
        onCameraGesture = onCameraGesture,
        modifier = modifier,
    )
}

/** Compatibility adapter for the pre-retention root host; it renders remote markers only. */
@Composable
actual fun PlatformRideMapLayer(
    darkTheme: Boolean,
    markers: List<ParticipantMarker>,
    requestLocationPermission: Boolean,
    vehicleSpeedKmh: Float?,
    recenterRequest: Long,
    onGpsSpeedKmhChanged: (Float?) -> Unit,
    modifier: Modifier,
) {
    AndroidMapLibreView(
        cacheKey = "ride",
        darkTheme = darkTheme,
        scene = NavigationMapScene(
            ownFix = null,
            trail = emptyList(),
            participantMarkers = markers,
            routes = emptyList(),
            destination = null,
            followState = RideMapFollowState(),
            cameraRequest = null,
        ),
        onCameraGesture = {},
        modifier = modifier,
    )
}

@Composable
private fun AndroidMapLibreView(
    cacheKey: String,
    darkTheme: Boolean,
    scene: NavigationMapScene,
    onCameraGesture: (Long) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember(cacheKey) { mutableStateOf(false) }
    var lastCameraSequence by remember(cacheKey) { mutableLongStateOf(Long.MIN_VALUE) }
    val latestScene = rememberUpdatedState(scene)
    val latestGestureCallback = rememberUpdatedState(onCameraGesture)
    val motionEstimator = remember(cacheKey) { RideMapMotionEstimator() }
    val cameraSmoother = remember(cacheKey) { RideMapCameraSmoother() }
    val hazeState = rememberHazeState()
    val mapView = remember(context, cacheKey) { MapViewCache.obtain(cacheKey, context) }
    val offlineMapSource: AndroidOfflineMapSource = koinInject()
    val offlineRegions: OfflineRegionPackageRepository = koinInject()
    val offlineRegionStates by offlineRegions.states.collectAsState()
    val offlineSourceUrl = remember(scene.ownFix?.coordinate, offlineRegionStates) {
        runCatching { offlineMapSource.sourceUrl(scene.ownFix?.coordinate) }.getOrNull()
    }

    LaunchedEffect(scene.ownFix?.coordinate, offlineRegionStates) {
        offlineMapSource.considerDownload(scene.ownFix?.coordinate)
    }

    LaunchedEffect(map, darkTheme, offlineSourceUrl) {
        val readyMap = map ?: return@LaunchedEffect
        val targetStyleUrl = if (darkTheme) DARK_MAP_STYLE_URL else LIGHT_MAP_STYLE_URL
        if (offlineSourceUrl == null &&
            readyMap.style?.uri == targetStyleUrl &&
            readyMap.style?.isFullyLoaded == true
        ) {
            styleReady = true
            return@LaunchedEffect
        }
        styleReady = false
        val builder = if (offlineSourceUrl != null) {
            Style.Builder().fromJson(
                offlineStyleJson(
                    tileUrl = offlineSourceUrl,
                    glyphsUrl = offlineMapSource.glyphsUrl(),
                    darkTheme = darkTheme,
                ),
            )
        } else {
            Style.Builder().fromUri(targetStyleUrl)
        }
        readyMap.setStyle(builder) { style ->
            configureStyle(style, darkTheme)
            lastCameraSequence = Long.MIN_VALUE
            styleReady = true
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { readyMap ->
            map = readyMap
            readyMap.uiSettings.isCompassEnabled = false
            readyMap.uiSettings.isLogoEnabled = false
            readyMap.uiSettings.isAttributionEnabled = false
        }
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(map) {
        val readyMap = map ?: return@DisposableEffect onDispose { }
        val listener = MapLibreMap.OnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                latestGestureCallback.value(SystemClock.elapsedRealtime())
            }
        }
        readyMap.addOnCameraMoveStartedListener(listener)
        onDispose { readyMap.removeOnCameraMoveStartedListener(listener) }
    }

    LaunchedEffect(map, styleReady, latestScene.value.cameraRequest?.sequence) {
        val readyMap = map ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        val request = latestScene.value.cameraRequest ?: return@LaunchedEffect
        if (request.sequence == lastCameraSequence) return@LaunchedEffect
        when (request) {
            is MapCameraRequest.FitAlternatives -> fitAlternatives(readyMap, request.points)
            is MapCameraRequest.Recenter -> recenter(readyMap, request.fix)
            is MapCameraRequest.FollowFix -> Unit
        }
        lastCameraSequence = request.sequence
    }

    LaunchedEffect(map, styleReady) {
        val readyMap = map ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        var lastFrameNanos = Long.MIN_VALUE
        var lastFixKey: FixRenderKey? = null
        var lastTrail: List<NavigationTrailPoint> = emptyList()
        while (isActive) {
            val frameNanos = withFrameNanos { it }
            val previousFrameNanos = lastFrameNanos
            if (previousFrameNanos != Long.MIN_VALUE &&
                frameNanos - previousFrameNanos < defaultRideMapLocationUpdatePolicy.renderIntervalMillis * 1_000_000L
            ) continue
            lastFrameNanos = frameNanos

            val current = latestScene.value
            val raw = current.ownFix
            val fixKey = raw?.let { FixRenderKey(it.capturedAtEpochMillis, it.coordinate) }
            if (fixKey != null && fixKey != lastFixKey) {
                motionEstimator.accept(raw.toMapMotionFix())
                lastFixKey = fixKey
            }
            if (current.trail != lastTrail) {
                updateTrailGeoJson(readyMap, current.trail, System.currentTimeMillis())
                lastTrail = current.trail
            }

            val nowMillis = System.currentTimeMillis()
            val estimate = motionEstimator.estimate(nowMillis)
            val predicted = estimate?.coordinate ?: raw?.coordinate?.let {
                RideMapPredictedCoordinate(it.latitude, it.longitude)
            }
            updateOwnGeoJson(readyMap, predicted)
            if (current.followState.mode == RideMapFollowMode.FOLLOWING && predicted != null) {
                val currentCamera = readyMap.cameraPosition
                val speedKmh = estimate?.speedMetersPerSecond?.times(3.6f) ?: 0f
                val cameraFrame = cameraSmoother.advance(
                    targetZoom = clampRideMapCameraZoom(
                        rideMapZoomForSpeed(speedKmh, fallbackZoom = currentCamera.zoom),
                    ),
                    targetBearingDegrees = rideMapBearingDegrees(
                        gpsBearingDegrees = estimate?.bearingDegrees,
                        speedKmh = speedKmh,
                        fallbackDegrees = currentCamera.bearing,
                    ),
                    targetCenter = predicted,
                    deltaMillis = if (previousFrameNanos == Long.MIN_VALUE) 0L
                    else ((frameNanos - previousFrameNanos) / 1_000_000L).coerceAtMost(250L),
                )
                readyMap.moveCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder(readyMap.cameraPosition)
                            .target(cameraFrame.center?.let { LatLng(it.latitude, it.longitude) })
                            .zoom(cameraFrame.zoom)
                            .tilt(defaultRideMapCameraPolicy.tiltDegrees)
                            .bearing(cameraFrame.bearingDegrees)
                            .build(),
                    ),
                )
            } else {
                val camera = readyMap.cameraPosition
                cameraSmoother.reset(
                    RideMapCameraFrame(
                        zoom = camera.zoom,
                        bearingDegrees = camera.bearing,
                        center = camera.target?.let { RideMapPredictedCoordinate(it.latitude, it.longitude) },
                    ),
                )
            }
        }
    }

    LaunchedEffect(map, styleReady, latestScene.value.routes, latestScene.value.destination) {
        val readyMap = map ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        updateRouteGeoJson(readyMap, latestScene.value.routes)
        updateDestinationGeoJson(readyMap, latestScene.value.destination)
    }

    LaunchedEffect(map, styleReady, latestScene.value.participantMarkers) {
        val readyMap = map ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        updateMarkerGeoJson(readyMap, latestScene.value.participantMarkers)
    }

    Box(modifier = modifier.background(ComposeColor(0xFF07131E))) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(state = hazeState, zIndex = 0f),
        )
        MapTopBottomBlur(hazeState = hazeState, modifier = Modifier.fillMaxSize())
        Text(
            text = "© OpenStreetMap © OpenFreeMap",
            color = if (darkTheme) ComposeColor(0xB8C8D4DA) else ComposeColor(0x88364048),
            fontSize = 9.sp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 4.dp),
        )
    }
}

private data class FixRenderKey(
    val timestamp: Long,
    val coordinate: GeoCoordinate,
)

private fun RideLocationFix.toMapMotionFix() = RideMapMotionFix(
    latitude = coordinate.latitude,
    longitude = coordinate.longitude,
    timestampMillis = capturedAtEpochMillis,
    accuracyMeters = accuracyMeters.toFloat(),
    speedMetersPerSecond = speedMetersPerSecond?.toFloat(),
    bearingDegrees = bearingDegrees?.toFloat(),
)

private fun fitAlternatives(map: MapLibreMap, points: List<GeoCoordinate>) {
    val latLngs = points.map { LatLng(it.latitude, it.longitude) }
    if (latLngs.isEmpty()) return
    if (latLngs.size == 1) {
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(latLngs.single(), 15.0))
        return
    }
    val boundsBuilder = LatLngBounds.Builder()
    latLngs.forEach(boundsBuilder::include)
    val bounds = boundsBuilder.build()
    // Keep the preview in the unobstructed map band: the HUD and the route card
    // occupy the top and bottom edges of the native map view. Flattening the
    // camera also prevents the retained ride tilt from projecting a long route
    // underneath the dashboard chrome.
    val horizontalPadding = (map.width * 0.06f).toInt().coerceAtLeast(48)
    val topPadding = (map.height * 0.14f).toInt().coerceAtLeast(96)
    val bottomPadding = (map.height * 0.28f).toInt().coerceAtLeast(180)
    map.moveCamera(
        CameraUpdateFactory.newLatLngBounds(
            bounds,
            0.0,
            0.0,
            horizontalPadding,
            topPadding,
            horizontalPadding,
            bottomPadding,
        ),
    )
}

private fun recenter(map: MapLibreMap, fix: RideLocationFix) {
    map.animateCamera(
        CameraUpdateFactory.newLatLngZoom(
            LatLng(fix.coordinate.latitude, fix.coordinate.longitude),
            map.cameraPosition.zoom.coerceAtLeast(15.0),
        ),
    )
}

@Composable
private fun MapTopBottomBlur(hazeState: HazeState, modifier: Modifier = Modifier) {
    Box(modifier = modifier) {
        MapBlurBand(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .fillMaxHeight(MAP_BLUR_BAND_FRACTION)
                .hazeEffect(state = hazeState) {
                    blurRadius = 24.dp
                    inputScale = HazeInputScale.Auto
                    noiseFactor = 0f
                    mask = Brush.verticalGradient(
                        listOf(ComposeColor.White, ComposeColor.White, ComposeColor.Transparent),
                    )
                },
        )
        MapBlurBand(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(MAP_BLUR_BAND_FRACTION)
                .hazeEffect(state = hazeState) {
                    blurRadius = 24.dp
                    inputScale = HazeInputScale.Auto
                    noiseFactor = 0f
                    mask = Brush.verticalGradient(
                        listOf(ComposeColor.Transparent, ComposeColor.White, ComposeColor.White),
                    )
                },
        )
    }
}

private const val MAP_BLUR_BAND_FRACTION = 0.30f

@Composable
private fun MapBlurBand(modifier: Modifier) {
    Box(modifier = modifier)
}

private fun configureStyle(style: Style, darkTheme: Boolean) {
    if (style.getLayer(BUILDINGS_LAYER_ID) == null) {
        val buildings = FillExtrusionLayer(BUILDINGS_LAYER_ID, "openmaptiles")
            .withSourceLayer("building")
            .withProperties(
                fillExtrusionColor(Color.parseColor(if (darkTheme) "#31424B" else "#D5DCE0")),
                fillExtrusionHeight(Expression.get("render_height")),
                fillExtrusionBase(Expression.get("render_min_height")),
                fillExtrusionOpacity(0.82f),
            )
        buildings.setMinZoom(13f)
        style.addLayer(buildings)
    }
    if (style.getSource(RU_CITIES_SOURCE_ID) == null) {
        style.addSource(
            GeoJsonSource(
                RU_CITIES_SOURCE_ID,
                FeatureCollection.fromFeatures(russianCityLabels.map { city ->
                    Feature.fromGeometry(Point.fromLngLat(city.longitude, city.latitude)).apply {
                        addStringProperty("name", city.name)
                    }
                }),
            ),
        )
    }
    val cities = style.getLayerAs<SymbolLayer>(RU_CITIES_LAYER_ID)
        ?: SymbolLayer(RU_CITIES_LAYER_ID, RU_CITIES_SOURCE_ID).also { style.addLayer(it) }
    cities.withProperties(
        textField(Expression.get("name")),
        textFont(arrayOf("Noto Sans Regular")),
        textSize(9f),
        textColor(Color.parseColor(if (darkTheme) "#E8F0F4" else "#1C2730")),
        textHaloColor(Color.parseColor(if (darkTheme) "#07131E" else "#FFFFFF")),
        textHaloWidth(1.5f),
        textAllowOverlap(true),
        textIgnorePlacement(true),
    )
    cities.setMinZoom(0f)
    cities.setMaxZoom(7f)

    addLineSourceAndLayer(style, TRAIL_SOURCE_ID, TRAIL_LAYER_ID, "#D16AFF", 3.2f, Expression.get("opacity"))
    addLineSourceAndLayer(style, INACTIVE_ROUTE_SOURCE_ID, INACTIVE_ROUTE_LAYER_ID, "#8496A1", 4.0f, 0.50f)
    addLineSourceAndLayer(style, SELECTED_ROUTE_SOURCE_ID, SELECTED_ROUTE_LAYER_ID, "#39B9FF", 6.0f, 0.96f)
    addLineSourceAndLayer(style, COMPLETED_ROUTE_SOURCE_ID, COMPLETED_ROUTE_LAYER_ID, "#72E3A2", 7.0f, 0.95f)

    if (style.getSource(DESTINATION_SOURCE_ID) == null) style.addSource(GeoJsonSource(DESTINATION_SOURCE_ID, emptyFeatureCollection()))
    if (style.getLayer(DESTINATION_LAYER_ID) == null) {
        style.addLayer(
            CircleLayer(DESTINATION_LAYER_ID, DESTINATION_SOURCE_ID).withProperties(
                circleColor(Color.parseColor("#FF668C")),
                circleRadius(8f),
                circleStrokeColor(Color.WHITE),
                circleStrokeWidth(2f),
            ),
        )
    }
    if (style.getSource(OWN_SOURCE_ID) == null) style.addSource(GeoJsonSource(OWN_SOURCE_ID, emptyFeatureCollection()))
    if (style.getLayer(OWN_LAYER_ID) == null) {
        style.addLayer(
            CircleLayer(OWN_LAYER_ID, OWN_SOURCE_ID).withProperties(
                circleColor(Color.parseColor("#4DC7FF")),
                circleRadius(8f),
                circleStrokeColor(Color.WHITE),
                circleStrokeWidth(2f),
            ),
        )
    }
    if (style.getSource(GROUP_SOURCE_ID) == null) style.addSource(GeoJsonSource(GROUP_SOURCE_ID, emptyFeatureCollection()))
    if (style.getLayer(GROUP_LAYER_ID) == null) {
        style.addLayer(
            CircleLayer(GROUP_LAYER_ID, GROUP_SOURCE_ID).withProperties(
                circleColor(
                    Expression.switchCase(
                        Expression.eq(Expression.get("stale"), true),
                        Expression.color(Color.parseColor("#FFB454")),
                        Expression.color(Color.parseColor("#35E6A2")),
                    ),
                ),
                circleRadius(7f),
                circleStrokeColor(Color.WHITE),
                circleStrokeWidth(2f),
            ),
        )
    }
    if (style.getLayer(GROUP_LABEL_LAYER_ID) == null) {
        style.addLayer(
            SymbolLayer(GROUP_LABEL_LAYER_ID, GROUP_SOURCE_ID).withProperties(
                textField(Expression.get("label")),
                textFont(arrayOf("Noto Sans Regular")),
                textSize(10f),
                textColor(Color.parseColor(if (darkTheme) "#F1F7FA" else "#15232B")),
                textHaloColor(Color.parseColor(if (darkTheme) "#07131E" else "#FFFFFF")),
                textHaloWidth(1.25f),
                textAllowOverlap(true),
                textIgnorePlacement(true),
            ),
        )
    }
}

/** A small offline style whose only network input is the local PMTiles server. */
private fun offlineStyleJson(tileUrl: String, glyphsUrl: String, darkTheme: Boolean): String {
    val background = if (darkTheme) "#07131E" else "#EEF3F5"
    val landuse = if (darkTheme) "#10232B" else "#E4ECEA"
    val water = if (darkTheme) "#12384A" else "#B9DDEB"
    val roads = if (darkTheme) "#9AAAB3" else "#7A858B"
    val buildings = if (darkTheme) "#344952" else "#D0D7D9"
    val label = if (darkTheme) "#E8F0F4" else "#1C2730"
    val halo = if (darkTheme) "#07131E" else "#FFFFFF"
    return """
        {
          "version": 8,
          "sources": {
            "openmaptiles": {
              "type": "vector",
              "tiles": ["$tileUrl"],
              "minzoom": 5,
              "maxzoom": 14
            }
          },
          "glyphs":"$glyphsUrl",
          "layers": [
            {"id":"background","type":"background","paint":{"background-color":"$background"}},
            {"id":"landuse","type":"fill","source":"openmaptiles","source-layer":"landuse","paint":{"fill-color":"$landuse","fill-opacity":0.8}},
            {"id":"water","type":"fill","source":"openmaptiles","source-layer":"water","paint":{"fill-color":"$water"}},
            {"id":"waterway","type":"line","source":"openmaptiles","source-layer":"waterway","paint":{"line-color":"$water","line-width":1.5}},
            {"id":"roads","type":"line","source":"openmaptiles","source-layer":"transportation","paint":{"line-color":"$roads","line-width":1.4,"line-opacity":0.9}},
            {"id":"buildings","type":"fill","source":"openmaptiles","source-layer":"building","minzoom":13,"paint":{"fill-color":"$buildings","fill-opacity":0.75}},
            {"id":"place-labels","type":"symbol","source":"openmaptiles","source-layer":"place","minzoom":5,"layout":{"text-field":["get","name"],"text-font":["Noto Sans Regular"],"text-size":["interpolate",["linear"],["zoom"],5,10,14,17],"text-max-width":8,"symbol-sort-key":["get","rank"]},"paint":{"text-color":"$label","text-halo-color":"$halo","text-halo-width":1.5}},
            {"id":"road-labels","type":"symbol","source":"openmaptiles","source-layer":"transportation_name","minzoom":10,"layout":{"symbol-placement":"line","text-field":["get","name"],"text-font":["Noto Sans Regular"],"text-size":["interpolate",["linear"],["zoom"],10,9,14,13],"text-max-angle":30,"text-max-width":8,"text-padding":2},"paint":{"text-color":"$label","text-halo-color":"$halo","text-halo-width":1.25}},
            {"id":"poi-labels","type":"symbol","source":"openmaptiles","source-layer":"poi","minzoom":13,"layout":{"text-field":["get","name"],"text-font":["Noto Sans Regular"],"text-size":10,"text-max-width":7,"text-offset":[0,0.8],"text-anchor":"top"},"paint":{"text-color":"$label","text-halo-color":"$halo","text-halo-width":1.25}}
          ]
        }
    """.trimIndent()
}

private fun addLineSourceAndLayer(
    style: Style,
    sourceId: String,
    layerId: String,
    color: String,
    width: Float,
    opacity: Any,
) {
    if (style.getSource(sourceId) == null) style.addSource(GeoJsonSource(sourceId, emptyFeatureCollection()))
    if (style.getLayer(layerId) == null) {
        val opacityProperty = when (opacity) {
            is Expression -> lineOpacity(opacity)
            is Float -> lineOpacity(opacity)
            else -> error("Unsupported line opacity")
        }
        style.addLayer(
            LineLayer(layerId, sourceId).withProperties(
                lineColor(Color.parseColor(color)),
                opacityProperty,
                lineWidth(width),
                lineCap("round"),
            ),
        )
    }
}

private fun updateRouteGeoJson(map: MapLibreMap, routes: List<NavigationRouteLine>) {
    val style = map.style ?: return
    style.getSourceAs<GeoJsonSource>(INACTIVE_ROUTE_SOURCE_ID)?.setGeoJson(
        routeFeatureCollection(routes.filter { !it.active }),
    )
    style.getSourceAs<GeoJsonSource>(SELECTED_ROUTE_SOURCE_ID)?.setGeoJson(
        routeFeatureCollection(routes.filter { it.active && it.selected }),
    )
    style.getSourceAs<GeoJsonSource>(COMPLETED_ROUTE_SOURCE_ID)?.setGeoJson(
        routeFeatureCollection(routes.flatMap(::completedRouteLines)),
    )
}

private fun completedRouteLines(route: NavigationRouteLine): List<NavigationRouteLine> =
    if (route.completedFraction <= 0.0) emptyList()
    else listOf(route.copy(points = completedPoints(route.points, route.completedFraction)))

private fun completedPoints(points: List<GeoCoordinate>, fraction: Double): List<GeoCoordinate> {
    if (points.size < 2) return emptyList()
    if (fraction >= 1.0) return points
    val target = (points.size - 1) * fraction
    val fullSegments = target.toInt().coerceIn(0, points.lastIndex - 1)
    val result = points.take(fullSegments + 1).toMutableList()
    val remainder = target - fullSegments
    if (remainder > 0.0) {
        val start = points[fullSegments]
        val end = points[fullSegments + 1]
        result += GeoCoordinate(
            latitude = start.latitude + (end.latitude - start.latitude) * remainder,
            longitude = start.longitude + (end.longitude - start.longitude) * remainder,
        )
    }
    return result.takeIf { it.size >= 2 } ?: emptyList()
}

private fun routeFeatureCollection(routes: List<NavigationRouteLine>): FeatureCollection =
    FeatureCollection.fromFeatures(
        routes.mapNotNull { route ->
            if (route.points.size < 2) return@mapNotNull null
            Feature.fromGeometry(
                LineString.fromLngLats(route.points.map { Point.fromLngLat(it.longitude, it.latitude) }),
            ).apply { addStringProperty("routeId", route.routeId) }
        },
    )

private fun updateDestinationGeoJson(map: MapLibreMap, destination: GeoCoordinate?) {
    map.style?.getSourceAs<GeoJsonSource>(DESTINATION_SOURCE_ID)?.setGeoJson(
        destination?.let {
            FeatureCollection.fromFeatures(listOf(Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude))))
        } ?: emptyFeatureCollection(),
    )
}

private fun updateMarkerGeoJson(map: MapLibreMap, markers: List<ParticipantMarker>) {
    map.style?.getSourceAs<GeoJsonSource>(GROUP_SOURCE_ID)?.setGeoJson(
        FeatureCollection.fromFeatures(markers.map { marker ->
            Feature.fromGeometry(Point.fromLngLat(marker.longitude, marker.latitude)).apply {
                addStringProperty("label", rideMapMarkerLabel(marker))
                addBooleanProperty("stale", marker.stale)
            }
        }),
    )
}

private fun updateTrailGeoJson(map: MapLibreMap, trail: List<NavigationTrailPoint>, nowMillis: Long) {
    val samples = trail.takeLast(MAX_TRAIL_POINTS).map { it.sample }
    map.style?.getSourceAs<GeoJsonSource>(TRAIL_SOURCE_ID)?.setGeoJson(
        trailFeatureCollection(samples, nowMillis),
    )
}

private fun updateOwnGeoJson(map: MapLibreMap, coordinate: RideMapPredictedCoordinate?) {
    map.style?.getSourceAs<GeoJsonSource>(OWN_SOURCE_ID)?.setGeoJson(
        coordinate?.let {
            FeatureCollection.fromFeatures(listOf(Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude))))
        } ?: emptyFeatureCollection(),
    )
}

private fun trailFeatureCollection(samples: List<RideMapTrailSample>, nowMillis: Long): FeatureCollection {
    if (samples.size < 2) return emptyFeatureCollection()
    val features = mutableListOf<Feature>()
    var distanceBehind = 0.0
    connectedRideMapTrailSegments(samples).asReversed().forEach { segment ->
        for (index in segment.lastIndex downTo 1) {
            val older = segment[index - 1]
            val newer = segment[index]
            val ageMillis = (nowMillis - newer.timestampMillis).coerceAtLeast(0L)
            val opacity = minOf(
                trailOpacityForDistanceMeters(distanceBehind),
                trailOpacityForAgeMillis(ageMillis),
            )
            if (opacity > 0f) {
                Feature.fromGeometry(
                    LineString.fromLngLats(
                        listOf(
                            Point.fromLngLat(older.longitude, older.latitude),
                            Point.fromLngLat(newer.longitude, newer.latitude),
                        ),
                    ),
                ).apply { addNumberProperty("opacity", opacity) }.also(features::add)
            }
            distanceBehind += distanceMeters(older, newer)
        }
    }
    return FeatureCollection.fromFeatures(features.reversed())
}

private fun distanceMeters(first: RideMapTrailSample, second: RideMapTrailSample): Double {
    val lat1 = Math.toRadians(first.latitude)
    val lat2 = Math.toRadians(second.latitude)
    val dLat = lat2 - lat1
    val dLon = Math.toRadians(second.longitude - first.longitude)
    val a = sin(dLat / 2.0).pow(2.0) + cos(lat1) * cos(lat2) * sin(dLon / 2.0).pow(2.0)
    return 6_371_000.0 * 2.0 * kotlin.math.atan2(sqrt(a), sqrt(1.0 - a))
}

private fun emptyFeatureCollection(): FeatureCollection = FeatureCollection.fromFeatures(emptyList())

package ru.sodovaya.volty.presentation.map

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
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
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.sources.GeoJsonSource
import ru.sodovaya.volty.presentation.nearby.ParticipantMarker
import ru.sodovaya.volty.presentation.map.defaultRideMapCameraPolicy
import ru.sodovaya.volty.presentation.map.rideMapTextureModeRequiredForMapCapture
import ru.sodovaya.volty.presentation.map.rideMapBearingDegrees
import ru.sodovaya.volty.presentation.map.rideMapZoomForSpeed
import ru.sodovaya.volty.presentation.map.predictRideMapCoordinate
import ru.sodovaya.volty.presentation.map.rideMapMarkerLabel
import ru.sodovaya.volty.presentation.map.trailOpacityForDistanceMeters
import ru.sodovaya.volty.presentation.map.RideMapTrailSample
import ru.sodovaya.volty.presentation.map.shouldRenderTrail
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
        modifier = modifier,
        markers = markers,
        // Nearby renders remote markers only. This flag is deliberately tied
        // to the explicit Ride permission request so opening Nearby cannot
        // start or reveal the user's own location stream.
        showOwnLocation = requestLocationPermission,
        requestLocationPermission = requestLocationPermission,
        vehicleSpeedKmh = vehicleSpeedKmh,
        recenterRequest = recenterRequest,
        onGpsSpeedKmhChanged = onGpsSpeedKmhChanged,
    )
}

private data class MapCoordinate(val latitude: Double, val longitude: Double)
private data class TrailPoint(
    val coordinate: MapCoordinate,
    val sample: RideMapTrailSample,
)

@Composable
private fun AndroidMapLibreView(
    cacheKey: String,
    darkTheme: Boolean,
    modifier: Modifier,
    markers: List<ParticipantMarker>,
    showOwnLocation: Boolean,
    requestLocationPermission: Boolean,
    vehicleSpeedKmh: Float?,
    recenterRequest: Long,
    onGpsSpeedKmhChanged: (Float?) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var locationPermissionGranted by remember { mutableStateOf(hasLocationPermission(context)) }
    var own by remember(showOwnLocation, locationPermissionGranted) {
        mutableStateOf(if (showOwnLocation && locationPermissionGranted) lastKnownLocation(context) else null)
    }
    val trail = remember(showOwnLocation) { mutableStateListOf<TrailPoint>() }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember(cacheKey) { mutableStateOf(false) }
    var followState by remember(cacheKey) { mutableStateOf(RideMapFollowState()) }
    val sessionTimestampNormalizer = remember(cacheKey) {
        RideMapSessionTimestampNormalizer(
            wallClockStartMillis = System.currentTimeMillis(),
            elapsedRealtimeStartMillis = SystemClock.elapsedRealtime(),
        )
    }
    val latestFollowState = rememberUpdatedState(followState)
    val latestOwn = rememberUpdatedState(own)
    val latestVehicleSpeedKmh = rememberUpdatedState(vehicleSpeedKmh)
    val latestGpsSpeedKmhChanged = rememberUpdatedState(onGpsSpeedKmhChanged)
    val motionEstimator = remember(cacheKey) { RideMapMotionEstimator() }
    val cameraSmoother = remember(cacheKey) { RideMapCameraSmoother() }
    val hazeState = rememberHazeState()
    val mapView = remember(context, cacheKey) {
        MapViewCache.obtain(cacheKey, context)
    }
    LaunchedEffect(mapView, requestLocationPermission) {
        val grantedAtStart = hasLocationPermission(context)
        locationPermissionGranted = grantedAtStart
        if (requestLocationPermission && !grantedAtStart) {
            context.findActivity()?.let { activity ->
                ActivityCompat.requestPermissions(
                    activity,
                    LOCATION_PERMISSIONS,
                    LOCATION_PERMISSION_REQUEST_CODE,
                )
            }
            while (isActive && !hasLocationPermission(context)) {
                delay(250L)
            }
            locationPermissionGranted = hasLocationPermission(context)
        }
    }
    LaunchedEffect(map, darkTheme) {
        val readyMap = map ?: return@LaunchedEffect
        val targetStyleUrl = if (darkTheme) DARK_MAP_STYLE_URL else LIGHT_MAP_STYLE_URL
        val currentStyle = readyMap.style
        if (currentStyle?.uri == targetStyleUrl && currentStyle.isFullyLoaded) {
            styleReady = true
            return@LaunchedEffect
        }
        styleReady = false
        readyMap.setStyle(Style.Builder().fromUri(targetStyleUrl)) { style ->
            configureStyle(style, darkTheme)
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

    DisposableEffect(context, showOwnLocation, locationPermissionGranted) {
        if (!showOwnLocation) {
            onDispose { latestGpsSpeedKmhChanged.value(null) }
        } else {
            val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
            val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (manager == null || (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED)) {
                onDispose { latestGpsSpeedKmhChanged.value(null) }
            } else {
                var gpsFixSeen = false
                var lastAcceptedLocation: Location? = null
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (!shouldAcceptMapLocation(location, lastAcceptedLocation, gpsFixSeen)) return
                        if (location.provider == LocationManager.GPS_PROVIDER) gpsFixSeen = true
                        // Preserve the raw fix for the historical trail. If we derive
                        // speed/bearing first, a single bad GPS jump looks self-consistent
                        // and can be drawn as a long false segment.
                        appendTrail(trail, location, sessionTimestampNormalizer)
                        val enriched = enrichMapLocation(location, lastAcceptedLocation)
                        lastAcceptedLocation = enriched
                        val timestampSource = enriched.elapsedRealtimeNanos
                            .takeIf { it > 0L }
                            ?.div(1_000_000L)
                        latestGpsSpeedKmhChanged.value(
                            enriched.takeIf { it.hasSpeed() }
                                ?.speed
                                ?.times(3.6f)
                                ?.takeIf { it.isFinite() && it >= 0f },
                        )
                        motionEstimator.accept(
                            RideMapMotionFix(
                                latitude = enriched.latitude,
                                longitude = enriched.longitude,
                                timestampMillis = sessionTimestampNormalizer.normalize(
                                    timestampMillis = timestampSource ?: enriched.time,
                                    source = if (timestampSource != null) {
                                        RideMapTimestampSource.ELAPSED_REALTIME
                                    } else {
                                        RideMapTimestampSource.WALL_CLOCK
                                    },
                                ),
                                accuracyMeters = enriched.accuracy.takeIf { enriched.hasAccuracy() },
                                speedMetersPerSecond = enriched.takeIf { it.hasSpeed() }?.speed,
                                bearingDegrees = enriched.takeIf { it.hasBearing() }?.bearing,
                            ),
                        )
                        own = enriched
                    }
                }
                val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                    .filter { provider -> runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false) }
                providers.forEach { provider ->
                    runCatching {
                        requestMapLocationUpdates(
                            manager = manager,
                            provider = provider,
                            listener = listener,
                            context = context,
                        )
                    }
                }
                onDispose {
                    runCatching { manager.removeUpdates(listener) }
                    latestGpsSpeedKmhChanged.value(null)
                }
            }
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
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // The native view is process-cached so returning to the ride tab
            // keeps its GL surface, camera and loaded tile cache intact.
        }
    }

    LaunchedEffect(recenterRequest) {
        followState = recenterRideMap(followState)
    }

    DisposableEffect(map) {
        val readyMap = map ?: return@DisposableEffect onDispose { }
        val listener = MapLibreMap.OnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                followState = onRideMapCameraMoveStarted(
                    state = latestFollowState.value,
                    origin = RideMapCameraMoveOrigin.GESTURE,
                    nowMillis = SystemClock.elapsedRealtime(),
                )
            }
        }
        readyMap.addOnCameraMoveStartedListener(listener)
        onDispose { readyMap.removeOnCameraMoveStartedListener(listener) }
    }

    LaunchedEffect(map, styleReady, markers, showOwnLocation) {
        val readyMap = map ?: return@LaunchedEffect
        var lastFrameNanos = Long.MIN_VALUE
        var lastTrailRenderMillis: Long? = null
        while (isActive) {
            val frameNanos = withFrameNanos { it }
            val previousFrameNanos = lastFrameNanos
            if (previousFrameNanos != Long.MIN_VALUE &&
                frameNanos - previousFrameNanos < defaultRideMapLocationUpdatePolicy.renderIntervalMillis * 1_000_000L
            ) continue
            lastFrameNanos = frameNanos

            val raw = latestOwn.value
            val nowMillis = System.currentTimeMillis()
            val trailNowMillis = nowMillis
            pruneTrail(trail, trailNowMillis)
            if (shouldRenderTrail(trailNowMillis, lastTrailRenderMillis)) {
                updateTrailGeoJson(readyMap, trail, trailNowMillis)
                lastTrailRenderMillis = trailNowMillis
            }
            val locationSpeedKmh = raw?.takeIf { it.hasSpeed() }?.speed?.times(3.6f)
                ?.takeIf { it.isFinite() && it >= 0f }
            val effectiveSpeedKmh = latestVehicleSpeedKmh.value
                ?.takeIf { it.isFinite() && it >= 0f }
                ?: locationSpeedKmh
            val vehicleSpeedMps = effectiveSpeedKmh?.div(3.6f)
            val estimate = motionEstimator.estimate(
                nowMillis = nowMillis,
                speedMetersPerSecondOverride = vehicleSpeedMps,
            )
            val predictedCoordinate = estimate?.coordinate ?: raw?.let { location ->
                val predicted = predictRideMapCoordinate(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    speedMetersPerSecond = vehicleSpeedMps ?: location.takeIf { it.hasSpeed() }?.speed,
                    bearingDegrees = estimate?.bearingDegrees
                        ?: location.takeIf { it.hasBearing() }?.bearing,
                    ageMillis = locationAgeMillis(location),
                )
                predicted
            }
            val target = predictedCoordinate?.let { LatLng(it.latitude, it.longitude) }
                ?: markers.firstOrNull()?.let { LatLng(it.latitude, it.longitude) }
            val speedKmh = effectiveSpeedKmh
                ?: estimate?.speedMetersPerSecond?.times(3.6f)
                ?: 0f

            // The marker is a render-time object, not a GPS-fix object. Writing
            // it only from the LocationListener makes it visibly jump once per
            // provider callback even though the estimator already predicts a
            // position for every frame.
            predictedCoordinate?.let { coordinate ->
                updateOwnGeoJson(readyMap, coordinate)
            }

            if (followState.mode == RideMapFollowMode.FREE &&
                shouldAutoReturnToFollow(
                    state = followState,
                    speedKmh = speedKmh.takeIf { it > 0f },
                    nowMillis = nowMillis,
                )
            ) {
                followState = recenterRideMap(followState)
            }

            if (target != null && followState.mode == RideMapFollowMode.FOLLOWING) {
                val currentCamera = readyMap.cameraPosition
                val targetBearing = rideMapBearingDegrees(
                    gpsBearingDegrees = estimate?.bearingDegrees
                        ?: raw?.takeIf { it.hasBearing() }?.bearing,
                    speedKmh = speedKmh,
                    fallbackDegrees = currentCamera.bearing,
                )
                val cameraFrame = cameraSmoother.advance(
                    targetZoom = clampRideMapCameraZoom(
                        rideMapZoomForSpeed(speedKmh, fallbackZoom = currentCamera.zoom),
                    ),
                    targetBearingDegrees = targetBearing,
                    targetCenter = RideMapPredictedCoordinate(target.latitude, target.longitude),
                    deltaMillis = if (previousFrameNanos == Long.MIN_VALUE) 0L
                    else ((frameNanos - previousFrameNanos) / 1_000_000L).coerceAtMost(250L),
                )
                val nextPosition = CameraPosition.Builder(readyMap.cameraPosition)
                    .target(
                        cameraFrame.center?.let { LatLng(it.latitude, it.longitude) } ?: target,
                    )
                    .zoom(cameraFrame.zoom)
                    .tilt(defaultRideMapCameraPolicy.tiltDegrees)
                    .bearing(cameraFrame.bearingDegrees)
                    .build()
                readyMap.moveCamera(CameraUpdateFactory.newCameraPosition(nextPosition))
            } else {
                // A gesture owns the camera while follow mode is free. Keep
                // the smoother anchored to that user position so re-following
                // never starts from a stale zoom or heading.
                val camera = readyMap.cameraPosition
                cameraSmoother.reset(
                    RideMapCameraFrame(
                        zoom = camera.zoom,
                        bearingDegrees = camera.bearing,
                        center = camera.target?.let { target ->
                            RideMapPredictedCoordinate(target.latitude, target.longitude)
                        },
                    ),
                )
            }
        }
    }

    LaunchedEffect(map, styleReady, markers) {
        val readyMap = map ?: return@LaunchedEffect
        updateMarkerGeoJson(readyMap, markers)
    }

    Box(modifier = modifier.background(ComposeColor(0xFF07131E))) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(state = hazeState, zIndex = 0f),
        )
        MapTopBottomBlur(
            hazeState = hazeState,
            modifier = Modifier.fillMaxSize(),
        )
        Text(
            text = "© OpenStreetMap © OpenFreeMap",
            color = if (darkTheme) ComposeColor(0xB8C8D4DA) else ComposeColor(0x88364048),
            fontSize = 9.sp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 4.dp),
        )
    }
}

private val LOCATION_PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

private const val LOCATION_PERMISSION_REQUEST_CODE = 7001

private fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

private fun requestMapLocationUpdates(
    manager: LocationManager,
    provider: String,
    listener: LocationListener,
    context: Context,
) {
    val policy = defaultRideMapLocationUpdatePolicy
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val request = LocationRequest.Builder(policy.minIntervalMillis)
            .setQuality(
                if (provider == LocationManager.GPS_PROVIDER) {
                    LocationRequest.QUALITY_HIGH_ACCURACY
                } else {
                    LocationRequest.QUALITY_BALANCED_POWER_ACCURACY
                },
            )
            .setMinUpdateIntervalMillis(policy.minIntervalMillis)
            .setMinUpdateDistanceMeters(policy.minDistanceMeters)
            .setMaxUpdateDelayMillis(0L)
            .build()
        manager.requestLocationUpdates(
            provider,
            request,
            ContextCompat.getMainExecutor(context),
            listener,
        )
    } else {
        manager.requestLocationUpdates(
            provider,
            policy.minIntervalMillis,
            policy.minDistanceMeters,
            listener,
            Looper.getMainLooper(),
        )
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is android.content.ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
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
private fun MapBlurBand(
    modifier: Modifier,
) {
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
            textSize(9f),
            textColor(Color.parseColor(if (darkTheme) "#E8F0F4" else "#1C2730")),
            textHaloColor(Color.parseColor(if (darkTheme) "#07131E" else "#FFFFFF")),
            textHaloWidth(1.5f),
            textAllowOverlap(true),
            textIgnorePlacement(true),
    )
    cities.setMinZoom(0f)
    cities.setMaxZoom(7f)
    if (style.getSource(TRAIL_SOURCE_ID) == null) style.addSource(GeoJsonSource(TRAIL_SOURCE_ID, emptyFeatureCollection()))
        if (style.getLayer(TRAIL_LAYER_ID) == null) {
        style.addLayer(
            LineLayer(TRAIL_LAYER_ID, TRAIL_SOURCE_ID).withProperties(
                lineColor(Color.parseColor("#D16AFF")),
                lineOpacity(Expression.get("opacity")),
                lineWidth(3.2f),
                lineCap("round"),
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

private fun updateMarkerGeoJson(
    map: MapLibreMap,
    markers: List<ParticipantMarker>,
) {
    val style = map.style ?: return
    style.getSourceAs<GeoJsonSource>(GROUP_SOURCE_ID)?.setGeoJson(
        FeatureCollection.fromFeatures(markers.map { marker ->
            Feature.fromGeometry(Point.fromLngLat(marker.longitude, marker.latitude)).apply {
                addStringProperty("label", rideMapMarkerLabel(marker))
                addBooleanProperty("stale", marker.stale)
            }
        }),
    )
}

private fun updateTrailGeoJson(
    map: MapLibreMap,
    trail: List<TrailPoint>,
    nowMillis: Long,
) {
    map.style?.getSourceAs<GeoJsonSource>(TRAIL_SOURCE_ID)?.setGeoJson(
        trailFeatureCollection(trail, nowMillis),
    )
}

private fun updateOwnGeoJson(
    map: MapLibreMap,
    coordinate: RideMapPredictedCoordinate?,
) {
    val style = map.style ?: return
    style.getSourceAs<GeoJsonSource>(OWN_SOURCE_ID)?.setGeoJson(
        coordinate?.let {
            FeatureCollection.fromFeatures(
                listOf(Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude))),
            )
        } ?: emptyFeatureCollection(),
    )
}

private fun shouldAcceptMapLocation(
    location: Location,
    previous: Location?,
    gpsFixSeen: Boolean,
): Boolean {
    if (previous == null) return true
    if (gpsFixSeen && location.provider != LocationManager.GPS_PROVIDER) return false
    val locationNanos = location.elapsedRealtimeNanos
    val previousNanos = previous.elapsedRealtimeNanos
    if (locationNanos > 0L && previousNanos > 0L && locationNanos < previousNanos) return false
    return true
}

/** Some phones omit speed/bearing from ordinary location callbacks. Recover
 * them from consecutive fixes so the camera can keep moving between callbacks. */
private fun enrichMapLocation(location: Location, previous: Location?): Location {
    if (previous == null) return location
    val elapsedMillis = when {
        location.elapsedRealtimeNanos > 0L && previous.elapsedRealtimeNanos > 0L ->
            (location.elapsedRealtimeNanos - previous.elapsedRealtimeNanos) / 1_000_000L
        else -> location.time - previous.time
    }
    if (elapsedMillis <= 0L) return location
    val distanceMeters = previous.distanceTo(location)
    val derivedSpeed = distanceMeters / (elapsedMillis / 1_000.0)
    if (derivedSpeed > 0.5 && !location.hasSpeed()) {
        location.speed = derivedSpeed.toFloat()
    }
    if (derivedSpeed > 0.5 && !location.hasBearing()) {
        location.bearing = previous.bearingTo(location)
    }
    return location
}

private fun locationAgeMillis(location: Location): Long {
    val fixNanos = location.elapsedRealtimeNanos
    if (fixNanos > 0L) {
        return ((SystemClock.elapsedRealtimeNanos() - fixNanos) / 1_000_000L).coerceAtLeast(0L)
    }
    return (System.currentTimeMillis() - location.time).coerceAtLeast(0L)
}

private fun emptyFeatureCollection(): FeatureCollection = FeatureCollection.fromFeatures(emptyList())

private fun appendTrail(
    target: MutableList<TrailPoint>,
    location: Location,
    timestampNormalizer: RideMapSessionTimestampNormalizer,
) {
    val next = MapCoordinate(location.latitude, location.longitude)
    if (target.lastOrNull()?.coordinate == next) return
    val elapsedRealtimeMillis = location.elapsedRealtimeNanos
        .takeIf { it > 0L }
        ?.div(1_000_000L)
    val sample = RideMapTrailSample(
        latitude = location.latitude,
        longitude = location.longitude,
        timestampMillis = timestampNormalizer.normalize(
            timestampMillis = elapsedRealtimeMillis ?: location.time,
            source = if (elapsedRealtimeMillis != null) {
                RideMapTimestampSource.ELAPSED_REALTIME
            } else {
                RideMapTimestampSource.WALL_CLOCK
            },
        ),
        accuracyMeters = location.accuracy.takeIf { location.hasAccuracy() },
        speedMetersPerSecond = location.takeIf { it.hasSpeed() }?.speed,
    )
    target += TrailPoint(
        coordinate = next,
        sample = sample,
    )
    while (target.size > MAX_TRAIL_POINTS) target.removeAt(0)
}

private fun pruneTrail(target: MutableList<TrailPoint>, nowMillis: Long) {
    while (target.firstOrNull()?.let { point ->
            !shouldRetainTrailPoint((nowMillis - point.sample.timestampMillis).coerceAtLeast(0L))
        } == true
    ) {
        target.removeAt(0)
    }
}

private fun trailFeatureCollection(
    trail: List<TrailPoint>,
    nowMillis: Long,
): FeatureCollection {
    if (trail.size < 2) return emptyFeatureCollection()
    val features = mutableListOf<Feature>()
    var distanceBehind = 0.0
    connectedRideMapTrailSegments(trail.map { it.sample }).asReversed().forEach { segment ->
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
                ).apply {
                    addNumberProperty("opacity", opacity)
                }.also(features::add)
            }
            distanceBehind += distanceMeters(
                MapCoordinate(older.latitude, older.longitude),
                MapCoordinate(newer.latitude, newer.longitude),
            )
        }
    }
    return FeatureCollection.fromFeatures(features.reversed())
}

private fun distanceMeters(first: MapCoordinate, second: MapCoordinate): Double {
    val lat1 = first.latitude * Math.PI / 180.0
    val lat2 = second.latitude * Math.PI / 180.0
    val dLat = lat2 - lat1
    val dLon = (second.longitude - first.longitude) * Math.PI / 180.0
    val a = sin(dLat / 2.0).pow(2.0) + cos(lat1) * cos(lat2) * sin(dLon / 2.0).pow(2.0)
    return 6_371_000.0 * 2.0 * kotlin.math.atan2(sqrt(a), sqrt(1.0 - a))
}

private fun lastKnownLocation(context: Context): Location? {
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
    val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
    if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) return null
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
        .maxByOrNull { it.time }
}

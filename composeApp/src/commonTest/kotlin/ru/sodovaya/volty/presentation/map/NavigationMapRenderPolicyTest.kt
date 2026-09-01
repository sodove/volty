package ru.sodovaya.volty.presentation.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import ru.sodovaya.volty.domain.location.LocationSource
import ru.sodovaya.volty.domain.location.RideLocationFix
import ru.sodovaya.volty.domain.navigation.GeoCoordinate
import ru.sodovaya.volty.domain.navigation.ManeuverKind
import ru.sodovaya.volty.domain.navigation.RouteAlternative
import ru.sodovaya.volty.domain.navigation.RouteGuidance
import ru.sodovaya.volty.domain.navigation.RouteManeuver
import ru.sodovaya.volty.domain.navigation.RoutePlan
import ru.sodovaya.volty.presentation.navigation.LightNavigationState
import ru.sodovaya.volty.presentation.navigation.NavigationPhase

class NavigationMapRenderPolicyTest {
    @Test
    fun `route ready fits every alternative and gives selected route priority`() {
        val plan = plan()
        val state = LightNavigationState(
            phase = NavigationPhase.RouteReady(plan, selectedRouteId = "short"),
            requestGeneration = 7L,
        )

        val scene = NavigationMapRenderPolicy.scene(state, ownFix = null)

        assertEquals(listOf("short", "long"), scene.routes.map(NavigationRouteLine::routeId))
        assertEquals(listOf(true, false), scene.routes.map(NavigationRouteLine::selected))
        assertEquals(listOf(true, false), scene.routes.map(NavigationRouteLine::active))
        val fit = assertIs<MapCameraRequest.FitAlternatives>(scene.cameraRequest)
        assertEquals(7L, fit.sequence)
        assertEquals(4, fit.points.size)
    }

    @Test
    fun `route preview suspends live follow so the fitted route remains visible`() {
        val state = LightNavigationState(
            phase = NavigationPhase.RouteReady(plan(), selectedRouteId = "short"),
            requestGeneration = 7L,
            followState = RideMapFollowState(RideMapFollowMode.FOLLOWING),
        )

        val scene = NavigationMapRenderPolicy.scene(state, ownFix = fix(1_000L))

        assertEquals(RideMapFollowMode.FREE, scene.followState.mode)
    }

    @Test
    fun `navigation follows own fix but never refits route bounds`() {
        val fix = fix(1_000L)
        val state = LightNavigationState(
            phase = NavigationPhase.Navigating(
                plan = plan(),
                selectedRouteId = "short",
                guidance = guidance("short", remainingDistance = 500.0),
            ),
            locationStatus = ru.sodovaya.volty.presentation.navigation.LocationUiStatus.FRESH,
            requestGeneration = 8L,
        )

        val scene = NavigationMapRenderPolicy.scene(state, ownFix = fix)

        assertIs<MapCameraRequest.FollowFix>(scene.cameraRequest)
        assertEquals(fix, assertIs<MapCameraRequest.FollowFix>(scene.cameraRequest).fix)
        assertTrue(scene.routes.first().active)
        assertEquals(0.5, scene.routes.first().completedFraction)
    }

    @Test
    fun `stale navigation fix keeps route visible but disables active guidance and follow camera`() {
        val state = LightNavigationState(
            phase = NavigationPhase.Navigating(
                plan = plan(),
                selectedRouteId = "short",
                guidance = guidance("short", remainingDistance = 500.0),
            ),
            locationStatus = ru.sodovaya.volty.presentation.navigation.LocationUiStatus.STALE,
            requestGeneration = 8L,
        )

        val scene = NavigationMapRenderPolicy.scene(state, ownFix = fix(1_000L))

        assertTrue(scene.routes.first().selected)
        assertFalse(scene.routes.first().active)
        assertEquals(null, scene.cameraRequest)
    }

    @Test
    fun `free follow preserves user camera and completed fraction is clamped`() {
        val state = LightNavigationState(
            phase = NavigationPhase.Navigating(
                plan = plan(),
                selectedRouteId = "short",
                guidance = guidance("short", remainingDistance = -100.0),
            ),
            locationStatus = ru.sodovaya.volty.presentation.navigation.LocationUiStatus.FRESH,
            followState = RideMapFollowState(RideMapFollowMode.FREE, 42L),
        )

        val scene = NavigationMapRenderPolicy.scene(state, ownFix = fix(1_000L))

        assertEquals(null, scene.cameraRequest)
        assertEquals(RideMapFollowMode.FREE, scene.followState.mode)
        assertEquals(1.0, scene.routes.first().completedFraction)
    }

    @Test
    fun `rerouting retains old geometry but marks it inactive`() {
        val state = LightNavigationState(
            phase = NavigationPhase.Rerouting(
                plan = plan(),
                selectedRouteId = "short",
                attempt = 1,
                failure = null,
            ),
        )

        val scene = NavigationMapRenderPolicy.scene(state, ownFix = fix(1_000L))

        assertTrue(scene.routes.isNotEmpty())
        assertTrue(scene.routes.first().selected)
        assertFalse(scene.routes.first().active)
        assertEquals(null, scene.cameraRequest)
    }

    @Test
    fun `reset has no route or destination source`() {
        val scene = NavigationMapRenderPolicy.scene(
            state = LightNavigationState(),
            ownFix = null,
        )

        assertTrue(scene.routes.isEmpty())
        assertEquals(null, scene.destination)
        assertEquals(null, scene.cameraRequest)
    }

    @Test
    fun `first idle location asks the map to recenter on the rider`() {
        val riderFix = fix(1_000L)

        val scene = NavigationMapRenderPolicy.scene(
            state = LightNavigationState(),
            ownFix = riderFix,
        )

        val request = assertIs<MapCameraRequest.Recenter>(scene.cameraRequest)
        assertEquals(riderFix, request.fix)
    }

    @Test
    fun `participants and trail remain independent from navigation phase`() {
        val marker = ru.sodovaya.volty.presentation.nearby.ParticipantMarker(
            userId = "rider",
            label = "Райдер",
            latitude = 56.83,
            longitude = 60.60,
            accuracyMeters = 8.0,
            presence = ru.sodovaya.volty.domain.social.PresenceStatus.ONLINE,
            stale = false,
        )
        val trail = NavigationTrailPoint(
            coordinate = GeoCoordinate(56.83, 60.60),
            sample = RideMapTrailSample(56.83, 60.60, 1_000L, 5f, 1f),
        )

        val scene = NavigationMapRenderPolicy.scene(
            state = LightNavigationState(),
            ownFix = null,
            trail = listOf(trail),
            participantMarkers = listOf(marker),
        )

        assertSame(marker, scene.participantMarkers.single())
        assertSame(trail, scene.trail.single())
    }

    @Test
    fun `recenter request type carries a distinct camera contract`() {
        val request: MapCameraRequest = MapCameraRequest.Recenter(
            sequence = 10L,
            fix = fix(1_000L),
        )

        assertEquals(10L, request.sequence)
        assertIs<MapCameraRequest.Recenter>(request)

        val scene = NavigationMapRenderPolicy.scene(
            state = LightNavigationState(),
            ownFix = fix(1_000L),
            recenterSequence = 10L,
        )
        assertEquals(request, scene.cameraRequest)
    }

    @Test
    fun `route fit wins over a retained recenter sequence`() {
        val routePlan = plan()
        val state = LightNavigationState(
            phase = NavigationPhase.RouteReady(routePlan, selectedRouteId = "short"),
            requestGeneration = 7L,
        )

        val scene = NavigationMapRenderPolicy.scene(
            state = state,
            ownFix = fix(1_000L),
            recenterSequence = 10L,
        )

        assertEquals(
            MapCameraRequest.FitAlternatives(
                sequence = 7L,
                points = routePlan.alternatives.flatMap { it.geometry },
            ),
            scene.cameraRequest,
        )
    }

    private companion object {
        fun fix(timestamp: Long) = RideLocationFix(
            coordinate = GeoCoordinate(56.83, 60.60),
            accuracyMeters = 10.0,
            speedMetersPerSecond = null,
            bearingDegrees = null,
            capturedAtEpochMillis = timestamp,
            elapsedRealtimeMillis = timestamp,
            source = LocationSource.GPS,
        )

        fun guidance(routeId: String, remainingDistance: Double) = RouteGuidance(
            routeId = routeId,
            maneuver = RouteManeuver(
                id = "straight",
                kind = ManeuverKind.STRAIGHT,
                instruction = "Ехать прямо",
                streetName = null,
                shapeIndex = 1,
                distanceMeters = 500.0,
            ),
            distanceToManeuverMeters = 100.0,
            remainingDistanceMeters = remainingDistance,
            remainingDurationSeconds = 60L,
            projectedShapeIndex = 1,
        )

        fun plan() = RoutePlan(
            destination = ru.sodovaya.volty.domain.navigation.PlaceCandidate(
                id = "destination",
                title = "Финиш",
                subtitle = null,
                coordinate = GeoCoordinate(56.83, 60.61),
            ),
            alternatives = listOf(
                alternative("short", 1_000.0, 56.60),
                alternative("long", 2_000.0, 56.61),
            ),
        )

        fun alternative(id: String, distance: Double, destinationLongitude: Double) = RouteAlternative(
            id = id,
            distanceMeters = distance,
            durationSeconds = 120L,
            geometry = listOf(
                GeoCoordinate(56.83, 60.60),
                GeoCoordinate(56.83, destinationLongitude),
            ),
            maneuvers = listOf(
                RouteManeuver(
                    id = "$id-arrive",
                    kind = ManeuverKind.ARRIVE,
                    instruction = "Вы прибыли",
                    streetName = null,
                    shapeIndex = 1,
                    distanceMeters = 0.0,
                ),
            ),
        )
    }
}

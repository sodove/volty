package ru.sodovaya.volty.presentation.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ru.sodovaya.volty.domain.navigation.ArrivalSocEstimate
import ru.sodovaya.volty.domain.navigation.ArrivalSocUnknownReason
import ru.sodovaya.volty.domain.navigation.GeoCoordinate
import ru.sodovaya.volty.domain.navigation.ManeuverKind
import ru.sodovaya.volty.domain.navigation.NavigationFailure
import ru.sodovaya.volty.domain.navigation.PlaceCandidate
import ru.sodovaya.volty.domain.navigation.RouteAlternative
import ru.sodovaya.volty.domain.navigation.RouteGuidance
import ru.sodovaya.volty.domain.navigation.RouteManeuver
import ru.sodovaya.volty.domain.navigation.RoutePlan
import ru.sodovaya.volty.domain.navigation.RouteProfile
import ru.sodovaya.volty.util.UnitSystem

class LightNavigationUiMapperTest {
    private val destination = PlaceCandidate(
        id = "place-1",
        title = "Набережная",
        subtitle = "Екатеринбург",
        coordinate = GeoCoordinate(56.8389, 60.6057),
    )

    @Test
    fun `planning exposes search and keeps selected profile visibly unconfirmed`() {
        val state = LightNavigationState(
            phase = NavigationPhase.Planning(
                query = "набережная",
                searchResults = listOf(destination),
                destination = destination,
                profile = RouteProfile.LIGHT_EV,
                profileConfirmed = false,
                requestInFlight = false,
                failure = null,
            ),
        )

        val ui = LightNavigationUiMapper.map(state, UnitSystem.METRIC)

        assertEquals(NavigationUiPhase.PLANNING, ui.phase)
        assertEquals(listOf(destination), ui.searchResults)
        assertTrue(ui.profiles.single { it.value == RouteProfile.LIGHT_EV }.selected)
        assertFalse(ui.profiles.single { it.value == RouteProfile.LIGHT_EV }.confirmed)
        assertTrue(ui.canConfirmProfile)
        assertFalse(ui.canStart)
    }

    @Test
    fun `alternatives use the existing formatter and selected route is marked`() {
        val plan = plan()
        val state = LightNavigationState(
            phase = NavigationPhase.RouteReady(plan, selectedRouteId = "route-b"),
            locationStatus = LocationUiStatus.FRESH,
        )

        val ui = LightNavigationUiMapper.map(state, UnitSystem.IMPERIAL)

        assertEquals("1.6", ui.alternatives.first { it.routeId == "route-a" }.distanceText)
        assertEquals(3L, ui.alternatives.first { it.routeId == "route-a" }.durationMinutes)
        assertTrue(ui.alternatives.single { it.routeId == "route-b" }.selected)
        assertTrue(ui.canStart)
    }

    @Test
    fun `every maneuver kind has a visual category`() {
        val plan = plan()
        val route = plan.alternatives.first()
        val results = ManeuverKind.values().map { kind ->
            val guidance = RouteGuidance(
                routeId = route.id,
                maneuver = route.maneuvers.first().copy(kind = kind),
                distanceToManeuverMeters = 250.0,
                remainingDistanceMeters = 2_000.0,
                remainingDurationSeconds = 180L,
                projectedShapeIndex = 0,
            )
            val state = LightNavigationState(
                phase = NavigationPhase.Navigating(plan, route.id, guidance),
                locationStatus = LocationUiStatus.FRESH,
            )
            LightNavigationUiMapper.map(state, UnitSystem.METRIC).maneuver?.icon
        }

        assertEquals(ManeuverKind.values().size, results.distinct().size)
    }

    @Test
    fun `stale gps hides guidance and rerouting hides old guidance`() {
        val plan = plan()
        val guidance = RouteGuidance(
            routeId = "route-a",
            maneuver = plan.alternatives.first().maneuvers.first(),
            distanceToManeuverMeters = 250.0,
            remainingDistanceMeters = 2_000.0,
            remainingDurationSeconds = 180L,
            projectedShapeIndex = 0,
        )

        val stale = LightNavigationUiMapper.map(
            LightNavigationState(
                phase = NavigationPhase.Navigating(plan, "route-a", guidance),
                locationStatus = LocationUiStatus.STALE,
            ),
            UnitSystem.METRIC,
        )
        val rerouting = LightNavigationUiMapper.map(
            LightNavigationState(
                phase = NavigationPhase.Rerouting(plan, "route-a", attempt = 1, failure = null),
                locationStatus = LocationUiStatus.FRESH,
            ),
            UnitSystem.METRIC,
        )

        assertNull(stale.maneuver)
        assertNull(stale.remainingDistanceText)
        assertEquals(NavigationUiCopyKey.LOCATION_STALE, stale.locationBanner)
        assertNull(rerouting.maneuver)
        assertNull(rerouting.remainingDistanceText)
        assertEquals(NavigationUiPhase.REROUTING, rerouting.phase)
    }

    @Test
    fun `known arrival soc is numeric and every unknown reason stays copy-only`() {
        val known = LightNavigationUiMapper.map(
            LightNavigationState(arrivalSoc = ArrivalSocEstimate.Known(percent = 42)),
            UnitSystem.METRIC,
        )
        assertEquals(42, known.arrivalSocPercent)
        assertNull(known.arrivalSocReason)

        ArrivalSocUnknownReason.values().forEach { reason ->
            val unknown = LightNavigationUiMapper.map(
                LightNavigationState(arrivalSoc = ArrivalSocEstimate.Unknown(reason)),
                UnitSystem.METRIC,
            )
            assertNull(unknown.arrivalSocPercent)
            assertEquals(reason, reasonFromCopyKey(unknown.arrivalSocReason))
        }
    }

    @Test
    fun `typed failures and location statuses become non-empty resource keys`() {
        val failures = listOf<NavigationFailure>(
            NavigationFailure.Offline,
            NavigationFailure.NoRoute,
            NavigationFailure.RateLimited(4),
            NavigationFailure.ProviderUnavailable,
            NavigationFailure.InvalidRequest("bad"),
            NavigationFailure.MalformedResponse,
        )
        failures.forEach { failure ->
            val state = LightNavigationState(
                phase = NavigationPhase.Planning(
                    query = "park",
                    searchResults = emptyList(),
                    destination = null,
                    profile = null,
                    profileConfirmed = false,
                    requestInFlight = false,
                    failure = failure,
                ),
            )
            assertTrue(LightNavigationUiMapper.map(state, UnitSystem.METRIC).failureBanner != null)
        }
        assertEquals(
            NavigationUiCopyKey.LOCATION_PERMISSION_REQUIRED,
            LightNavigationUiMapper.map(
                LightNavigationState(locationStatus = LocationUiStatus.PERMISSION_REQUIRED),
                UnitSystem.METRIC,
            ).locationBanner,
        )
        assertEquals(
            NavigationUiCopyKey.LOCATION_FRESH_REQUIRED,
            LightNavigationUiMapper.map(
                LightNavigationState(phase = NavigationPhase.RouteReady(plan(), "route-a")),
                UnitSystem.METRIC,
            ).locationBanner,
        )
        assertEquals(
            NavigationUiCopyKey.ROUTE_OFFLINE,
            LightNavigationUiMapper.map(
                LightNavigationState(
                    phase = NavigationPhase.Planning(
                        query = "park",
                        searchResults = emptyList(),
                        destination = destination,
                        profile = RouteProfile.LIGHT_EV,
                        profileConfirmed = true,
                        requestInFlight = false,
                        failure = NavigationFailure.Offline,
                    ),
                ),
                UnitSystem.METRIC,
            ).failureBanner,
        )
    }

    private fun plan(): RoutePlan = RoutePlan(
        destination = destination,
        profile = RouteProfile.LIGHT_EV,
        alternatives = listOf(
            route("route-a", distanceMeters = 2_500.0, durationSeconds = 150L),
            route("route-b", distanceMeters = 3_000.0, durationSeconds = 210L),
        ),
    )

    private fun route(id: String, distanceMeters: Double, durationSeconds: Long): RouteAlternative {
        val geometry = listOf(
            GeoCoordinate(56.83, 60.60),
            GeoCoordinate(56.835, 60.603),
            destination.coordinate,
        )
        val maneuver = RouteManeuver(
            id = "$id-arrive",
            kind = ManeuverKind.ARRIVE,
            instruction = "Прибытие",
            streetName = null,
            shapeIndex = 0,
            distanceMeters = 250.0,
        )
        return RouteAlternative(id, distanceMeters, durationSeconds, geometry, listOf(maneuver))
    }

    private fun reasonFromCopyKey(key: NavigationUiCopyKey?): ArrivalSocUnknownReason = when (key) {
        NavigationUiCopyKey.SOC_NO_ROUTE -> ArrivalSocUnknownReason.NO_ROUTE
        NavigationUiCopyKey.SOC_BMS_DISCONNECTED -> ArrivalSocUnknownReason.BMS_DISCONNECTED
        NavigationUiCopyKey.SOC_PACKS_PARTIAL -> ArrivalSocUnknownReason.PACKS_PARTIAL
        NavigationUiCopyKey.SOC_UNEARNED -> ArrivalSocUnknownReason.SOC_UNEARNED
        NavigationUiCopyKey.SOC_CAPACITY_UNEARNED -> ArrivalSocUnknownReason.CAPACITY_UNEARNED
        NavigationUiCopyKey.SOC_TELEMETRY_STALE -> ArrivalSocUnknownReason.TELEMETRY_STALE
        NavigationUiCopyKey.SOC_CONSUMPTION_UNEARNED -> ArrivalSocUnknownReason.CONSUMPTION_UNEARNED
        else -> error("Unexpected arrival SoC key: $key")
    }
}

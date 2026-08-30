package ru.sodovaya.volty.presentation.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ru.sodovaya.volty.domain.navigation.ArrivalSocEstimate
import ru.sodovaya.volty.domain.navigation.ArrivalSocUnknownReason
import ru.sodovaya.volty.domain.navigation.GeoCoordinate
import ru.sodovaya.volty.domain.navigation.ManeuverKind
import ru.sodovaya.volty.domain.navigation.NavigationFailure
import ru.sodovaya.volty.domain.navigation.PlaceCandidate
import ru.sodovaya.volty.domain.navigation.RouteAlternative
import ru.sodovaya.volty.domain.navigation.RouteManeuver
import ru.sodovaya.volty.domain.navigation.RoutePlan
import ru.sodovaya.volty.domain.navigation.RouteProfile

class NavigationReducerTest {
    private val place = PlaceCandidate(
        id = "place-1",
        title = "Набережная",
        subtitle = "Екатеринбург",
        coordinate = GeoCoordinate(56.8389, 60.6057),
    )
    private val secondPlace = place.copy(id = "place-2", title = "Парк")

    @Test
    fun `idle opens a planning scene and query change invalidates old results`() {
        val planning = NavigationReducer.reduce(
            LightNavigationState(),
            NavigationAction.PlannerRequested,
        )
        val withResults = planning.copy(
            phase = NavigationPhase.Planning(
                query = "набережная",
                searchResults = listOf(place),
                destination = place,
                profile = RouteProfile.LIGHT_EV,
                profileConfirmed = true,
                requestInFlight = false,
                failure = null,
            ),
        )

        val changed = NavigationReducer.reduce(
            withResults,
            NavigationAction.QueryChanged("парк"),
        )
        val next = assertIs<NavigationPhase.Planning>(changed.phase)
        assertEquals("парк", next.query)
        assertTrue(next.searchResults.isEmpty())
        assertNull(next.destination)
        assertNull(next.profile)
        assertFalse(next.profileConfirmed)
        assertEquals(withResults.requestGeneration + 1L, changed.requestGeneration)
    }

    @Test
    fun `place selection is not enough and suggested profile never confirms`() {
        val initial = NavigationReducer.reduce(
            LightNavigationState(),
            NavigationAction.PlannerRequested,
        )
        val selected = NavigationReducer.reduce(
            initial,
            NavigationAction.PlaceSelected(place),
        )
        val selectedPhase = assertIs<NavigationPhase.Planning>(selected.phase)
        assertEquals(place, selectedPhase.destination)
        assertNull(selectedPhase.profile)
        assertFalse(selectedPhase.profileConfirmed)

        val suggested = NavigationReducer.reduce(
            selected,
            NavigationAction.ProfileSelected(RouteProfile.LIGHT_EV),
        )
        val suggestedPhase = assertIs<NavigationPhase.Planning>(suggested.phase)
        assertEquals(RouteProfile.LIGHT_EV, suggestedPhase.profile)
        assertFalse(suggestedPhase.profileConfirmed)

        val confirmed = NavigationReducer.reduce(suggested, NavigationAction.ProfileConfirmed)
        assertTrue(assertIs<NavigationPhase.Planning>(confirmed.phase).profileConfirmed)
    }

    @Test
    fun `changing profile invalidates confirmation and routes`() {
        val planning = NavigationPhase.Planning(
            query = "набережная",
            searchResults = listOf(place),
            destination = place,
            profile = RouteProfile.LIGHT_EV,
            profileConfirmed = true,
            requestInFlight = false,
            failure = null,
        )
        val state = LightNavigationState(phase = planning)
        val changed = NavigationReducer.reduce(
            state,
            NavigationAction.ProfileSelected(RouteProfile.BICYCLE),
        )
        val next = assertIs<NavigationPhase.Planning>(changed.phase)
        assertEquals(RouteProfile.BICYCLE, next.profile)
        assertFalse(next.profileConfirmed)
        assertEquals(listOf(place), next.searchResults)
        assertNull(next.failure)
        assertEquals(state.requestGeneration + 1L, changed.requestGeneration)
    }

    @Test
    fun `route plan selects only a stable alternative id`() {
        val plan = routePlan()
        val ready = NavigationReducer.reduce(
            LightNavigationState(),
            NavigationAction.RouteLoaded(plan),
        )
        val readyPhase = assertIs<NavigationPhase.RouteReady>(ready.phase)
        assertEquals("route-a", readyPhase.selectedRouteId)

        val selected = NavigationReducer.reduce(
            ready,
            NavigationAction.AlternativeSelected("route-b"),
        )
        assertEquals("route-b", assertIs<NavigationPhase.RouteReady>(selected.phase).selectedRouteId)

        val ignored = NavigationReducer.reduce(
            selected,
            NavigationAction.AlternativeSelected("unknown"),
        )
        assertEquals("route-b", assertIs<NavigationPhase.RouteReady>(ignored.phase).selectedRouteId)
    }

    @Test
    fun `stop clears every navigation surface and fences old work`() {
        val state = LightNavigationState(
            phase = NavigationPhase.Navigating(
                plan = routePlan(),
                selectedRouteId = "route-b",
                guidance = null,
            ),
            locationStatus = LocationUiStatus.FRESH,
            arrivalSoc = ArrivalSocEstimate.Known(64),
            followState = LightNavigationState().followState.copy(lastGestureAtMillis = 42L),
            requestGeneration = 17L,
        )
        val stopped = NavigationReducer.reduce(state, NavigationAction.StopNavigation)
        assertIs<NavigationPhase.Idle>(stopped.phase)
        assertEquals(LocationUiStatus.NOT_REQUESTED, stopped.locationStatus)
        assertEquals(
            ArrivalSocEstimate.Unknown(ArrivalSocUnknownReason.NO_ROUTE),
            stopped.arrivalSoc,
        )
        assertEquals(LightNavigationState().followState, stopped.followState)
        assertEquals(18L, stopped.requestGeneration)

        val oldFailure = NavigationReducer.reduce(
            stopped,
            NavigationAction.RouteFailed(
                NavigationFailure.RateLimited(retryAfterSeconds = 9L),
                requestGeneration = 17L,
            ),
        )
        assertIs<NavigationPhase.Idle>(oldFailure.phase)
        assertEquals(stopped, oldFailure)
    }

    private fun routePlan(): RoutePlan {
        val geometry = listOf(
            GeoCoordinate(56.8, 60.6),
            GeoCoordinate(56.81, 60.61),
        )
        fun alternative(id: String) = RouteAlternative(
            id = id,
            distanceMeters = 1_500.0,
            durationSeconds = 240L,
            geometry = geometry,
            maneuvers = listOf(
                RouteManeuver(
                    id = "$id-depart",
                    kind = ManeuverKind.DEPART,
                    instruction = "Ехать прямо",
                    streetName = null,
                    shapeIndex = 0,
                    distanceMeters = 1_500.0,
                ),
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
        return RoutePlan(
            destination = place,
            profile = RouteProfile.LIGHT_EV,
            alternatives = listOf(alternative("route-a"), alternative("route-b")),
        )
    }
}

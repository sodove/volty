package ru.sodovaya.volty.domain.navigation

import ru.sodovaya.volty.domain.location.LocationSource
import ru.sodovaya.volty.domain.location.RideLocationFix
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RouteProgressEngineTest {
    @Test
    fun projection_reports_start_middle_and_end_with_non_negative_turn_distance() {
        val route = route(id = "projection", maneuverShapeIndex = 2)
        val engine = RouteProgressEngine()

        val start = assertIs<RouteProgressUpdate.OnRoute>(engine.update(route, fixAt(0.0, 1_000L), 1_100L))
        val middle = assertIs<RouteProgressUpdate.OnRoute>(engine.update(route, fixAt(150.0, 2_000L), 2_100L))
        val endFirst = assertIs<RouteProgressUpdate.OnRoute>(engine.update(route, fixAt(300.0, 3_000L), 3_100L))

        assertEquals(0, start.guidance.projectedShapeIndex)
        assertEquals(1, middle.guidance.projectedShapeIndex)
        assertEquals(3, endFirst.guidance.projectedShapeIndex)
        assertEquals(200.0, start.guidance.distanceToManeuverMeters, 2.0)
        assertEquals(50.0, middle.guidance.distanceToManeuverMeters, 2.0)
        assertTrue(endFirst.guidance.distanceToManeuverMeters >= 0.0)
        assertEquals(300.0, start.guidance.remainingDistanceMeters, 2.0)
        assertEquals(150.0, middle.guidance.remainingDistanceMeters, 2.0)
        assertEquals(0.0, endFirst.guidance.remainingDistanceMeters, 2.0)
    }

    @Test
    fun remaining_duration_is_monotonic_and_maneuver_advances_at_shape_index() {
        val route = route(id = "maneuvers", maneuverShapeIndex = 1)
        val engine = RouteProgressEngine()

        val beforeTurn = assertIs<RouteProgressUpdate.OnRoute>(engine.update(route, fixAt(99.0, 1_000L), 1_100L))
        val atTurn = assertIs<RouteProgressUpdate.OnRoute>(engine.update(route, fixAt(100.0, 2_000L), 2_100L))
        val afterTurn = assertIs<RouteProgressUpdate.OnRoute>(engine.update(route, fixAt(200.0, 3_000L), 3_100L))

        assertEquals(ManeuverKind.RIGHT, beforeTurn.guidance.maneuver.kind)
        assertEquals(ManeuverKind.ARRIVE, atTurn.guidance.maneuver.kind)
        assertEquals(ManeuverKind.ARRIVE, afterTurn.guidance.maneuver.kind)
        assertTrue(beforeTurn.guidance.remainingDurationSeconds >= atTurn.guidance.remainingDurationSeconds)
        assertTrue(atTurn.guidance.remainingDurationSeconds >= afterTurn.guidance.remainingDurationSeconds)
        assertTrue(beforeTurn.guidance.distanceToManeuverMeters >= 0.0)
    }

    @Test
    fun loop_projection_prefers_a_forward_candidate_near_prior_progress() {
        val route = route(
            id = "loop",
            points = listOf(0.0, 100.0, 0.0, 100.0),
            maneuverShapeIndex = 3,
        )
        val engine = RouteProgressEngine()

        engine.update(route, fixAt(90.0, 1_000L), 1_100L)
        val loopExit = assertIs<RouteProgressUpdate.OnRoute>(engine.update(route, fixAt(0.0, 2_000L), 2_100L))

        assertEquals(2, loopExit.guidance.projectedShapeIndex)
        assertEquals(100.0, loopExit.guidance.remainingDistanceMeters, 3.0)
    }

    @Test
    fun changing_route_id_resets_progress() {
        val engine = RouteProgressEngine()
        val first = route(id = "first")
        val second = route(id = "second")

        engine.update(first, fixAt(290.0, 1_000L), 1_100L)
        val reset = assertIs<RouteProgressUpdate.OnRoute>(engine.update(second, fixAt(0.0, 2_000L), 2_100L))

        assertEquals(300.0, reset.guidance.remainingDistanceMeters, 2.0)
        assertEquals("second", reset.guidance.routeId)
    }

    @Test
    fun untrusted_position_never_exposes_guidance() {
        val route = route(id = "trust")
        val engine = RouteProgressEngine()

        assertIs<RouteProgressUpdate.Unavailable>(engine.update(route, null, 1_000L))
        assertEquals(
            NavigationPositionProblem.STALE,
            (engine.update(route, fixAt(0.0, 1_000L), 6_001L) as RouteProgressUpdate.Unavailable).problem,
        )
        assertEquals(
            NavigationPositionProblem.POOR_ACCURACY,
            (engine.update(route, fixAt(0.0, 7_000L, accuracyMeters = 50.1), 7_100L) as RouteProgressUpdate.Unavailable).problem,
        )

        engine.update(route, fixAt(0.0, 8_000L), 8_100L)
        val outOfOrder = engine.update(route, fixAt(0.0, 7_999L), 8_100L)
        assertEquals(NavigationPositionProblem.UNKNOWN, (outOfOrder as RouteProgressUpdate.Unavailable).problem)

        assertFailsWith<IllegalArgumentException> {
            fixAt(0.0, 9_000L).copy(speedMetersPerSecond = Double.NaN)
        }
    }

    @Test
    fun one_noisy_fix_recovers_and_does_not_confirm_off_route() {
        val route = route(id = "noise")
        val engine = RouteProgressEngine()

        val candidate = assertIs<RouteProgressUpdate.OffRouteCandidate>(
            engine.update(route, fixAt(100.0, 1_000L, accuracyMeters = 5.0, lateralMeters = 100.0), 1_100L),
        )
        val recovered = engine.update(route, fixAt(100.0, 2_000L), 2_100L)

        assertTrue(candidate.distanceFromRouteMeters > 30.0)
        assertIs<RouteProgressUpdate.OnRoute>(recovered)
    }

    @Test
    fun off_route_confirmation_requires_three_fresh_fixes_spanning_two_seconds() {
        val route = route(id = "confirm")
        val engine = RouteProgressEngine()

        assertIs<RouteProgressUpdate.OffRouteCandidate>(offRoute(engine, route, 1_000L))
        assertIs<RouteProgressUpdate.OffRouteCandidate>(offRoute(engine, route, 1_500L))
        assertIs<RouteProgressUpdate.OffRouteCandidate>(offRoute(engine, route, 2_999L))
        val confirmed = assertIs<RouteProgressUpdate.OffRouteConfirmed>(offRoute(engine, route, 3_000L))
        val stable = assertIs<RouteProgressUpdate.OffRouteConfirmed>(offRoute(engine, route, 3_100L))

        assertEquals(confirmed.episodeId, stable.episodeId)
        assertTrue(confirmed.episodeId > 0L)
    }

    @Test
    fun accuracy_expands_off_route_threshold_and_stale_fix_resets_evidence() {
        val route = route(id = "threshold")
        val engine = RouteProgressEngine()

        assertIs<RouteProgressUpdate.OnRoute>(
            engine.update(route, fixAt(100.0, 1_000L, accuracyMeters = 50.0, lateralMeters = 80.0), 1_100L),
        )
        assertIs<RouteProgressUpdate.OffRouteCandidate>(
            engine.update(route, fixAt(100.0, 2_000L, accuracyMeters = 20.0, lateralMeters = 80.0), 2_100L),
        )

        assertIs<RouteProgressUpdate.OffRouteCandidate>(offRoute(engine, route, 3_000L))
        assertIs<RouteProgressUpdate.Unavailable>(
            engine.update(route, fixAt(100.0, 3_000L, lateralMeters = 100.0), 8_001L),
        )
        assertIs<RouteProgressUpdate.OffRouteCandidate>(offRoute(engine, route, 9_000L))
        val secondAfterReset = assertIs<RouteProgressUpdate.OffRouteCandidate>(offRoute(engine, route, 10_000L))

        assertTrue(secondAfterReset.distanceFromRouteMeters > 30.0)
    }

    @Test
    fun arrival_requires_two_fresh_qualifying_fixes() {
        val route = route(id = "arrival")
        val engine = RouteProgressEngine()

        val first = engine.update(route, fixAt(300.0, 1_000L), 1_100L)
        val second = engine.update(route, fixAt(300.0, 2_000L), 2_100L)
        val third = engine.update(route, fixAt(300.0, 3_000L), 3_100L)

        assertIs<RouteProgressUpdate.OnRoute>(first)
        assertIs<RouteProgressUpdate.Arrived>(second)
        assertIs<RouteProgressUpdate.Arrived>(third)
    }

    private fun offRoute(engine: RouteProgressEngine, route: RouteAlternative, capturedAt: Long): RouteProgressUpdate =
        engine.update(
            route,
            fixAt(100.0, capturedAt, accuracyMeters = 5.0, lateralMeters = 100.0),
            capturedAt + 100L,
        )

    private fun route(
        id: String,
        points: List<Double> = listOf(0.0, 100.0, 200.0, 300.0),
        maneuverShapeIndex: Int = 2,
    ): RouteAlternative {
        val geometry = points.map { coordinateAt(it) }
        return RouteAlternative(
            id = id,
            distanceMeters = points.zipWithNext().sumOf { (from, to) -> abs(to - from) },
            durationSeconds = 600L,
            geometry = geometry,
            maneuvers = listOf(
                maneuver("depart", ManeuverKind.DEPART, 0),
                maneuver("turn", ManeuverKind.RIGHT, maneuverShapeIndex),
                maneuver("arrive", ManeuverKind.ARRIVE, points.lastIndex),
            ),
        )
    }

    private fun maneuver(id: String, kind: ManeuverKind, shapeIndex: Int): RouteManeuver = RouteManeuver(
        id = id,
        kind = kind,
        instruction = id,
        streetName = null,
        shapeIndex = shapeIndex,
        distanceMeters = 1.0,
    )

    private fun fixAt(
        routeMeters: Double,
        capturedAt: Long,
        accuracyMeters: Double = 5.0,
        lateralMeters: Double = 0.0,
    ): RideLocationFix = RideLocationFix(
        coordinate = GeoCoordinate(
            latitude = lateralMeters / METERS_PER_DEGREE,
            longitude = routeMeters / METERS_PER_DEGREE,
        ),
        accuracyMeters = accuracyMeters,
        speedMetersPerSecond = null,
        bearingDegrees = null,
        capturedAtEpochMillis = capturedAt,
        elapsedRealtimeMillis = capturedAt,
        source = LocationSource.GPS,
    )

    private fun coordinateAt(routeMeters: Double): GeoCoordinate = GeoCoordinate(
        latitude = 0.0,
        longitude = routeMeters / METERS_PER_DEGREE,
    )

    private companion object {
        const val METERS_PER_DEGREE = 111_320.0
    }
}

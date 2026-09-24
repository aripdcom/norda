package com.aripd.norda.core.nav

import com.aripd.norda.core.track.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class WaypointScopeTest {

    private fun point(lat: Double, lon: Double, timeMillis: Long = 0L) = TrackPoint(
        timeMillis = timeMillis,
        latitude = lat,
        longitude = lon,
        altitude = 0.0,
        accuracyM = 5f,
        speedMps = 1.4f,
        bearingDeg = 0f
    )

    private fun waypoint(
        id: Long,
        lat: Double,
        lon: Double,
        createdAtMillis: Long
    ) = Waypoint(id, "W$id", lat, lon, null, createdAtMillis)

    // A short walk near 40.99 N, and the outing runs from 1000 to 9000.
    private val track = listOf(
        point(40.9900, 29.1390, 1500L),
        point(40.9910, 29.1400, 5000L),
        point(40.9920, 29.1410, 8500L)
    )

    // The field case (F-18): the Sept 24 export carried a waypoint 6 km from
    // the route, saved on another day. It does not belong to this outing.
    @Test
    fun farAwayAndOlderIsLeftOut() {
        val home = waypoint(1, 40.9577, 29.0730, 10L)
        assertEquals(
            emptyList<Waypoint>(),
            WaypointScope.forTrack(listOf(home), track, 1000L, 9000L)
        )
    }

    @Test
    fun onTheRouteIsKeptEvenIfOlder() {
        val onRoute = waypoint(2, 40.9911, 29.1401, 10L)
        assertEquals(
            listOf(onRoute),
            WaypointScope.forTrack(listOf(onRoute), track, 1000L, 9000L)
        )
    }

    // Marked during the walk: part of the outing's record wherever it sits —
    // a summit read off the map is still something this walk produced.
    @Test
    fun createdDuringTheOutingIsKeptEvenIfFarAway() {
        val marked = waypoint(3, 41.2000, 29.9000, 5000L)
        assertEquals(
            listOf(marked),
            WaypointScope.forTrack(listOf(marked), track, 1000L, 9000L)
        )
    }

    // The window is the recording's, not the first accepted point's: a
    // waypoint saved while GPS was still settling belongs to the outing.
    @Test
    fun windowEdgesAreInclusive() {
        val atStart = waypoint(4, 41.2000, 29.9000, 1000L)
        val atEnd = waypoint(5, 41.2000, 29.9000, 9000L)
        val justAfter = waypoint(6, 41.2000, 29.9000, 9001L)
        assertEquals(
            listOf(atStart, atEnd),
            WaypointScope.forTrack(listOf(atStart, atEnd, justAfter), track, 1000L, 9000L)
        )
    }

    @Test
    fun orderIsPreserved() {
        val a = waypoint(7, 40.9901, 29.1391, 10L)
        val b = waypoint(8, 41.2000, 29.9000, 4000L)
        val c = waypoint(9, 40.9919, 29.1409, 20L)
        assertEquals(
            listOf(a, b, c),
            WaypointScope.forTrack(listOf(a, b, c), track, 1000L, 9000L)
        )
    }

    // Due south of the start, so the north-east end of the route cannot be
    // the nearer point: ~230 m is inside the margin, ~320 m is not.
    @Test
    fun theMarginIsAroundTheRouteItself() {
        val inside = waypoint(10, 40.9900 - 0.00207, 29.1390, 10L)
        val outside = waypoint(11, 40.9900 - 0.00288, 29.1390, 10L)
        assertEquals(
            listOf(inside),
            WaypointScope.forTrack(listOf(inside, outside), track, 1000L, 9000L)
        )
    }

    // No points to be near: only the outing's own window can keep a waypoint.
    @Test
    fun emptyTrackFallsBackToTheWindow()  {
        val during = waypoint(12, 41.2000, 29.9000, 4000L)
        val before = waypoint(13, 41.2000, 29.9000, 10L)
        assertEquals(
            listOf(during),
            WaypointScope.forTrack(listOf(during, before), emptyList(), 1000L, 9000L)
        )
    }
}

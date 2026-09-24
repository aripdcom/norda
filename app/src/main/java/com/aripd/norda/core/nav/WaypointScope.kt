package com.aripd.norda.core.nav

import com.aripd.norda.core.geo.Geo
import com.aripd.norda.core.track.TrackPoint

/**
 * Which of the saved waypoints belong in one outing's GPX (field item F-18).
 *
 * Waypoints are global in Norda — they are not owned by an activity — and the
 * export used to write every single one into the file. The Sept 24 evening
 * walk proved what that costs: its file carried a waypoint 6 km from the
 * route, saved on another day. Two things are wrong with that. A GPX is read
 * as the record of one outing, so an unrelated marker misleads every tool that
 * opens it; and a file sent to someone else then carries every place the owner
 * has ever marked, which for an app that promises verifiable privacy is a leak,
 * not an inconvenience.
 *
 * A waypoint belongs to the outing when it was **saved during the recording**
 * — whatever it marks, this walk produced it — or when the walk **went past
 * it**, within [NEAR_ROUTE_M] of some recorded point. Everything else is the
 * owner's own collection and stays on the phone. Keeping a full backup of the
 * waypoints is a different job from exporting a track, and it is not this one.
 */
object WaypointScope {

    /**
     * How close counts as "went past it". 250 m is wide enough for a marker
     * set beside the path and for the GPS error on both the point and the
     * route, and narrow enough that the next street over is not swept in.
     */
    const val NEAR_ROUTE_M = 250.0

    /**
     * [startMillis] and [endMillis] are the recording's own bounds, not the
     * first and last accepted point: a waypoint saved while GPS was still
     * settling belongs to the outing too.
     */
    fun forTrack(
        waypoints: List<Waypoint>,
        track: List<TrackPoint>,
        startMillis: Long,
        endMillis: Long,
        nearRouteM: Double = NEAR_ROUTE_M
    ): List<Waypoint> = waypoints.filter { w ->
        w.createdAtMillis in startMillis..endMillis || track.any { p ->
            Geo.distanceMeters(p.latitude, p.longitude, w.latitude, w.longitude) <= nearRouteM
        }
    }
}

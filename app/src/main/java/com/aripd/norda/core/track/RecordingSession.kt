package com.aripd.norda.core.track

import com.aripd.norda.core.geo.Geo

/**
 * Recording state machine: filter, auto-pause, stopwatch, distance and
 * elevation under one roof. Pure Kotlin — the Android side only forwards
 * fixes and persists the accepted points.
 *
 * Two separate time bases, deliberately: stopwatch operations are called with
 * the monotonic clock (the user can change the wall clock), while auto-pause
 * decides using the fixes' own timestamps.
 */
class RecordingSession(
    val type: ActivityType,
    val startWallMillis: Long,
    startMonotonicMillis: Long,
    private val autoPause: AutoPauseDetector = AutoPauseDetector(),
    private val elevation: ElevationTracker = ElevationTracker(),
    // Y-3: the accumulator is fed a ±5 s median, not raw altitudes. The live
    // figure therefore trails the walk by about five seconds, and the tail is
    // booked when the summary is taken.
    private val altitudes: AltitudeSmoother = AltitudeSmoother()
) {

    enum class State { RECORDING, PAUSED, AUTO_PAUSED, STOPPED }

    var state = State.RECORDING
        private set

    private val stopwatch = PauseAwareStopwatch().apply { start(startMonotonicMillis) }
    private val recorded = mutableListOf<TrackPoint>()
    val points: List<TrackPoint> get() = recorded

    var distanceM = 0.0
        private set
    val elevationGainM get() = elevation.gainM
    val elevationLossM get() = elevation.lossM

    // After a manual pause: the path in between does not count towards the
    // distance (the user deliberately excluded that segment) and the detector
    // is reset with the new location.
    private var breakSegment = false
    private var pendingDetectorReset = false

    // Raw data for filter calibration (Phase 8): a counter per verdict. Only
    // fixes evaluated in the RECORDING state are counted — those arriving
    // during a pause are not calibration data. The verdict on the tentative
    // fix at the settling gate is finalized by the second fix; a tentative
    // that never gets finalized is not counted.
    private val filterCounts = IntArray(GpsFilter.Verdict.entries.size)

    fun filterCount(verdict: GpsFilter.Verdict): Int = filterCounts[verdict.ordinal]

    fun evaluatedFixCount(): Int = filterCounts.sum()

    /**
     * A point entering the recording in this call + the persistence flag (the
     * caller writes it to the DB).
     */
    data class Accepted(
        val point: TrackPoint,
        val hasAltitude: Boolean,
        /**
         * This point opens a new leg: the ground between it and the previous
         * point was covered while the recording was paused and is not distance
         * (F-17). Persisted, so the file can say where the pause was instead
         * of leaving a gap that looks like a GPS outage.
         */
        val afterPause: Boolean = false
    )

    // Settling gate (F-11): on two outings the first fix came in 13–26 m off,
    // and because it was made the anchor, the phantom distance — even with the
    // jumping point rejected — was still counted with the next point. That is
    // why the first fix is not an anchor but TENTATIVE: it does not enter the
    // recording until the second fix confirms physical consistency; if a
    // teleport shows up, the culprit is the first fix — the tentative is
    // replaced.
    private var tentative: Accepted? = null

    // The time until GPS settles must be visible on screen (F-4): while no
    // point has entered the recording, the latest/best accuracy is read from
    // here. 0 = the device reports no accuracy; it does not count as quality.
    var latestAccuracyM: Float? = null
        private set
    var bestAccuracyM: Float? = null
        private set

    /**
     * Processes a raw fix; returns the points that ENTER the recording in this
     * call (the caller persists them): none, a single point, or — when the
     * tentative is confirmed by the second fix — the tentative + the fix
     * together. [hasAltitude] must be true only for valid altitudes.
     */
    fun onFix(fix: TrackPoint, hasAltitude: Boolean, nowMonotonicMillis: Long): List<Accepted> {
        if (state == State.STOPPED || state == State.PAUSED) return emptyList()

        if (pendingDetectorReset) {
            autoPause.reset(fix)
            pendingDetectorReset = false
        }

        // The gate operates only while the recording is empty; on recovery
        // (prime) the inherited last point is placed into the recording, so the
        // settling test is not repeated there.
        val gate = recorded.isEmpty()
        val previous = if (gate) tentative?.point else recorded.lastOrNull()
        // accepted: the flag the auto-pause detector sees (a tentative is also
        // quality movement); commitFix: the fix itself entering the recording
        // in this call.
        var accepted = false
        var commitFix = false
        var confirmTentative = false
        if (state == State.RECORDING) {
            val verdict = GpsFilter.evaluate(previous, fix)
            if (!gate) {
                filterCounts[verdict.ordinal]++
                accepted = verdict == GpsFilter.Verdict.ACCEPT
                commitFix = accepted
            } else when (verdict) {
                GpsFilter.Verdict.ACCEPT -> {
                    accepted = true
                    if (previous == null) {
                        // First quality fix: becomes the tentative; the accept
                        // count and the recording are finalized by the second fix.
                        tentative = Accepted(fix, hasAltitude)
                    } else {
                        confirmTentative = true
                        commitFix = true
                    }
                }
                GpsFilter.Verdict.JITTER -> {
                    // Jitter near the tentative: the pair is consistent → the
                    // tentative is confirmed, the fix itself is rejected as
                    // jitter as usual.
                    filterCounts[verdict.ordinal]++
                    confirmTentative = true
                }
                GpsFilter.Verdict.TELEPORT -> {
                    // The pair is physically inconsistent; per field evidence
                    // the outlier is the FIRST fix. The counter records the
                    // dropped tentative.
                    filterCounts[verdict.ordinal]++
                    tentative = Accepted(fix, hasAltitude)
                }
                else -> filterCounts[verdict.ordinal]++
            }
            if (fix.accuracyM > 0f) {
                latestAccuracyM = fix.accuracyM
                val best = bestAccuracyM
                if (best == null || fix.accuracyM < best) bestAccuracyM = fix.accuracyM
            }
        }

        when (autoPause.onFix(fix, accepted)) {
            AutoPauseDetector.Decision.PAUSE -> {
                state = State.AUTO_PAUSED
                stopwatch.pause(nowMonotonicMillis)
                return if (confirmTentative) listOf(commitTentative()) else emptyList()
            }
            AutoPauseDetector.Decision.RESUME -> {
                state = State.RECORDING
                stopwatch.resume(nowMonotonicMillis)
                return if (confirmTentative) listOf(commitTentative()) else emptyList()
            }
            AutoPauseDetector.Decision.NONE -> Unit
        }

        if (state != State.RECORDING) return emptyList()

        val out = mutableListOf<Accepted>()
        if (confirmTentative) out += commitTentative()
        if (!commitFix) return out

        val prev = recorded.lastOrNull()
        val opensNewLeg = prev != null && breakSegment
        if (prev != null && !breakSegment) {
            distanceM += Geo.distanceMeters(
                prev.latitude, prev.longitude, fix.latitude, fix.longitude
            )
        }
        breakSegment = false
        // An accept outside the gate was counted above; one at the gate is
        // finalized here.
        if (gate) filterCounts[GpsFilter.Verdict.ACCEPT.ordinal]++
        recorded += fix
        if (hasAltitude) bookAltitude(fix.timeMillis, fix.altitude)
        out += Accepted(fix, hasAltitude, afterPause = opensNewLeg)
        return out
    }

    private fun bookAltitude(timeMillis: Long, altitudeM: Double) {
        altitudes.onAltitude(timeMillis, altitudeM).forEach(elevation::onAltitude)
    }

    private fun commitTentative(): Accepted {
        val t = tentative!!
        tentative = null
        filterCounts[GpsFilter.Verdict.ACCEPT.ordinal]++
        recorded += t.point
        if (t.hasAltitude) bookAltitude(t.point.timeMillis, t.point.altitude)
        return t
    }

    /**
     * Recovery after process death: the state inherited from the unfinished
     * recording on disk. Called only right after construction, before the
     * first fix. Distance and duration are inherited; elevation is recomputed
     * with the same hysteresis by feeding the stored altitudes in order; the
     * last point is handed to the filter as "previous".
     */
    fun prime(
        recoveredDistanceM: Double,
        recoveredDurationMillis: Long,
        lastPoint: TrackPoint?,
        recoveredAltitudes: List<Pair<Long, Double>>
    ) {
        distanceM = recoveredDistanceM
        stopwatch.prime(recoveredDurationMillis)
        lastPoint?.let { recorded += it }
        recoveredAltitudes.forEach { (timeMillis, altitudeM) ->
            bookAltitude(timeMillis, altitudeM)
        }
    }

    fun pauseManual(nowMonotonicMillis: Long) {
        if (state == State.RECORDING || state == State.AUTO_PAUSED) {
            state = State.PAUSED
            stopwatch.pause(nowMonotonicMillis)
        }
    }

    fun resumeManual(nowMonotonicMillis: Long) {
        if (state == State.PAUSED) {
            state = State.RECORDING
            stopwatch.resume(nowMonotonicMillis)
            breakSegment = true
            pendingDetectorReset = true
        }
    }

    fun stop(nowMonotonicMillis: Long) {
        if (state == State.STOPPED) return
        stopwatch.pause(nowMonotonicMillis)
        state = State.STOPPED
    }

    fun durationMillis(nowMonotonicMillis: Long): Long =
        stopwatch.elapsedMillis(nowMonotonicMillis)

    fun currentPaceSecPerKm(): Double? = Stats.currentPaceSecPerKm(recorded)

    /**
     * The closing figures. Taking them books whatever the smoother still
     * holds (Y-3): the last few seconds of a walk are part of it.
     */
    fun summary(id: Long, endWallMillis: Long, nowMonotonicMillis: Long): ActivitySummary {
        altitudes.flush().forEach(elevation::onAltitude)
        return ActivitySummary(
            id = id,
            type = type,
            startTimeMillis = startWallMillis,
            endTimeMillis = endWallMillis,
            distanceM = distanceM,
            durationMillis = durationMillis(nowMonotonicMillis),
            elevationGainM = elevationGainM,
            elevationLossM = elevationLossM
        )
    }
}

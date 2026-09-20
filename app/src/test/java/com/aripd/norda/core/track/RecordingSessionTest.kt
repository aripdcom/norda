package com.aripd.norda.core.track

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end test of the state machine: a synthetic walk is fed in, and
 * distance/duration/elevation and the state transitions are verified. In the
 * tests the monotonic clock and the fix time have the same value (for
 * simplicity).
 *
 * onFix returns the points that ENTER the recording on this call (0/1/2
 * elements): because of the settling gate (F-11) the first fix waits as a
 * tentative point, and once the second fix confirms it both may be returned
 * together.
 */
class RecordingSessionTest {

    private val step10m = 0.00008993

    private fun fix(t: Long, latSteps: Double, alt: Double = 0.0, acc: Float = 10f) =
        TrackPoint(t, 41.0 + latSteps * step10m, 29.0, alt, acc, 0f, 0f)

    private fun session() = RecordingSession(
        type = ActivityType.WALK,
        startWallMillis = 1_000_000,
        startMonotonicMillis = 0
    )

    @Test
    fun filterCountsFeedCalibration() {
        val s = session()
        s.onFix(fix(0, 0.0), false, 0)                          // tentative
        s.onFix(fix(5_000, 1.0), false, 5_000)                  // confirms: 2 accepted
        s.onFix(fix(10_000, 1.05), false, 10_000)               // ~0.5 m jitter
        s.onFix(fix(15_000, 1.0, acc = 99f), false, 15_000)     // poor accuracy
        assertEquals(2, s.filterCount(GpsFilter.Verdict.ACCEPT))
        assertEquals(1, s.filterCount(GpsFilter.Verdict.JITTER))
        assertEquals(1, s.filterCount(GpsFilter.Verdict.BAD_ACCURACY))
        assertEquals(0, s.filterCount(GpsFilter.Verdict.TELEPORT))
        assertEquals(0, s.filterCount(GpsFilter.Verdict.NON_MONOTONIC))
    }

    // F-4 (tennis-court trial): the time spent before the GPS settles must be
    // visible on screen — the latest/best accuracy is observable while no
    // point has entered the recording yet.
    @Test
    fun gpsQualityIsObservableBeforeFirstAccept() {
        val s = session()
        assertEquals(null, s.latestAccuracyM)
        assertEquals(null, s.bestAccuracyM)
        assertEquals(0, s.evaluatedFixCount())
        s.onFix(fix(0, 0.0, acc = 48f), false, 0)          // poor accuracy
        s.onFix(fix(1_000, 0.0, acc = 35f), false, 1_000)  // still poor
        assertEquals(35f, s.latestAccuracyM)
        assertEquals(35f, s.bestAccuracyM)
        assertEquals(2, s.evaluatedFixCount())
        assertEquals(0, s.points.size)
    }

    @Test
    fun unknownAccuracyDoesNotPolluteGpsQuality() {
        val s = session()
        s.onFix(fix(0, 0.0, acc = 0f), false, 0)                // accuracy unknown → tentative
        s.onFix(fix(5_000, 1.0, acc = 0f), false, 5_000)        // confirms
        assertEquals(null, s.bestAccuracyM)
        assertEquals(null, s.latestAccuracyM)
        assertEquals(2, s.evaluatedFixCount())
        assertEquals(2, s.points.size)
    }

    @Test
    fun manualPauseDoesNotPolluteFilterCounts() {
        val s = session()
        s.onFix(fix(0, 0.0), false, 0)
        s.onFix(fix(2_000, 1.0), false, 2_000)   // tentative + this fix recorded
        s.pauseManual(3_000)
        s.onFix(fix(5_000, 2.0), false, 5_000)   // fix during pause is not counted
        assertEquals(2, s.filterCount(GpsFilter.Verdict.ACCEPT))
    }

    @Test
    fun recordsAcceptedFixesAndAccumulatesDistance() {
        val s = session()
        assertEquals(0, s.onFix(fix(0, 0.0), hasAltitude = false, nowMonotonicMillis = 0).size)
        assertEquals(2, s.onFix(fix(5_000, 1.0), false, 5_000).size)  // tentative + itself
        assertEquals(1, s.onFix(fix(10_000, 2.0), false, 10_000).size)
        assertEquals(20.0, s.distanceM, 0.5)
        assertEquals(10_000, s.durationMillis(10_000))
        assertEquals(3, s.points.size)
    }

    @Test
    fun rejectedJitterAddsNothing() {
        val s = session()
        s.onFix(fix(0, 0.0), false, 0)
        // ~0.5 m jitter: confirms the tentative point (close = consistent) but
        // does not enter itself
        val r = s.onFix(fix(5_000, 0.05), false, 5_000)
        assertEquals(1, r.size)
        assertEquals(41.0, r[0].point.latitude, 1e-12)
        assertEquals(0.0, s.distanceM, 1e-9)
        assertEquals(1, s.points.size)
    }

    // F-11 (Tour 2 (repeat) + night tour, two field proofs): during "settling"
    // the first fix can arrive 13–26 m off; if it is made the anchor, the
    // phantom distance still gets counted with a LATER point even when the
    // jumping point is rejected. That is why the first fix is TENTATIVE, not
    // an anchor: it does not enter the recording until the second fix confirms
    // physical consistency; if a teleport shows up the culprit is the first
    // fix — the tentative point is replaced and the phantom distance is never
    // born.
    @Test
    fun settlingFirstFixIsReplacedNotAnchored() {
        val s = session()
        assertEquals(0, s.onFix(fix(0, 0.0), false, 0).size)          // tentative
        // field data: ~25.7 m in 2 s = 12.85 m/s — settling spike
        assertEquals(0, s.onFix(fix(2_000, 2.57), false, 2_000).size) // tentative replaced
        assertEquals(1, s.filterCount(GpsFilter.Verdict.TELEPORT))
        assertEquals(0, s.points.size)
        // a third fix near the new tentative point confirms it
        val confirmed = s.onFix(fix(4_000, 2.62), false, 4_000)
        assertEquals(1, confirmed.size)
        assertEquals(41.0 + 2.57 * step10m, confirmed[0].point.latitude, 1e-12)
        assertEquals(0.0, s.distanceM, 1e-9)                          // no phantom distance
        assertEquals(1, s.points.size)
        // normal flow continues
        assertEquals(1, s.onFix(fix(9_000, 3.57), false, 9_000).size)
        assertEquals(10.0, s.distanceM, 0.5)
        assertEquals(2, s.filterCount(GpsFilter.Verdict.ACCEPT))
        assertEquals(1, s.filterCount(GpsFilter.Verdict.JITTER))
    }

    // A tentative point that never gets confirmed does not enter the
    // recording: a single-fix "recording" is not data.
    @Test
    fun unresolvedTentativeFixNeverEntersTrack() {
        val s = session()
        assertEquals(0, s.onFix(fix(0, 0.0), false, 0).size)
        s.stop(1_000)
        assertEquals(0, s.points.size)
        assertEquals(0, s.filterCount(GpsFilter.Verdict.ACCEPT))
    }

    @Test
    fun autoPausesAndFreezesDuration() {
        val s = session()
        s.onFix(fix(0, 0.0), false, 0)
        // standing in the same place: fixes that are not accepted
        s.onFix(fix(10_000, 0.0), false, 10_000)
        s.onFix(fix(20_000, 0.0), false, 20_000)
        assertEquals(0, s.onFix(fix(21_000, 0.0), false, 21_000).size)
        assertEquals(RecordingSession.State.AUTO_PAUSED, s.state)
        // the duration freezes at the moment of the pause
        assertEquals(21_000, s.durationMillis(60_000))
    }

    @Test
    fun autoResumesOnRealMovement() {
        val s = session()
        s.onFix(fix(0, 0.0), false, 0)
        s.onFix(fix(21_000, 0.0), false, 21_000)              // AUTO_PAUSED
        s.onFix(fix(30_000, 1.0), false, 30_000)              // 10 m → RESUME
        assertEquals(RecordingSession.State.RECORDING, s.state)
        // an accepted point after the resume is added to the distance as usual
        assertTrue(s.onFix(fix(35_000, 2.0), false, 35_000).isNotEmpty())
        assertEquals(20.0, s.distanceM, 0.5)
        // the 9 s spent paused do not count toward the duration: 21 + 5
        assertEquals(26_000, s.durationMillis(35_000))
    }

    @Test
    fun manualPauseBlocksEverything() {
        val s = session()
        s.onFix(fix(0, 0.0), false, 0)
        s.pauseManual(5_000)
        assertEquals(RecordingSession.State.PAUSED, s.state)
        assertEquals(0, s.onFix(fix(10_000, 5.0), false, 10_000).size)
        assertEquals(0.0, s.distanceM, 1e-9)
        assertEquals(5_000, s.durationMillis(60_000))
    }

    // Ground covered during a manual pause is the part the user deliberately
    // excluded: the first point after the resume is recorded, but the distance
    // in between is not counted.
    @Test
    fun manualResumeSkipsGapDistance() {
        val s = session()
        s.onFix(fix(0, 0.0), false, 0)
        s.pauseManual(5_000)
        s.resumeManual(50_000)
        // tentative + resume fix enter together; the gap distance is not counted
        assertEquals(2, s.onFix(fix(55_000, 10.0), false, 55_000).size)
        assertEquals(0.0, s.distanceM, 1e-9)
        assertEquals(1, s.onFix(fix(60_000, 11.0), false, 60_000).size)
        assertEquals(10.0, s.distanceM, 0.5)
    }

    /**
     * F-17: the caller has to be able to persist WHERE the pause was, or the
     * file cannot tell a pause from a GPS outage afterwards and every
     * recomputation re-counts the leg the session deliberately skipped.
     */
    @Test
    fun theFirstPointAfterAResumeIsMarkedAsSuch() {
        val s = session()
        s.onFix(fix(0, 0.0), false, 0)
        s.pauseManual(5_000)
        s.resumeManual(50_000)
        val resumed = s.onFix(fix(55_000, 10.0), false, 55_000)
        assertEquals(2, resumed.size)
        // The tentative that finally entered belongs to the first leg.
        assertFalse("the confirmed tentative is not after a pause", resumed[0].afterPause)
        assertTrue("the resume fix opens a new leg", resumed[1].afterPause)
        val next = s.onFix(fix(60_000, 11.0), false, 60_000)
        assertEquals(1, next.size)
        assertFalse("only the first point after a resume is marked", next[0].afterPause)
    }

    @Test
    fun pointsOfAnUninterruptedRecordingAreNeverMarked() {
        val s = session()
        s.onFix(fix(0, 0.0), false, 0)
        // 5 m in 5 s: past the jitter gate, well under the speed cap.
        val second = s.onFix(fix(5_000, 0.5), false, 5_000)
        assertEquals(2, second.size)
        assertFalse(second[0].afterPause)
        assertFalse(second[1].afterPause)
    }

    @Test
    fun stopFreezesAndSummarises() {
        val s = session()
        s.onFix(fix(0, 0.0, alt = 100.0), true, 0)
        s.onFix(fix(5_000, 1.0, alt = 105.0), true, 5_000)
        s.stop(6_000)
        assertEquals(0, s.onFix(fix(10_000, 2.0), false, 10_000).size)
        val sum = s.summary(id = 7, endWallMillis = 1_006_000, nowMonotonicMillis = 60_000)
        assertEquals(ActivityType.WALK, sum.type)
        assertEquals(1_000_000, sum.startTimeMillis)
        assertEquals(1_006_000, sum.endTimeMillis)
        assertEquals(10.0, sum.distanceM, 0.5)
        assertEquals(6_000, sum.durationMillis)
        assertEquals(5.0, sum.elevationGainM, 1e-9)
        assertEquals(0.0, sum.elevationLossM, 1e-9)
    }

    // The persistence flag belongs to the point: when a tentative point with a
    // valid altitude and an altitude-less confirming fix are returned in the
    // same call, the flags must not get mixed up.
    @Test
    fun committedPointsCarryTheirOwnAltitudeFlag() {
        val s = session()
        s.onFix(fix(0, 0.0, alt = 100.0), true, 0)
        val pair = s.onFix(fix(5_000, 1.0, alt = 0.0), false, 5_000)
        assertEquals(2, pair.size)
        assertTrue(pair[0].hasAltitude)
        assertTrue(!pair[1].hasAltitude)
        assertEquals(0.0, s.elevationGainM, 1e-9)
    }

    // When the service is killed and restarted, the recording is taken over
    // from disk: distance/duration are preserved, a new fix is measured from
    // the old last point, and elevation is rebuilt from the stored altitudes
    // with the same hysteresis. Since the recovered last point is already in
    // the recording, the settling gate does not apply.
    @Test
    fun primeRestoresRecoveredState() {
        val s = session()
        s.prime(
            recoveredDistanceM = 500.0,
            recoveredDurationMillis = 600_000,
            lastPoint = fix(0, 0.0, alt = 100.0),
            // 30 s apart: too sparse for the median to have a window, so the
            // stored altitudes pass through untouched (Y-3).
            recoveredAltitudes = listOf(0L to 100.0, 30_000L to 110.0, 60_000L to 105.0)
        )
        assertEquals(500.0, s.distanceM, 1e-9)
        assertEquals(610_000, s.durationMillis(10_000))
        assertEquals(1, s.onFix(fix(5_000, 1.0), false, 5_000).size)
        assertEquals(510.0, s.distanceM, 0.5)
        // Elevation is complete once the smoother's tail is booked, which is
        // what taking the summary does.
        val recovered = s.summary(id = 7, endWallMillis = 1_006_000, nowMonotonicMillis = 10_000)
        assertEquals(10.0, recovered.elevationGainM, 1e-9)
        assertEquals(5.0, recovered.elevationLossM, 1e-9)
    }

    // A fix that reports no altitude does not enter the elevation calculation
    // — the 0.0 sentinel value would have produced a phantom descent.
    @Test
    fun invalidAltitudeIsIgnored() {
        val s = session()
        s.onFix(fix(0, 0.0, alt = 100.0), true, 0)
        s.onFix(fix(5_000, 1.0, alt = 0.0), false, 5_000)     // hasAltitude=false
        s.onFix(fix(10_000, 2.0, alt = 110.0), true, 10_000)
        val sum = s.summary(id = 1, endWallMillis = 1_010_000, nowMonotonicMillis = 10_000)
        assertEquals(10.0, sum.elevationGainM, 1e-9)
        assertEquals(0.0, sum.elevationLossM, 1e-9)
    }
}

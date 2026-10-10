package com.aripd.norda.core.track

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Y-3: gain and loss were inflated, because the 4 m hysteresis counts the
 * oscillation that GNSS vertical noise rides on top of a real climb. The
 * accumulator stays as it is; what changes is what goes into it — a median
 * over a window of time, which kills spikes and leaves slopes.
 *
 * The mechanism tests below pass the window explicitly, so they keep saying
 * what they mean whatever the shipped default is; the last three pin the
 * default itself, which is a field-calibrated number (Oct 10, against a
 * companion's DEM-corrected track over 7.19 km of shared ground).
 */
class AltitudeSmootherTest {

    private fun feed(s: AltitudeSmoother, samples: List<Pair<Long, Double>>): List<Double> {
        val out = ArrayList<Double>()
        for ((t, a) in samples) out += s.onAltitude(t, a)
        out += s.flush()
        return out
    }

    private fun steady(count: Int, stepMillis: Long, value: (Int) -> Double) =
        (0 until count).map { it * stepMillis to value(it) }

    @Test
    fun `every sample comes out exactly once and in order`() {
        val s = AltitudeSmoother(5_000L)
        val samples = steady(10, 1_000L) { 100.0 + it }
        val out = feed(s, samples)
        assertEquals(10, out.size)
        // A monotone input stays monotone through a median.
        assertTrue(out.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test
    fun `a single spike is removed`() {
        val s = AltitudeSmoother(5_000L)
        val samples = steady(11, 1_000L) { if (it == 5) 140.0 else 100.0 }
        val out = feed(s, samples)
        assertEquals(11, out.size)
        assertTrue("the spike survived: $out", out.all { it == 100.0 })
    }

    @Test
    fun `a real step survives`() {
        val s = AltitudeSmoother(5_000L)
        val samples = steady(20, 1_000L) { if (it < 10) 100.0 else 120.0 }
        val out = feed(s, samples)
        assertEquals(20, out.size)
        assertEquals(100.0, out.first(), 1e-9)
        assertEquals(120.0, out.last(), 1e-9)
        val e = ElevationTracker()
        out.forEach { e.onAltitude(it) }
        assertEquals(20.0, e.gainM, 1e-9)
        assertEquals(0.0, e.lossM, 1e-9)
    }

    @Test
    fun `a cadence sparser than the window passes through untouched`() {
        // 30 s apart against a ±5 s window: no window ever holds three
        // samples, so nothing is smoothed — the alternative would erase a
        // whole outing.
        val s = AltitudeSmoother(5_000L)
        val samples = steady(6, 30_000L) { 100.0 + it * 6.0 }
        val out = feed(s, samples)
        assertEquals(samples.map { it.second }, out)
    }

    @Test
    fun `a sample waits for its window before it is emitted`() {
        val s = AltitudeSmoother(5_000L)
        assertTrue(s.onAltitude(0L, 100.0).isEmpty())
        assertTrue(s.onAltitude(1_000L, 101.0).isEmpty())
        assertTrue(s.onAltitude(4_000L, 102.0).isEmpty())
        // Now the first sample's right half is complete.
        assertEquals(1, s.onAltitude(5_000L, 103.0).size)
        assertEquals(3, s.flush().size)
    }

    @Test
    fun `oscillation on a climb stops counting as extra gain`() {
        // A real 20 m climb with ±6 m noise riding on it: the raw series books
        // far more than the climb, the smoothed one books the climb.
        val samples = steady(40, 1_000L) { i ->
            val climb = 100.0 + i * 0.5
            val noise = when (i % 4) { 0 -> 6.0; 2 -> -6.0; else -> 0.0 }
            climb + noise
        }
        val raw = ElevationTracker()
        samples.forEach { raw.onAltitude(it.second) }
        val smoothed = ElevationTracker()
        feed(AltitudeSmoother(5_000L), samples).forEach { smoothed.onAltitude(it) }
        // The terrain holds 19.5 m. Raw books six times that; smoothed books
        // less than the climb rather than more, because the hysteresis leaves
        // the last sub-threshold remainder unbooked — an error in the
        // conservative direction, which is the one to have here.
        assertTrue("raw should over-count: ${raw.gainM}", raw.gainM > 100.0)
        assertTrue("smoothed should sit just under the real climb: ${smoothed.gainM}",
            smoothed.gainM in 10.0..19.5)
    }

    // ---- the shipped window ------------------------------------------------

    @Test
    fun `the shipped window is half a minute each way`() {
        val s = AltitudeSmoother()
        assertTrue(s.onAltitude(0L, 100.0).isEmpty())
        assertTrue(s.onAltitude(29_000L, 101.0).isEmpty())
        assertEquals(1, s.onAltitude(30_000L, 102.0).size)
    }

    @Test
    fun `at the shipped window a real climb survives and its noise does not`() {
        // Five minutes of climbing at 0.1 m/s is 30 m of terrain, with ±6 m of
        // 1 Hz noise on top — the shape the field tours actually carry.
        val samples = steady(301, 1_000L) { i ->
            val climb = 100.0 + i * 0.1
            val noise = when (i % 4) { 0 -> 6.0; 2 -> -6.0; else -> 0.0 }
            climb + noise
        }
        val raw = ElevationTracker()
        samples.forEach { raw.onAltitude(it.second) }
        val smoothed = ElevationTracker()
        feed(AltitudeSmoother(), samples).forEach { smoothed.onAltitude(it) }
        // Measured: raw books 915 m for 30 m of ground; the half-minute window
        // books 24 m. It reads under rather than over, because the window's
        // ends pull towards the middle and the hysteresis leaves the last
        // remainder unbooked — both errors in the conservative direction.
        assertTrue("raw should over-count wildly: ${raw.gainM}", raw.gainM > 500.0)
        // The band is narrow enough to notice a window change in either
        // direction: ±5 s books 28.1 m of the same climb, ±30 s books 24.0.
        assertTrue("the climb should survive, and the window should be the wide one: " +
            "${smoothed.gainM}", smoothed.gainM in 20.0..26.0)
    }

    @Test
    fun `at the shipped window the battery-saver cadence still passes through`() {
        // The sparse tour left 61 points over 33 minutes, about one every 32 s
        // — just outside a ±30 s window, so no window holds three samples and
        // nothing is smoothed. A spike proves the rule rather than the data:
        // a linear ramp would come back unchanged either way.
        val s = AltitudeSmoother()
        val samples = steady(6, 32_000L) { if (it == 3) 160.0 else 100.0 + it * 6.0 }
        assertEquals(samples.map { it.second }, feed(s, samples))
    }
}

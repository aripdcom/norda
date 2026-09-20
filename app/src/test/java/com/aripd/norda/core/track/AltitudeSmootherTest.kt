package com.aripd.norda.core.track

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Y-3: gain and loss were inflated and unstable, because the 4 m hysteresis
 * counts the ±10 m oscillation that GNSS vertical noise rides on top of a
 * real climb. The accumulator stays as it is; what changes is what goes into
 * it — a median over a ±5 s window, which kills spikes and leaves slopes.
 *
 * The window has to be bounded in TIME, not in samples: on a sparse recording
 * (battery saver, 61 points over 33 minutes) a nine-sample window spans ten
 * minutes and flattens the outing to nothing.
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
        val s = AltitudeSmoother()
        val samples = steady(10, 1_000L) { 100.0 + it }
        val out = feed(s, samples)
        assertEquals(10, out.size)
        // A monotone input stays monotone through a median.
        assertTrue(out.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test
    fun `a single spike is removed`() {
        val s = AltitudeSmoother()
        val samples = steady(11, 1_000L) { if (it == 5) 140.0 else 100.0 }
        val out = feed(s, samples)
        assertEquals(11, out.size)
        assertTrue("the spike survived: $out", out.all { it == 100.0 })
    }

    @Test
    fun `a real step survives`() {
        val s = AltitudeSmoother()
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
    fun `sparse samples pass through untouched`() {
        // 30 s apart: no window ever holds three samples, so nothing is
        // smoothed — the alternative would erase a whole outing.
        val s = AltitudeSmoother()
        val samples = steady(6, 30_000L) { 100.0 + it * 6.0 }
        val out = feed(s, samples)
        assertEquals(samples.map { it.second }, out)
    }

    @Test
    fun `a sample waits for its window before it is emitted`() {
        val s = AltitudeSmoother()
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
        feed(AltitudeSmoother(), samples).forEach { smoothed.onAltitude(it) }
        // The terrain holds 19.5 m. Raw books six times that; smoothed books
        // less than the climb rather than more, because the hysteresis leaves
        // the last sub-threshold remainder unbooked — an error in the
        // conservative direction, which is the one to have here.
        assertTrue("raw should over-count: ${raw.gainM}", raw.gainM > 100.0)
        assertTrue("smoothed should sit just under the real climb: ${smoothed.gainM}",
            smoothed.gainM in 10.0..19.5)
    }
}

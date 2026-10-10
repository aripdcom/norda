package com.aripd.norda.core.track

/**
 * A streaming median over a ±window of time, fed to `ElevationTracker`
 * (docs/MVP.md, 5.4; field item Y-3).
 *
 * The hysteresis accumulator counts a rise the moment it separates from the
 * anchor by the threshold, and GNSS vertical noise separates by that much
 * regularly: on the field tours it booked about a quarter more climb than the
 * terrain held, and perturbing the same series by a centimetre moved the
 * total by tens of metres, because a perturbation flips which step crosses
 * the threshold. A median removes the spikes that cause both and leaves the
 * slope, because the middle value of a window is not moved by its outliers.
 *
 * **The half minute is measured, not chosen.** The first version of this
 * filter used ±5 s, which removes spikes but not the slow wander of the
 * vertical error, and nothing in a one-way recording could say how wide the
 * window should be: every criterion available from the track alone rewards
 * more smoothing. The Oct 10 night walk settled it, because a companion's
 * DEM-corrected track covered 7.19 km of the same ground in nine stretches.
 * Over it ±5 s booked 2.21× the DEM's own rise; a grid over twelve
 * window-and-threshold pairs put the error per stretch at its lowest — 5.6 m
 * against 17.1 — at ±30 s with the threshold left alone, and every wider
 * window or higher threshold began undershooting the stretches that really
 * climb. The remaining 1.32× is where to stop: a DEM has no kerbs, stairs or
 * overpasses in it, so the truth is above the reference and below us.
 *
 * Two decisions are worth their lines. The window is bounded in **time**, not
 * in samples: at the usual ~1 Hz cadence a nine-sample window is nine
 * seconds, but on a sparse recording — the battery-saver tour left 61 points
 * over 33 minutes, one every 32 s — the same nine samples span ten minutes
 * and flatten the outing to nothing. That cadence falls outside this window
 * entirely, so such a recording passes through untouched. And where a window
 * holds fewer than [minSamples], the value passes through untouched: with
 * nothing to compare against, a median of one or two samples is not a filter,
 * it is a coin toss.
 *
 * Because the window is centred, a sample can only be emitted once samples
 * newer by half a window have arrived. So the live figure trails the walk by
 * about half a minute, and [flush] books the tail when the recording ends —
 * which is why a finished activity is never short of its last stretch.
 */
class AltitudeSmoother(
    private val halfWindowMillis: Long = 30_000L,
    private val minSamples: Int = 3
) {

    private val times = ArrayList<Long>()
    private val values = ArrayList<Double>()

    /** Index into the buffers of the next sample whose turn it is to be emitted. */
    private var pending = 0

    /**
     * Takes one altitude and returns the samples whose window is now complete,
     * in order — usually one, sometimes none, never out of sequence.
     */
    fun onAltitude(timeMillis: Long, altitudeM: Double): List<Double> {
        times += timeMillis
        values += altitudeM
        val out = ArrayList<Double>()
        while (pending < times.size && timeMillis - times[pending] >= halfWindowMillis) {
            out += medianAt(pending)
            pending++
        }
        compact()
        return out
    }

    /** Books whatever is still buffered; their windows stay half-open. */
    fun flush(): List<Double> {
        val out = ArrayList<Double>()
        while (pending < times.size) {
            out += medianAt(pending)
            pending++
        }
        times.clear()
        values.clear()
        pending = 0
        return out
    }

    private fun medianAt(index: Int): Double {
        val centre = times[index]
        val window = ArrayList<Double>()
        var i = index
        while (i >= 0 && centre - times[i] <= halfWindowMillis) {
            window += values[i]
            i--
        }
        i = index + 1
        while (i < times.size && times[i] - centre <= halfWindowMillis) {
            window += values[i]
            i++
        }
        if (window.size < minSamples) return values[index]
        window.sort()
        val middle = window.size / 2
        return if (window.size % 2 == 1) window[middle]
        else (window[middle - 1] + window[middle]) / 2.0
    }

    /** Drops what no future window can reach, so memory does not grow with the outing. */
    private fun compact() {
        if (pending == 0) return
        val oldestNeeded = times[pending] - halfWindowMillis
        var drop = 0
        while (drop < pending && times[drop] < oldestNeeded) drop++
        if (drop == 0) return
        times.subList(0, drop).clear()
        values.subList(0, drop).clear()
        pending -= drop
    }
}

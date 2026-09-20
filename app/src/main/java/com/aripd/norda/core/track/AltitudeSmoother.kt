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
 * Two decisions are worth their lines. The window is bounded in **time**, not
 * in samples: at the usual ~1 Hz cadence a nine-sample window is nine
 * seconds, but on a sparse recording — the battery-saver tour left 61 points
 * over 33 minutes — the same nine samples span ten minutes and flatten the
 * outing to nothing. And where a window holds fewer than [minSamples], the
 * value passes through untouched: with nothing to compare against, a median
 * of one or two samples is not a filter, it is a coin toss.
 *
 * Because the window is centred, a sample can only be emitted once samples
 * newer by half a window have arrived. So the live figure trails the walk by
 * about five seconds, and [flush] books the tail when the recording ends.
 */
class AltitudeSmoother(
    private val halfWindowMillis: Long = 5_000L,
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

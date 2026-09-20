package com.aripd.norda.core.io

import com.aripd.norda.core.nav.Waypoint
import com.aripd.norda.core.track.TrackPoint
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * GPX interchange (docs/MVP.md, section 10): the track (`trk`) and the
 * waypoints (`wpt`) in a single file. Altitude is written only when valid —
 * the 0.0 sentinel value never leaks out. Parsing tolerates broken input by
 * skipping entries. Pure JVM (java.xml); touches no Android, fully tested.
 */
object Gpx {

    class ParsedPoint(
        val point: TrackPoint,
        val hasAltitude: Boolean,
        /** First point of a segment after the first: the track broke here (F-17). */
        val afterPause: Boolean = false
    )

    class Parsed(
        val name: String?,
        val points: List<ParsedPoint>,
        val waypoints: List<Waypoint>,
        val report: Report? = null
    )

    /** GPS filter verdict counters — the raw data for threshold calibration. */
    class FilterCounts(
        val accept: Int,
        val badAccuracy: Int,
        val jitter: Int,
        val teleport: Int,
        val nonMonotonic: Int
    )

    /**
     * Outing telemetry (F-3, Field Run 1): the summary, battery and filter
     * counters as the app saw them travel INSIDE the GPX — the field report is
     * a single file, no manual notes needed. Goes as GPX 1.1 `extensions` +
     * our own namespace; other tools ignore the block, and import does not
     * treat it as data (statistics are always recomputed from the points).
     */
    class Report(
        val filter: FilterCounts?,
        val startBatteryPct: Int?,
        val endBatteryPct: Int?,
        /** Charge counter in µAh at start/end (B-1); null if the device has none. */
        val startChargeUah: Long? = null,
        val endChargeUah: Long? = null,
        val distanceM: Double,
        val activeMillis: Long,
        val gainM: Double,
        val lossM: Double,
        /** App version that wrote the recording (F-6): the file identifies itself. */
        val appVersion: String? = null
    )

    private const val NORDA_NS = "https://github.com/aripdcom/norda/gpx/1"

    /**
     * [segmentBreaks] is parallel to [points] when given: true means that point
     * opens a new `<trkseg>`, which is how GPX marks a break in a track (F-17).
     * Norda writes one at every manual pause, so no tool — ours included —
     * draws or counts a line across ground that was covered while paused.
     */
    fun write(
        trackName: String,
        points: List<TrackPoint>,
        altitudeValid: List<Boolean>,
        waypoints: List<Waypoint>,
        report: Report? = null,
        segmentBreaks: List<Boolean> = emptyList()
    ): String {
        require(points.size == altitudeValid.size) { "point and altitude lists must have the same size" }
        val breaks = if (segmentBreaks.size == points.size) segmentBreaks else emptyList()
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"Norda\" ")
        sb.append("xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        for (w in waypoints) {
            sb.append("<wpt lat=\"").append(w.latitude).append("\" lon=\"")
                .append(w.longitude).append("\">\n")
            w.altitude?.let { sb.append("<ele>").append(it).append("</ele>\n") }
            sb.append("<name>").append(escape(w.name)).append("</name>\n")
            sb.append("</wpt>\n")
        }
        if (points.isNotEmpty()) {
            sb.append("<trk>\n<name>").append(escape(trackName)).append("</name>\n<trkseg>\n")
            for (i in points.indices) {
                if (i > 0 && breaks.isNotEmpty() && breaks[i]) {
                    sb.append("</trkseg>\n<trkseg>\n")
                }
                val p = points[i]
                sb.append("<trkpt lat=\"").append(p.latitude).append("\" lon=\"")
                    .append(p.longitude).append("\">\n")
                if (altitudeValid[i]) sb.append("<ele>").append(p.altitude).append("</ele>\n")
                if (p.timeMillis > 0) {
                    sb.append("<time>").append(utcFormat().format(Date(p.timeMillis)))
                        .append("</time>\n")
                }
                sb.append("</trkpt>\n")
            }
            sb.append("</trkseg>\n</trk>\n")
        }
        if (report != null) {
            // In the GPX 1.1 schema, extensions is the LAST child of gpx.
            sb.append("<extensions>\n<norda:report xmlns:norda=\"")
                .append(NORDA_NS).append("\"")
            report.appVersion?.let { sb.append(" app=\"").append(escape(it)).append("\"") }
            sb.append(">\n")
            sb.append("<norda:summary distanceM=\"").append(report.distanceM)
                .append("\" activeMillis=\"").append(report.activeMillis)
                .append("\" gainM=\"").append(report.gainM)
                .append("\" lossM=\"").append(report.lossM).append("\"/>\n")
            if (report.startBatteryPct != null || report.endBatteryPct != null ||
                report.startChargeUah != null || report.endChargeUah != null
            ) {
                sb.append("<norda:battery")
                report.startBatteryPct?.let { sb.append(" startPct=\"").append(it).append("\"") }
                report.endBatteryPct?.let { sb.append(" endPct=\"").append(it).append("\"") }
                report.startChargeUah?.let { sb.append(" startUah=\"").append(it).append("\"") }
                report.endChargeUah?.let { sb.append(" endUah=\"").append(it).append("\"") }
                sb.append("/>\n")
            }
            report.filter?.let { f ->
                sb.append("<norda:filter accept=\"").append(f.accept)
                    .append("\" badAccuracy=\"").append(f.badAccuracy)
                    .append("\" jitter=\"").append(f.jitter)
                    .append("\" teleport=\"").append(f.teleport)
                    .append("\" nonMonotonic=\"").append(f.nonMonotonic)
                    .append("\"/>\n")
            }
            sb.append("</norda:report>\n</extensions>\n")
        }
        sb.append("</gpx>\n")
        return sb.toString()
    }

    fun parse(xml: String): Parsed {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        val points = ArrayList<ParsedPoint>()
        val waypoints = ArrayList<Waypoint>()
        var name: String? = null

        val trackNames = doc.getElementsByTagName("name")
        for (i in 0 until trackNames.length) {
            val el = trackNames.item(i) as Element
            if (el.parentNode?.nodeName == "trk") {
                name = el.textContent?.trim()
                break
            }
        }
        // Segments matter: a second `<trkseg>` means the track broke there, and
        // the ground in between is not distance (F-17). Read per segment so the
        // break survives the round trip; a file with no segment element at all
        // is read as one continuous track.
        val segments = doc.getElementsByTagName("trkseg")
        var segmentCount = 0
        for (segIndex in 0 until segments.length) {
            val children = (segments.item(segIndex) as Element).getElementsByTagName("trkpt")
            var firstOfSegment = true
            for (i in 0 until children.length) {
                val parsed = parsePoint(children.item(i) as Element) ?: continue
                points += if (segmentCount > 0 && firstOfSegment) {
                    ParsedPoint(parsed.point, parsed.hasAltitude, afterPause = true)
                } else {
                    parsed
                }
                firstOfSegment = false
            }
            if (children.length > 0) segmentCount++
        }
        if (points.isEmpty()) {
            val trkpts = doc.getElementsByTagName("trkpt")
            for (i in 0 until trkpts.length) {
                points += parsePoint(trkpts.item(i) as Element) ?: continue
            }
        }
        val wpts = doc.getElementsByTagName("wpt")
        for (i in 0 until wpts.length) {
            val el = wpts.item(i) as Element
            val lat = el.getAttribute("lat").toDoubleOrNull() ?: continue
            val lon = el.getAttribute("lon").toDoubleOrNull() ?: continue
            waypoints += Waypoint(
                id = 0,
                name = childText(el, "name")?.trim().orEmpty(),
                latitude = lat,
                longitude = lon,
                altitude = childText(el, "ele")?.toDoubleOrNull(),
                createdAtMillis = 0
            )
        }
        return Parsed(name, points, waypoints, parseReport(doc))
    }

    /** The `norda:report` block — null if absent or broken (tolerance rule). */
    private fun parseReport(doc: org.w3c.dom.Document): Report? {
        val reports = doc.getElementsByTagName("norda:report")
        if (reports.length == 0) return null
        val el = reports.item(0) as Element
        val summary = firstChildElement(el, "norda:summary") ?: return null
        return Report(
            filter = parseFilter(firstChildElement(el, "norda:filter")),
            startBatteryPct = firstChildElement(el, "norda:battery")
                ?.getAttribute("startPct")?.toIntOrNull(),
            endBatteryPct = firstChildElement(el, "norda:battery")
                ?.getAttribute("endPct")?.toIntOrNull(),
            startChargeUah = firstChildElement(el, "norda:battery")
                ?.getAttribute("startUah")?.toLongOrNull(),
            endChargeUah = firstChildElement(el, "norda:battery")
                ?.getAttribute("endUah")?.toLongOrNull(),
            distanceM = summary.getAttribute("distanceM").toDoubleOrNull() ?: return null,
            activeMillis = summary.getAttribute("activeMillis").toLongOrNull() ?: return null,
            gainM = summary.getAttribute("gainM").toDoubleOrNull() ?: return null,
            lossM = summary.getAttribute("lossM").toDoubleOrNull() ?: return null,
            appVersion = el.getAttribute("app").takeIf { it.isNotEmpty() }
        )
    }

    /** One `<trkpt>`; null when the line is broken (tolerance rule). */
    private fun parsePoint(el: Element): ParsedPoint? {
        val lat = el.getAttribute("lat").toDoubleOrNull() ?: return null
        val lon = el.getAttribute("lon").toDoubleOrNull() ?: return null
        val ele = childText(el, "ele")?.toDoubleOrNull()
        return ParsedPoint(
            TrackPoint(
                timeMillis = parseTimeMillis(childText(el, "time")),
                latitude = lat,
                longitude = lon,
                altitude = ele ?: 0.0,
                accuracyM = 0f,
                speedMps = 0f,
                bearingDeg = 0f
            ),
            hasAltitude = ele != null
        )
    }

    private fun parseFilter(el: Element?): FilterCounts? {
        el ?: return null
        return FilterCounts(
            accept = el.getAttribute("accept").toIntOrNull() ?: return null,
            badAccuracy = el.getAttribute("badAccuracy").toIntOrNull() ?: return null,
            jitter = el.getAttribute("jitter").toIntOrNull() ?: return null,
            teleport = el.getAttribute("teleport").toIntOrNull() ?: return null,
            nonMonotonic = el.getAttribute("nonMonotonic").toIntOrNull() ?: return null
        )
    }

    private fun firstChildElement(parent: Element, tag: String): Element? {
        val children = parent.getElementsByTagName(tag)
        return if (children.length == 0) null else children.item(0) as Element
    }

    private fun childText(parent: Element, tag: String): String? {
        val children = parent.getElementsByTagName(tag)
        for (i in 0 until children.length) {
            val el = children.item(i)
            if (el.parentNode === parent) return el.textContent
        }
        return null
    }

    private fun parseTimeMillis(text: String?): Long {
        if (text == null) return 0
        for (pattern in arrayOf(
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
        )) {
            try {
                val fmt = SimpleDateFormat(pattern, Locale.US)
                fmt.timeZone = TimeZone.getTimeZone("UTC")
                return fmt.parse(text.trim())?.time ?: 0
            } catch (_: Exception) {
                // try the next format
            }
        }
        return 0
    }

    private fun utcFormat(): SimpleDateFormat =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    private fun escape(text: String): String = buildString(text.length) {
        for (c in text) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(c)
            }
        }
    }
}

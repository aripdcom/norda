package com.aripd.norda

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.aripd.norda.core.io.Gpx
import com.aripd.norda.core.nav.WaypointNaming
import com.aripd.norda.core.nav.WaypointScope
import com.aripd.norda.core.track.ActivitySummary
import com.aripd.norda.geo.Geoids
import com.aripd.norda.map.MapHint
import com.aripd.norda.map.MapPackages
import com.aripd.norda.map.MapView
import com.aripd.norda.map.TileStore
import com.aripd.norda.storage.ActivityDao
import com.aripd.norda.storage.AppDatabase
import com.aripd.norda.storage.WaypointDao
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen map: an activity's track (from history, with GPX export) or a
 * package preview. Long-pressing the map adds a waypoint (MVP 2.1).
 */
class MapActivity : Activity() {

    private lateinit var mapView: MapView
    private lateinit var waypointDao: WaypointDao
    private var activityId = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map)
        Insets.apply(findViewById(R.id.root))
        mapView = findViewById(R.id.mapView)
        waypointDao = WaypointDao(AppDatabase.get(this))

        // Why the map is empty, in one line (F-15, MVP 7.4).
        val mapHint = findViewById<TextView>(R.id.mapHint)
        mapView.onCoverage = { state ->
            val text = MapHint.text(this, state, mapView.packageName, mapView.currentZoom)
            mapHint.text = text.orEmpty()
            mapHint.visibility = if (text == null) View.GONE else View.VISIBLE
        }

        activityId = intent.getLongExtra(EXTRA_ACTIVITY_ID, -1L)
        if (activityId > 0) {
            showTrack(activityId)
        } else {
            showPackage(intent.getStringExtra(EXTRA_PACKAGE_PATH))
        }

        mapView.onLongPressLatLon = { lat, lon -> addWaypointDialog(lat, lon) }

        val exportButton = findViewById<Button>(R.id.gpxExportButton)
        exportButton.visibility = if (activityId > 0) View.VISIBLE else View.GONE
        exportButton.setOnClickListener {
            @Suppress("DEPRECATION")
            startActivityForResult(
                Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/gpx+xml")
                    .putExtra(Intent.EXTRA_TITLE, exportFileName()),
                REQUEST_EXPORT
            )
        }
    }

    override fun onResume() {
        super.onResume()
        mapView.setWaypoints(waypointDao.list())
    }

    private fun showTrack(activityId: Long) {
        val points = ActivityDao(AppDatabase.get(this)).pointsFor(activityId)
        mapView.setTrack(points)
        val mid = points.getOrNull(points.size / 2)
        mapView.setStore(MapPackages.openBest(this, mid?.latitude, mid?.longitude))
        if (points.isNotEmpty()) {
            mapView.post { mapView.fitToTrack() }
        }
    }

    private fun showPackage(path: String?) {
        val store = path?.let { TileStore.open(File(it)) }
            ?: MapPackages.openBest(this, null, null)
        val center = store?.boundsCenter()
        val minZoom = store?.minZoom
        mapView.setStore(store)
        if (center != null && minZoom != null) {
            mapView.setZoom(minZoom + 1)
            mapView.setCenter(center.first, center.second)
        }
    }

    private fun addWaypointDialog(lat: Double, lon: Double) {
        val defaultName =
            WaypointNaming.nextDefaultName(waypointDao.names(), getString(R.string.waypoint_prefix))
        val input = EditText(this).apply { setText(defaultName) }
        AlertDialog.Builder(this)
            .setTitle(R.string.add_waypoint_title)
            .setView(input)
            .setPositiveButton(R.string.waypoint_save) { _, _ ->
                val name = WaypointNaming.sanitize(input.text.toString()) ?: defaultName
                waypointDao.insert(name, lat, lon, null, System.currentTimeMillis())
                mapView.setWaypoints(waypointDao.list())
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---- GPX export: the track + the outing's waypoints in one file (MVP 10) ----

    private fun exportFileName(): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return "norda-$date.gpx"
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_EXPORT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            val dao = ActivityDao(AppDatabase.get(this))
            val detailed = dao.pointsDetailed(activityId)
            val summary = dao.summary(activityId)
            // Every other tool reads GPX <ele> as height above mean sea level,
            // while the database keeps the receiver's ellipsoid height — in
            // Istanbul the two differ by ~37 m (Y-1, MVP 5.7). The conversion
            // happens here, at the edge.
            val points = detailed.map { stored ->
                val point = stored.point
                if (stored.hasAltitude) {
                    point.copy(
                        altitude = Geoids.toMsl(
                            this, point.latitude, point.longitude, point.altitude
                        )
                    )
                } else {
                    point
                }
            }
            // Only the waypoints that belong to this outing travel with it
            // (F-18): the ones saved during the recording, and the ones the
            // walk went past. The rest stay on the phone — a track file is not
            // a backup of every place the owner has ever marked.
            val track = detailed.map { it.point }
            val waypoints = WaypointScope.forTrack(
                waypoints = waypointDao.list(),
                track = track,
                startMillis = summary?.startTimeMillis
                    ?: track.firstOrNull()?.timeMillis ?: 0L,
                endMillis = summary?.endTimeMillis
                    ?: track.lastOrNull()?.timeMillis ?: 0L
            ).map { w ->
                val altitude = w.altitude
                if (altitude != null) {
                    w.copy(altitude = Geoids.toMsl(this, w.latitude, w.longitude, altitude))
                } else {
                    w
                }
            }
            val xml = Gpx.write(
                trackName = "Norda ${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}",
                points = points,
                altitudeValid = detailed.map { it.hasAltitude },
                waypoints = waypoints,
                report = buildReport(summary),
                // A manual pause becomes a new <trkseg>: no tool draws or
                // counts a line across ground covered while paused (F-17).
                segmentBreaks = detailed.map { it.afterPause }
            )
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(xml.toByteArray(Charsets.UTF_8))
            } ?: throw IllegalStateException("could not open the output stream")
            // The toast says what left the phone: on a file that may be
            // shared, the waypoint count is the part worth seeing (F-18).
            Toast.makeText(
                this,
                getString(R.string.gpx_exported, points.size, waypoints.size),
                Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.gpx_export_failed, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Outing telemetry (F-3): summary + battery from the DB; the filter
     * counters are added only if the last recording IS this activity — the
     * counters belong to the last recording, not to the activity, and an old
     * track exported later must not get the wrong counters embedded.
     */
    private fun buildReport(s: ActivitySummary?): Gpx.Report? {
        if (s == null) return null
        val prefs = getSharedPreferences(
            com.aripd.norda.tracking.TrackingService.FILTER_STATS_PREFS, MODE_PRIVATE
        )
        val filter = if (prefs.getLong("activity_id", -1L) == activityId) {
            Gpx.FilterCounts(
                accept = prefs.getInt("accept", 0),
                badAccuracy = prefs.getInt("bad_accuracy", 0),
                jitter = prefs.getInt("jitter", 0),
                teleport = prefs.getInt("teleport", 0),
                nonMonotonic = prefs.getInt("non_monotonic", 0)
            )
        } else null
        return Gpx.Report(
            filter = filter,
            startBatteryPct = s.startBatteryPct,
            endBatteryPct = s.endBatteryPct,
            startChargeUah = s.startChargeUah,
            endChargeUah = s.endChargeUah,
            distanceM = s.distanceM,
            activeMillis = s.durationMillis,
            gainM = s.elevationGainM,
            lossM = s.elevationLossM,
            appVersion = BuildConfig.VERSION_NAME
        )
    }

    companion object {
        const val EXTRA_ACTIVITY_ID = "activity_id"
        const val EXTRA_PACKAGE_PATH = "package_path"
        private const val REQUEST_EXPORT = 3
    }
}

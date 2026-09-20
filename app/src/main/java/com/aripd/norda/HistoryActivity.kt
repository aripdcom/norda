package com.aripd.norda

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.aripd.norda.core.io.Gpx
import com.aripd.norda.core.nav.WaypointNaming
import com.aripd.norda.core.track.ActivitySummary
import com.aripd.norda.core.track.Battery
import com.aripd.norda.core.track.ActivityType
import com.aripd.norda.core.track.AltitudeSmoother
import com.aripd.norda.core.track.ElevationTracker
import com.aripd.norda.core.track.Format
import com.aripd.norda.core.track.Stats
import com.aripd.norda.geo.Geoids
import com.aripd.norda.storage.ActivityDao
import com.aripd.norda.storage.AppDatabase
import com.aripd.norda.storage.WaypointDao
import java.text.DateFormat
import java.util.Date

/** List of finished activities; long-press deletes. Map detail comes in Phase 4. */
class HistoryActivity : Activity() {

    private lateinit var dao: ActivityDao
    private lateinit var listView: ListView
    private val items = mutableListOf<ActivitySummary>()
    private val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = items[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView
                ?: layoutInflater.inflate(R.layout.row_activity, parent, false)
            val a = items[position]
            val typeLabel = getString(
                if (a.type == ActivityType.WALK) R.string.walk else R.string.run
            )
            row.findViewById<TextView>(R.id.rowTitle).text =
                getString(R.string.history_row_title, dateFormat.format(Date(a.startTimeMillis)), typeLabel)
            val distance =
                if (a.distanceM < 1000) getString(R.string.distance_m, a.distanceM.toInt())
                else getString(R.string.distance_km, a.distanceM / 1000.0)
            val stats = StringBuilder(
                getString(
                    R.string.history_row_stats,
                    distance, Format.duration(a.durationMillis), a.elevationGainM.toInt()
                )
            )
            stats.append(batteryLine(a))
            row.findViewById<TextView>(R.id.rowStats).text = stats
            return row
        }

        /**
         * Battery measurement culture (Phase 8): a number appears only when it
         * was measured cleanly, and the rate's denominator is the wall clock
         * (F-1) — the battery drains during pauses too.
         *
         * The charge counter comes first when the device serves it (B-1): the
         * integer percentage sits on a level for a long stretch, so a
         * half-hour outing can read 0%, as the Sept 12 night walk did. With
         * µAh the same outing gets a real mAh figure and a fractional
         * percentage. Without the counter the percentage remains the source.
         */
        private fun batteryLine(a: ActivitySummary): String {
            val wallSpan = a.endTimeMillis - a.startTimeMillis
            val preferCharge = Battery.preferCharge(
                a.startChargeUah, a.endChargeUah, a.startBatteryPct, a.endBatteryPct
            )
            val mah = if (preferCharge) {
                Battery.chargeDrainMah(a.startChargeUah, a.endChargeUah)
            } else null
            if (mah != null) {
                val full = Battery.fullChargeUah(a.startChargeUah, a.startBatteryPct)
                val percent = Battery.chargeDrainPercent(a.startChargeUah, a.endChargeUah, full)
                if (percent != null) {
                    val rate = Battery.drainPerHour(percent, wallSpan)
                    return if (rate != null) {
                        getString(R.string.history_row_charge_rate, percent, mah.toInt(), rate)
                    } else {
                        getString(R.string.history_row_charge, percent, mah.toInt())
                    }
                }
                // No level to scale by: the mAh still stands on its own.
                return getString(R.string.history_row_charge_mah, mah.toInt())
            }
            // Fed during the recording: there is no consumption to report, and
            // an empty line would read as "not measured" (field tour, Sept 13
            // seaside walk: 16% → 57%).
            if (Battery.wasCharging(
                    a.startBatteryPct, a.endBatteryPct, a.startChargeUah, a.endChargeUah
                )
            ) {
                return getString(R.string.history_row_charging)
            }
            val drain = Battery.drainPercent(a.startBatteryPct, a.endBatteryPct) ?: return ""
            val rate = Battery.drainPerHour(drain, wallSpan)
            return if (rate != null) getString(R.string.history_row_battery_rate, drain, rate)
            else getString(R.string.history_row_battery, drain)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        Insets.apply(findViewById(R.id.root))

        dao = ActivityDao(AppDatabase.get(this))
        listView = findViewById(R.id.historyList)
        listView.emptyView = findViewById(R.id.emptyText)
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            startActivity(
                android.content.Intent(this, MapActivity::class.java)
                    .putExtra(MapActivity.EXTRA_ACTIVITY_ID, items[position].id)
            )
        }
        listView.setOnItemLongClickListener { _, _, position, _ ->
            confirmDelete(items[position])
            true
        }

        findViewById<Button>(R.id.gpxImportButton).setOnClickListener {
            @Suppress("DEPRECATION")
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*"),
                REQUEST_IMPORT
            )
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_IMPORT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            val xml = contentResolver.openInputStream(uri)
                ?.bufferedReader()?.use { it.readText() }
                ?: return
            val parsed = Gpx.parse(xml)

            if (parsed.points.size >= 2) {
                val first = parsed.points.first().point
                val last = parsed.points.last().point
                val startTime =
                    if (first.timeMillis > 0) first.timeMillis else System.currentTimeMillis()
                val id = dao.startActivity(ActivityType.WALK, startTime)
                val elevation = ElevationTracker()
                // The same smoother as the live recording, so an imported
                // track reads the way it would have been recorded (Y-3).
                val smoother = AltitudeSmoother()
                for (p in parsed.points) {
                    // GPX elevations are heights above mean sea level; the
                    // database keeps ellipsoid heights, as the receiver
                    // reports them (Y-1, MVP 5.7).
                    val point = if (p.hasAltitude) {
                        p.point.copy(
                            altitude = Geoids.toEllipsoid(
                                this, p.point.latitude, p.point.longitude, p.point.altitude
                            )
                        )
                    } else {
                        p.point
                    }
                    dao.appendPoint(id, point, p.hasAltitude, p.afterPause)
                    if (p.hasAltitude) {
                        smoother.onAltitude(point.timeMillis, point.altitude)
                            .forEach(elevation::onAltitude)
                    }
                }
                smoother.flush().forEach(elevation::onAltitude)
                val duration =
                    if (first.timeMillis > 0 && last.timeMillis > first.timeMillis)
                        last.timeMillis - first.timeMillis
                    else 0L
                dao.finishActivity(
                    ActivitySummary(
                        id = id,
                        type = ActivityType.WALK,
                        startTimeMillis = startTime,
                        endTimeMillis = startTime + duration,
                        // A second <trkseg> marks a break: that leg is not
                        // distance, on import either (F-17).
                        distanceM = Stats.totalDistanceMeters(
                            parsed.points.map { it.point },
                            parsed.points.map { it.afterPause }
                        ),
                        durationMillis = duration,
                        elevationGainM = elevation.gainM,
                        elevationLossM = elevation.lossM
                    )
                )
            }

            val waypointDao = WaypointDao(AppDatabase.get(this))
            for (w in parsed.waypoints) {
                val name = WaypointNaming.sanitize(w.name) ?: WaypointNaming.nextDefaultName(
                    waypointDao.names(), getString(R.string.waypoint_prefix)
                )
                val altitude = w.altitude?.let {
                    Geoids.toEllipsoid(this, w.latitude, w.longitude, it)
                }
                waypointDao.insert(
                    name, w.latitude, w.longitude, altitude, System.currentTimeMillis()
                )
            }

            Toast.makeText(
                this,
                getString(R.string.gpx_imported, parsed.points.size, parsed.waypoints.size),
                Toast.LENGTH_LONG
            ).show()
            reload()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.gpx_import_failed, Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        items.clear()
        items.addAll(dao.listFinished())
        adapter.notifyDataSetChanged()
    }

    private fun confirmDelete(a: ActivitySummary) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.delete_confirm))
            .setPositiveButton(R.string.delete) { _, _ ->
                dao.deleteActivity(a.id)
                reload()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private companion object {
        const val REQUEST_IMPORT = 4
    }
}

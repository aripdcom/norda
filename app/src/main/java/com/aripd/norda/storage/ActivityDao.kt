package com.aripd.norda.storage

import android.content.ContentValues
import com.aripd.norda.core.track.ActivitySummary
import com.aripd.norda.core.track.ActivityType
import com.aripd.norda.core.track.TrackPoint

/** Identity of an unfinished (end_time NULL) recording — for recovery. */
data class UnfinishedActivity(
    val id: Long,
    val type: ActivityType,
    val startTimeMillis: Long
)

/**
 * A stored point with the two facts that are not in [TrackPoint] itself: was
 * its altitude valid, and does it open a new leg after a pause (F-17).
 */
data class StoredPoint(
    val point: TrackPoint,
    val hasAltitude: Boolean,
    val afterPause: Boolean
)

/** Dumb data access layer: only reads and writes, makes no decisions (MVP.md 8.2). */
class ActivityDao(private val helper: AppDatabase) {

    fun startActivity(
        type: ActivityType,
        startTimeMillis: Long,
        startBatteryPct: Int? = null,
        startChargeUah: Long? = null
    ): Long =
        helper.writableDatabase.insertOrThrow("activity", null, ContentValues().apply {
            put("type", type.name)
            put("start_time", startTimeMillis)
            if (startBatteryPct != null) put("start_battery", startBatteryPct)
            if (startChargeUah != null) put("start_charge_uah", startChargeUah)
        })

    fun appendPoint(
        activityId: Long,
        p: TrackPoint,
        hasAltitude: Boolean,
        afterPause: Boolean = false
    ) {
        helper.writableDatabase.insertOrThrow("track_point", null, ContentValues().apply {
            put("activity_id", activityId)
            put("timestamp", p.timeMillis)
            put("latitude", p.latitude)
            put("longitude", p.longitude)
            if (hasAltitude) put("altitude", p.altitude) else putNull("altitude")
            put("accuracy", p.accuracyM)
            put("speed", p.speedMps)
            put("bearing", p.bearingDeg)
            // Only the points that open a leg carry the flag; NULL is the norm.
            if (afterPause) put("after_pause", 1)
        })
    }

    fun finishActivity(summary: ActivitySummary) {
        helper.writableDatabase.update("activity", ContentValues().apply {
            put("end_time", summary.endTimeMillis)
            put("distance_m", summary.distanceM)
            put("duration_ms", summary.durationMillis)
            put("elevation_gain_m", summary.elevationGainM)
            put("elevation_loss_m", summary.elevationLossM)
            if (summary.endBatteryPct != null) put("end_battery", summary.endBatteryPct)
            if (summary.endChargeUah != null) put("end_charge_uah", summary.endChargeUah)
        }, "id = ?", arrayOf(summary.id.toString()))
    }

    fun unfinishedActivity(): UnfinishedActivity? =
        helper.readableDatabase.query(
            "activity", arrayOf("id", "type", "start_time"),
            "end_time IS NULL", null, null, null, "start_time ASC", "1"
        ).use { c ->
            if (!c.moveToFirst()) return null
            UnfinishedActivity(c.getLong(0), ActivityType.fromName(c.getString(1)), c.getLong(2))
        }

    fun pointsFor(activityId: Long): List<TrackPoint> =
        helper.readableDatabase.query(
            "track_point",
            arrayOf("timestamp", "latitude", "longitude", "altitude", "accuracy", "speed", "bearing"),
            "activity_id = ?", arrayOf(activityId.toString()), null, null, "timestamp ASC"
        ).use { c ->
            val out = ArrayList<TrackPoint>(c.count)
            while (c.moveToNext()) {
                out += TrackPoint(
                    timeMillis = c.getLong(0),
                    latitude = c.getDouble(1),
                    longitude = c.getDouble(2),
                    altitude = if (c.isNull(3)) 0.0 else c.getDouble(3),
                    accuracyM = c.getFloat(4),
                    speedMps = c.getFloat(5),
                    bearingDeg = c.getFloat(6)
                )
            }
            out
        }

    /**
     * Points with the altitude flag and the pause flag — for GPX export and
     * for every recomputation that has to skip the paused legs (F-17).
     */
    fun pointsDetailed(activityId: Long): List<StoredPoint> =
        helper.readableDatabase.query(
            "track_point",
            arrayOf(
                "timestamp", "latitude", "longitude", "altitude", "accuracy", "speed",
                "bearing", "after_pause"
            ),
            "activity_id = ?", arrayOf(activityId.toString()), null, null, "timestamp ASC"
        ).use { c ->
            val out = ArrayList<StoredPoint>(c.count)
            while (c.moveToNext()) {
                val hasAltitude = !c.isNull(3)
                out += StoredPoint(
                    TrackPoint(
                        timeMillis = c.getLong(0),
                        latitude = c.getDouble(1),
                        longitude = c.getDouble(2),
                        altitude = if (hasAltitude) c.getDouble(3) else 0.0,
                        accuracyM = c.getFloat(4),
                        speedMps = c.getFloat(5),
                        bearingDeg = c.getFloat(6)
                    ),
                    hasAltitude = hasAltitude,
                    afterPause = !c.isNull(7) && c.getInt(7) != 0
                )
            }
            out
        }

    /**
     * The most recently stored point of any activity — a rough position for
     * night mode's sun arithmetic when nothing fresher is known (NightMode).
     */
    fun lastKnownPosition(): Pair<Double, Double>? =
        helper.readableDatabase.query(
            "track_point", arrayOf("latitude", "longitude"),
            null, null, null, null, "id DESC", "1"
        ).use { c -> if (c.moveToFirst()) c.getDouble(0) to c.getDouble(1) else null }

    /**
     * Altitudes of the points that reported a valid altitude, with their
     * timestamps, in time order. The time comes along because the elevation
     * smoother's window is measured in seconds (Y-3, MVP 5.4).
     */
    fun altitudesFor(activityId: Long): List<Pair<Long, Double>> =
        helper.readableDatabase.query(
            "track_point", arrayOf("timestamp", "altitude"),
            "activity_id = ? AND altitude IS NOT NULL", arrayOf(activityId.toString()),
            null, null, "timestamp ASC"
        ).use { c ->
            val out = ArrayList<Pair<Long, Double>>(c.count)
            while (c.moveToNext()) out += c.getLong(0) to c.getDouble(1)
            out
        }

    fun summary(activityId: Long): ActivitySummary? =
        helper.readableDatabase.query(
            "activity",
            arrayOf(
                "id", "type", "start_time", "end_time",
                "distance_m", "duration_ms", "elevation_gain_m", "elevation_loss_m",
                "start_battery", "end_battery",
                "start_charge_uah", "end_charge_uah"
            ),
            "id = ? AND end_time IS NOT NULL", arrayOf(activityId.toString()),
            null, null, null, "1"
        ).use { c ->
            if (!c.moveToFirst()) return null
            ActivitySummary(
                id = c.getLong(0),
                type = ActivityType.fromName(c.getString(1)),
                startTimeMillis = c.getLong(2),
                endTimeMillis = c.getLong(3),
                distanceM = c.getDouble(4),
                durationMillis = c.getLong(5),
                elevationGainM = c.getDouble(6),
                elevationLossM = c.getDouble(7),
                startBatteryPct = if (c.isNull(8)) null else c.getInt(8),
                endBatteryPct = if (c.isNull(9)) null else c.getInt(9),
                startChargeUah = if (c.isNull(10)) null else c.getLong(10),
                endChargeUah = if (c.isNull(11)) null else c.getLong(11)
            )
        }

    fun listFinished(): List<ActivitySummary> =
        helper.readableDatabase.query(
            "activity",
            arrayOf(
                "id", "type", "start_time", "end_time",
                "distance_m", "duration_ms", "elevation_gain_m", "elevation_loss_m",
                "start_battery", "end_battery",
                "start_charge_uah", "end_charge_uah"
            ),
            "end_time IS NOT NULL", null, null, null, "start_time DESC"
        ).use { c ->
            val out = ArrayList<ActivitySummary>(c.count)
            while (c.moveToNext()) {
                out += ActivitySummary(
                    id = c.getLong(0),
                    type = ActivityType.fromName(c.getString(1)),
                    startTimeMillis = c.getLong(2),
                    endTimeMillis = c.getLong(3),
                    distanceM = c.getDouble(4),
                    durationMillis = c.getLong(5),
                    elevationGainM = c.getDouble(6),
                    elevationLossM = c.getDouble(7),
                    startBatteryPct = if (c.isNull(8)) null else c.getInt(8),
                    endBatteryPct = if (c.isNull(9)) null else c.getInt(9),
                    startChargeUah = if (c.isNull(10)) null else c.getLong(10),
                    endChargeUah = if (c.isNull(11)) null else c.getLong(11)
                )
            }
            out
        }

    fun deleteActivity(activityId: Long) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.delete("track_point", "activity_id = ?", arrayOf(activityId.toString()))
            db.delete("activity", "id = ?", arrayOf(activityId.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}

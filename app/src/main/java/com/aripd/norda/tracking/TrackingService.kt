package com.aripd.norda.tracking

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.widget.Toast
import com.aripd.norda.R
import com.aripd.norda.RecordingActivity
import com.aripd.norda.core.track.ActivityType
import com.aripd.norda.core.track.Format
import com.aripd.norda.core.track.GpsFilter
import com.aripd.norda.core.track.RecordingSession
import com.aripd.norda.core.track.Stats
import com.aripd.norda.core.track.TrackPoint
import com.aripd.norda.storage.ActivityDao
import com.aripd.norda.storage.AppDatabase

/**
 * The recording now belongs to this foreground service, not to the screen
 * (docs/MVP.md, 5.6): it keeps running when the screen turns off or the user
 * switches to another app. Every accepted point is written to disk at once;
 * if the system kills the process and restarts the service (START_STICKY),
 * the recording is taken over from the unfinished activity on disk.
 */
class TrackingService : Service(), LocationListener {

    private lateinit var locationManager: LocationManager
    private lateinit var dao: ActivityDao
    private var lastNotifiedMillis = 0L

    // The screens live in the same process; no binding ceremony needed,
    // control goes through the static surface in the companion.
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        dao = ActivityDao(AppDatabase.get(this))
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (session == null) {
            if (intent != null) {
                startNewRecording(ActivityType.fromName(intent.getStringExtra(EXTRA_TYPE)))
            } else if (!resumeUnfinishedRecording()) {
                // Sticky restart, but no recording to take over.
                stopSelf()
                return START_NOT_STICKY
            }
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        startLocationUpdates()
        return START_STICKY
    }

    private fun startNewRecording(type: ActivityType) {
        val s = RecordingSession(
            type = type,
            startWallMillis = System.currentTimeMillis(),
            startMonotonicMillis = SystemClock.elapsedRealtime()
        )
        activityId = dao.startActivity(
            type, s.startWallMillis, batteryPercent(), batteryChargeUah()
        )
        session = s
    }

    /** Battery percent; null if unreadable — Battery rules forbid made-up values. */
    private fun batteryPercent(): Int? =
        (getSystemService(BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            .takeIf { it in 0..100 }

    /**
     * Remaining charge in µAh (B-1): the integer percent is too coarse for a
     * half-hour outing — on the Sept 12 night walk it read 80% at both ends.
     * Devices without the counter answer 0 or `Int.MIN_VALUE`; that is not
     * data, so it becomes null and the percentage stays the source.
     */
    private fun batteryChargeUah(): Long? =
        (getSystemService(BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            .takeIf { it > 0 }
            ?.toLong()

    /**
     * If the system killed and brought the service back: continue the
     * unfinished recording on disk.
     */
    private fun resumeUnfinishedRecording(): Boolean {
        val unfinished = dao.unfinishedActivity() ?: return false
        val stored = dao.pointsDetailed(unfinished.id)
        val points = stored.map { it.point }
        val lastPoint = points.lastOrNull()
        val endTime = lastPoint?.timeMillis ?: unfinished.startTimeMillis
        val s = RecordingSession(
            type = unfinished.type,
            startWallMillis = unfinished.startTimeMillis,
            startMonotonicMillis = SystemClock.elapsedRealtime()
        )
        s.prime(
            // The legs walked while paused are not distance, here either — the
            // flags are on disk since v1.7.0, so recovery no longer re-counts
            // what the live session deliberately skipped (F-17).
            recoveredDistanceM = Stats.totalDistanceMeters(points, stored.map { it.afterPause }),
            // Pause information is not written to disk; the inherited duration
            // is approximated from the point span.
            recoveredDurationMillis = (endTime - unfinished.startTimeMillis).coerceAtLeast(0),
            lastPoint = lastPoint,
            recoveredAltitudes = dao.altitudesFor(unfinished.id)
        )
        activityId = unfinished.id
        session = s
        return true
    }

    private fun startLocationUpdates() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            stopRecording(discard = false)
            return
        }
        // Distance filter deliberately 0 (docs/MVP.md, 5.3). We subscribe even
        // if the provider is disabled at that moment: if the user turns
        // location on later, fixes start flowing; depending on
        // isProviderEnabled would have left the recording silently empty.
        // Subscribing to a provider that does not exist, however, throws —
        // that is what the guard is for.
        if (LocationManager.GPS_PROVIDER in locationManager.allProviders) {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this)
        }
        // Network-seeded warm-up (F-10): the network request seeds GNSS with a
        // coarse position and speeds up the lock. Its fixes are NEVER used
        // anywhere (clean track); it is released once the first real GPS fix
        // arrives (battery rule).
        if (LocationManager.NETWORK_PROVIDER in locationManager.allProviders) {
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER, 5000L, 0f, networkSeed
            )
            networkSeedActive = true
        }
    }

    /** Seed listener: processes no fix at all; its mere presence suffices. */
    private val networkSeed = object : LocationListener {
        override fun onLocationChanged(location: Location) = Unit
        @Deprecated("The framework still calls this; needed for API < 29")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }
    private var networkSeedActive = false

    private fun dropNetworkSeed() {
        if (networkSeedActive) {
            locationManager.removeUpdates(networkSeed)
            networkSeedActive = false
        }
    }

    override fun onLocationChanged(location: Location) {
        // The only way into the track is real GPS (clean-track stance, MVP 11).
        if (location.provider != LocationManager.GPS_PROVIDER) return
        dropNetworkSeed()
        val s = session ?: return
        val stateBefore = s.state
        val point = TrackPoint(
            timeMillis = location.time,
            latitude = location.latitude,
            longitude = location.longitude,
            altitude = location.altitude,
            accuracyM = if (location.hasAccuracy()) location.accuracy else 0f,
            speedMps = if (location.hasSpeed()) location.speed else 0f,
            bearingDeg = if (location.hasBearing()) location.bearing else 0f
        )
        // Because of the settling gate (F-11) a single fix can persist more
        // than one point: the confirmed tentative + the fix itself. The flag
        // belongs to the point — the tentative's may differ from this fix's.
        for (accepted in s.onFix(point, location.hasAltitude(), SystemClock.elapsedRealtime())) {
            dao.appendPoint(
                activityId, accepted.point, accepted.hasAltitude, accepted.afterPause
            )
        }
        // Not a notification on every fix: immediately on a state change,
        // otherwise at least 10 s apart.
        val now = SystemClock.elapsedRealtime()
        if (s.state != stateBefore || now - lastNotifiedMillis >= 10_000L) {
            updateNotification()
        }
    }

    @Deprecated("The framework still calls this; needed for API < 29")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit

    private fun stopRecording(discard: Boolean) {
        val s = session
        if (s != null) saveFilterStats(s)
        if (s != null && !discard) {
            val now = SystemClock.elapsedRealtime()
            s.stop(now)
            if (s.points.isEmpty()) {
                // A recording without a single point is noise in history: the
                // row is deleted and this is said WITH THE REASON (F-4): did no
                // fix arrive at all, or did the accuracy never get under the
                // threshold?
                dao.deleteActivity(activityId)
                val best = s.bestAccuracyM
                var message =
                    if (s.evaluatedFixCount() == 0 || best == null)
                        getString(R.string.discarded_no_fix)
                    else getString(
                        R.string.discarded_poor_accuracy,
                        best.toInt(), GpsFilter.MAX_ACCURACY_M.toInt()
                    )
                // Battery saver throttles GPS on many devices (F-5): it is
                // stated explicitly as a likely cause of the empty recording.
                if ((getSystemService(POWER_SERVICE) as android.os.PowerManager).isPowerSaveMode) {
                    message += " " + getString(R.string.power_save_hint)
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            } else {
                dao.finishActivity(
                    s.summary(activityId, System.currentTimeMillis(), now)
                        .copy(
                            endBatteryPct = batteryPercent(),
                            endChargeUah = batteryChargeUah()
                        )
                )
            }
        }
        session = null
        locationManager.removeUpdates(this)
        dropNetworkSeed()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * The last recording's filter counters are persisted (F-2, Field Run 1
     * finding): so they can be read from Diagnostics even AFTER the outing is
     * over — no need to take notes mid-walk. Written for an empty/discarded
     * recording too: the answer to "why did the recording stay empty" is most
     * often in these counters.
     */
    private fun saveFilterStats(s: RecordingSession) {
        getSharedPreferences(FILTER_STATS_PREFS, MODE_PRIVATE).edit()
            .putLong("activity_id", activityId)
            .putInt("accept", s.filterCount(GpsFilter.Verdict.ACCEPT))
            .putInt("bad_accuracy", s.filterCount(GpsFilter.Verdict.BAD_ACCURACY))
            .putInt("jitter", s.filterCount(GpsFilter.Verdict.JITTER))
            .putInt("teleport", s.filterCount(GpsFilter.Verdict.TELEPORT))
            .putInt("non_monotonic", s.filterCount(GpsFilter.Verdict.NON_MONOTONIC))
            .putLong("saved_at", System.currentTimeMillis())
            .apply()
    }

    override fun onDestroy() {
        locationManager.removeUpdates(this)
        dropNetworkSeed()
        instance = null
        super.onDestroy()
    }

    // ---- Notification ----

    private fun createChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = getString(R.string.notif_channel_desc) }
        )
    }

    private fun buildNotification(): Notification {
        val s = session
        val title = getString(
            if (s?.type == ActivityType.RUN) R.string.run else R.string.walk
        )
        val text = if (s == null) "" else {
            val duration = Format.duration(s.durationMillis(SystemClock.elapsedRealtime()))
            val d = s.distanceM
            val distance =
                if (d < 1000) getString(R.string.distance_m, d.toInt())
                else getString(R.string.distance_km, d / 1000.0)
            val state = when (s.state) {
                RecordingSession.State.PAUSED -> getString(R.string.recording_state_paused)
                RecordingSession.State.AUTO_PAUSED -> getString(R.string.recording_state_auto)
                else -> getString(R.string.recording_state_recording)
            }
            getString(R.string.notif_text, state, duration, distance)
        }
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, RecordingActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_norda)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification() {
        lastNotifiedMillis = SystemClock.elapsedRealtime()
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    companion object {
        const val EXTRA_TYPE = "type"
        /** The Diagnostics screen reads the last recording's counters from here. */
        const val FILTER_STATS_PREFS = "filter_stats"
        private const val CHANNEL_ID = "tracking"
        private const val NOTIFICATION_ID = 1

        private var instance: TrackingService? = null

        /** Quick answer to the screens' "is a recording running" question. */
        var session: RecordingSession? = null
            private set
        var activityId = 0L
            private set
        val isRecording: Boolean get() = session != null

        fun pauseManual() {
            session?.pauseManual(SystemClock.elapsedRealtime())
            instance?.updateNotification()
        }

        fun resumeManual() {
            session?.resumeManual(SystemClock.elapsedRealtime())
            instance?.updateNotification()
        }

        /** Finishes the recording, writes the summary and shuts the service down. */
        fun finishRecording() {
            instance?.stopRecording(discard = false)
        }
    }
}

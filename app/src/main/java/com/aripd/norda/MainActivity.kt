package com.aripd.norda

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import com.aripd.norda.core.sun.NightPolicy
import com.aripd.norda.core.track.ActivityType
import com.aripd.norda.core.track.AltitudeSmoother
import com.aripd.norda.core.track.ElevationTracker
import com.aripd.norda.core.track.GpsFilter
import com.aripd.norda.core.track.Stats
import com.aripd.norda.storage.ActivityDao
import com.aripd.norda.storage.AppDatabase
import com.aripd.norda.tracking.TrackingService

/**
 * Home (docs/MVP.md, 3.1): as empty as possible — pick a type, tap START.
 * If an unfinished recording exists at launch (process death) it is recovered
 * into history.
 *
 * Permission UX (Phase 8): after a denial the reason stays on screen; a
 * permanent denial opens a dialog leading to Settings. If the location
 * service is off, START says so up front — not after the recording has
 * stayed empty.
 *
 * GPS pre-warm-up (F-6): the chip only starts searching once someone asks for
 * GPS; listening starts while Home is open so it locks while the user picks a
 * type, and START is tapped on seeing the "GPS ready" line rather than in a
 * blind wait. Released on leaving the screen (battery rule, MVP 14); while a
 * recording is running the service is listening.
 */
class MainActivity : Activity(), LocationListener {

    private lateinit var dao: ActivityDao
    private lateinit var walkButton: RadioButton
    private lateinit var runButton: RadioButton
    private lateinit var startButton: Button
    private lateinit var permissionHint: TextView
    private lateinit var gpsHint: TextView
    private lateinit var nightToggle: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Insets.apply(findViewById(R.id.root))

        dao = ActivityDao(AppDatabase.get(this))
        walkButton = findViewById(R.id.typeWalk)
        runButton = findViewById(R.id.typeRun)
        permissionHint = findViewById(R.id.permissionHint)
        gpsHint = findViewById(R.id.gpsHint)

        // The installed version is visible on screen (F-7): the question "which
        // version am I on" is asked of the screen, not the phone.
        findViewById<TextView>(R.id.versionText).text =
            getString(R.string.version_label, BuildConfig.VERSION_NAME)

        startButton = findViewById(R.id.startButton)
        startButton.setOnClickListener { onStartTapped() }
        findViewById<Button>(R.id.compassButton).setOnClickListener {
            startActivity(Intent(this, CompassActivity::class.java))
        }
        findViewById<Button>(R.id.historyButton).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        findViewById<Button>(R.id.mapsButton).setOnClickListener {
            startActivity(Intent(this, MapsActivity::class.java))
        }
        findViewById<TextView>(R.id.diagnosticsLink).setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }
        // Night mode (MVP 3.7): one line at the foot of Home cycles
        // Auto → Night → Day. The filter itself follows on every screen.
        nightToggle = findViewById(R.id.nightToggle)
        nightToggle.setOnClickListener {
            NightMode.setMode(this, NightMode.mode(this).next())
            NightMode.apply(this)
            renderNightToggle()
        }
        // The strength is set once, not in the field, so it lives behind a
        // long press (F-16): deep red is the hardest colour to focus on, and
        // which strength reads best depends on the eye.
        nightToggle.setOnLongClickListener {
            showNightStrengthDialog()
            true
        }
        renderNightToggle()
    }

    private fun renderNightToggle() {
        val mode = NightMode.mode(this)
        val modeLabel = getString(
            when (mode) {
                NightPolicy.Mode.AUTO -> R.string.night_mode_auto
                NightPolicy.Mode.ON -> R.string.night_mode_on
                NightPolicy.Mode.OFF -> R.string.night_mode_off
            }
        )
        // With the filter off the strength would be noise on the line.
        nightToggle.text =
            if (mode == NightPolicy.Mode.OFF) modeLabel
            else getString(R.string.night_mode_line, modeLabel, nightStrengthLabel())
    }

    private fun nightStrengthLabel(): String = getString(
        when (NightMode.strength(this)) {
            NightPolicy.Strength.SOFT -> R.string.night_strength_soft
            NightPolicy.Strength.MEDIUM -> R.string.night_strength_medium
            NightPolicy.Strength.STRONG -> R.string.night_strength_strong
        }
    )

    private fun showNightStrengthDialog() {
        val options = NightPolicy.Strength.values()
        val labels = options.map {
            getString(
                when (it) {
                    NightPolicy.Strength.SOFT -> R.string.night_strength_soft
                    NightPolicy.Strength.MEDIUM -> R.string.night_strength_medium
                    NightPolicy.Strength.STRONG -> R.string.night_strength_strong
                }
            )
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.night_strength_title)
            .setSingleChoiceItems(labels, options.indexOf(NightMode.strength(this))) { dialog, which ->
                NightMode.setStrength(this, options[which])
                NightMode.apply(this)
                renderNightToggle()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        recoverUnfinished()
        renderPermissionHint()
        renderStartButton()
        startGpsWarmup()
    }

    /**
     * While a recording is running the button must not read as "new
     * recording" (F-8): START becomes BACK TO RECORDING and the type choice is
     * locked. Technically no new recording was being opened anyway (the
     * service is guarded); now the screen says so too.
     */
    private fun renderStartButton() {
        val recording = TrackingService.isRecording
        startButton.text =
            getString(if (recording) R.string.return_to_recording else R.string.start)
        walkButton.isEnabled = !recording
        runButton.isEnabled = !recording
    }

    override fun onPause() {
        super.onPause()
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        locationManager.removeUpdates(this)
        gnssCallback?.let { locationManager.unregisterGnssStatusCallback(it) }
        gnssCallback = null
    }

    // Satellite visibility (F-9): "Searching for GPS" alone gives no reason —
    // satellites 0/0 = no sky, satellites 7/0 = sky, but no lock.
    private var gnssCallback: GnssStatus.Callback? = null
    private var satsSeen = 0
    private var satsUsed = 0
    private var hasGpsFix = false

    /**
     * GPS pre-warm-up (F-6): fixes never enter the recording; they only feed
     * the readiness indicator.
     */
    private fun startGpsWarmup() {
        gpsHint.visibility = View.GONE
        // The permission check is deliberately inline: lint's MissingPermission
        // flow analysis cannot see inside a helper function (the repo's pattern).
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED || TrackingService.isRecording
        ) {
            return
        }
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        if (LocationManager.GPS_PROVIDER !in locationManager.allProviders) return
        hasGpsFix = false
        satsSeen = 0
        satsUsed = 0
        renderSearchingHint()
        gpsHint.visibility = View.VISIBLE
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this)
        // Network-seeded warm-up (F-10): requesting the network provider seeds
        // the GNSS engine with a coarse position on many devices, cutting the
        // lock from minutes to seconds (field evidence: Compass/Maps visits).
        // A network fix NEVER enters the indicator or the recording —
        // onLocationChanged filters by provider.
        if (LocationManager.NETWORK_PROVIDER in locationManager.allProviders) {
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 0f, this)
        }
        val callback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                satsSeen = status.satelliteCount
                var used = 0
                for (i in 0 until status.satelliteCount) {
                    if (status.usedInFix(i)) used++
                }
                satsUsed = used
                if (!hasGpsFix) renderSearchingHint()
            }
        }
        gnssCallback = callback
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            locationManager.registerGnssStatusCallback(mainExecutor, callback)
        } else {
            @Suppress("DEPRECATION")
            locationManager.registerGnssStatusCallback(callback, Handler(Looper.getMainLooper()))
        }
    }

    /** Without a fix: searching + satellite count (0/0 = no sky; 7/0 = no lock). */
    private fun renderSearchingHint() {
        gpsHint.text =
            if (satsSeen > 0) getString(R.string.gps_searching_sats, satsUsed, satsSeen)
            else getString(R.string.gps_searching)
    }

    override fun onLocationChanged(location: Location) {
        // Only real GPS advances the indicator; a network fix is merely a seed.
        if (location.provider != LocationManager.GPS_PROVIDER) return
        hasGpsFix = true
        val acc = if (location.hasAccuracy()) location.accuracy else 0f
        gpsHint.text = when {
            acc > 0f && acc <= GpsFilter.MAX_ACCURACY_M ->
                getString(R.string.gps_ready, acc.toInt())
            acc > 0f -> getString(R.string.gps_accuracy_live, acc.toInt())
            else -> getString(R.string.gps_searching)
        }
    }

    @Deprecated("The framework still calls this; needed for API < 29")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit

    private fun onStartTapped() {
        if (TrackingService.isRecording) {
            // Back to the running recording: no permission/location dialogs needed.
            startActivity(Intent(this, RecordingActivity::class.java))
            return
        }
        if (!hasLocationPermission()) {
            if (permissionDeniedForever()) {
                // requestPermissions is silently denied here; the only way out
                // is the system settings. The user is shown a door, not a loop.
                AlertDialog.Builder(this)
                    .setMessage(R.string.permission_denied_forever)
                    .setPositiveButton(R.string.open_settings) { _, _ ->
                        startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", packageName, null)
                            )
                        )
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
                return
            }
            // The notification permission (13+) is requested together with
            // location: the foreground service's persistent notification is the
            // visible face of the recording. Denying it does not block the
            // recording; only the notification stays hidden.
            val permissions = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions += Manifest.permission.POST_NOTIFICATIONS
            }
            prefs().edit().putBoolean(KEY_LOCATION_ASKED, true).apply()
            requestPermissions(permissions.toTypedArray(), REQUEST_LOCATION)
            return
        }

        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            // A recording started with location off stays silently empty; the
            // user should learn this at the start of the walk, not at the end.
            // "Start anyway" stays available: the service waits subscribed, and
            // fixes flow once location is turned on.
            AlertDialog.Builder(this)
                .setMessage(R.string.location_disabled)
                .setPositiveButton(R.string.location_enable) { _, _ ->
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
                .setNegativeButton(R.string.start_anyway) { _, _ -> startRecording() }
                .show()
            return
        }
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (powerManager.isPowerSaveMode) {
            // Field evidence (F-12, matrix step 20): in battery saver the
            // system can stop location while the screen is off — on a 33-min
            // outing fixes flowed only while the device was awake (61 points
            // remained). The status line (F-5) said so during the recording;
            // the user should know at the start of the walk too. The mode
            // remains supported.
            AlertDialog.Builder(this)
                .setMessage(R.string.power_save_warning)
                .setPositiveButton(R.string.power_save_settings) { _, _ ->
                    startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
                }
                .setNegativeButton(R.string.start_anyway) { _, _ -> startRecording() }
                .show()
            return
        }
        startRecording()
    }

    private fun startRecording() {
        val type = if (runButton.isChecked) ActivityType.RUN else ActivityType.WALK
        startActivity(
            Intent(this, RecordingActivity::class.java)
                .putExtra(RecordingActivity.EXTRA_TYPE, type.name)
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_LOCATION) return
        if (hasLocationPermission()) {
            permissionHint.visibility = View.GONE
            startGpsWarmup()
            onStartTapped()
        } else {
            renderPermissionHint()
        }
    }

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Asked before + rationale no longer shown = the user said "don't ask again". */
    private fun permissionDeniedForever(): Boolean =
        prefs().getBoolean(KEY_LOCATION_ASKED, false) &&
            !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun renderPermissionHint() {
        if (hasLocationPermission()) {
            permissionHint.visibility = View.GONE
            return
        }
        permissionHint.setText(
            if (permissionDeniedForever()) R.string.permission_denied_forever
            else R.string.permission_needed
        )
        permissionHint.visibility = View.VISIBLE
    }

    private fun prefs() = getSharedPreferences("ui", MODE_PRIVATE)

    /**
     * A recording left unfinished by process death (end_time NULL) is pieced
     * back together from the points on disk: distance/elevation are recomputed
     * with the pure core; the duration is approximated from the point span,
     * since the pause information is lost.
     */
    private fun recoverUnfinished() {
        // If the service is recording, the "unfinished" activity is live — leave it.
        if (TrackingService.isRecording) return
        val unfinished = dao.unfinishedActivity() ?: return
        val stored = dao.pointsDetailed(unfinished.id)
        val points = stored.map { it.point }
        if (points.isEmpty()) {
            // An unfinished recording without a single point: not recovered
            // into history as noise, silently deleted.
            dao.deleteActivity(unfinished.id)
            return
        }
        // Paused legs stay out of the distance on recovery too (F-17).
        val distance = Stats.totalDistanceMeters(points, stored.map { it.afterPause })
        // Elevation is rebuilt through the same smoother the live recording
        // uses, or a recovered activity would read higher than the same outing
        // recorded without interruption (Y-3, MVP 5.4).
        val elevation = ElevationTracker()
        val smoother = AltitudeSmoother()
        dao.altitudesFor(unfinished.id).forEach { (timeMillis, altitudeM) ->
            smoother.onAltitude(timeMillis, altitudeM).forEach(elevation::onAltitude)
        }
        smoother.flush().forEach(elevation::onAltitude)
        val endTime = points.lastOrNull()?.timeMillis ?: unfinished.startTimeMillis
        dao.finishActivity(
            com.aripd.norda.core.track.ActivitySummary(
                id = unfinished.id,
                type = unfinished.type,
                startTimeMillis = unfinished.startTimeMillis,
                endTimeMillis = endTime,
                distanceM = distance,
                durationMillis = (endTime - unfinished.startTimeMillis).coerceAtLeast(0),
                elevationGainM = elevation.gainM,
                elevationLossM = elevation.lossM
            )
        )
        Toast.makeText(this, R.string.recovered_toast, Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val REQUEST_LOCATION = 1
        const val KEY_LOCATION_ASKED = "location_asked"
    }
}

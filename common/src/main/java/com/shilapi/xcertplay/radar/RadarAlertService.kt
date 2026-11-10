package com.shilapi.xcertplay.radar

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import com.shilapi.xcertplay.DiPlayActivity
import com.shilapi.xcertplay.host.R
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Speed camera alerts in the background: follows the car's own GPS, warns with tones, a spoken
 * sentence and a pop-up over any screen (CarPlay included), and keeps the camera list current.
 * It runs as a location foreground service, so Android keeps it alive while CarPlay or another
 * car app is in front. It is independent of the CarPlay session: no iPhone is needed.
 */
class RadarAlertService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var store: RadarCameraStore
    private lateinit var overlay: RadarOverlay
    private val engine = RadarAlertEngine()
    private var tones: ToneGenerator? = null
    private var speech: TextToSpeech? = null
    private var speechReady = false
    private var lastFix: DrivingFix? = null
    private var downloading = false
    private var lastDownloadAttemptMillis = 0L
    private var forceDownload = false
    private var lastOverLimitToneMillis = 0L
    private var subscribed = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onFix(location)
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
        override fun onStatusChanged(provider: String, status: Int, extras: Bundle?) = Unit
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = RadarCameraStore(this)
        overlay = RadarOverlay(this, ::markHere)
        io.execute { store.load(); main.post { Log.i(TAG, "Loaded ${store.cameras.size} saved cameras") } }
        tones = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 100) }.getOrNull()
        speech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                speech?.setLanguage(Locale.getDefault())
                speech?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                speechReady = true
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                RadarAlertSettings.setEnabled(this, false)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE -> {
                forceDownload = true
                lastFix?.let(::maybeDownload)
            }
        }
        // Started with startForegroundService: go to the foreground first, then decide whether to stay.
        if (!goForeground() || !RadarAlertSettings.enabled(this) || !hasLocationPermission()) {
            stopSelf()
            return START_NOT_STICKY
        }
        engine.alertDistanceMeters = RadarAlertSettings.distanceMeters(this).toFloat()
        subscribe()
        return START_STICKY
    }

    override fun onDestroy() {
        if (subscribed) runCatching { getSystemService(LocationManager::class.java).removeUpdates(listener) }
        subscribed = false
        main.removeCallbacksAndMessages(null)
        overlay.removeAll()
        tones?.release(); tones = null
        speech?.shutdown(); speech = null
        io.shutdown()
        super.onDestroy()
    }

    private fun hasLocationPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun goForeground(): Boolean {
        val manager = getSystemService(NotificationManager::class.java)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_RUNNING, getString(R.string.radar_channel_running), NotificationManager.IMPORTANCE_LOW),
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ALERTS, getString(R.string.radar_channel_alerts), NotificationManager.IMPORTANCE_HIGH),
            )
            Notification.Builder(this, CHANNEL_RUNNING)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setPriority(Notification.PRIORITY_LOW)
        }
        val open = PendingIntent.getActivity(
            this, 20, Intent(this, DiPlayActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 21, Intent(this, RadarAlertService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = builder
            .setSmallIcon(R.drawable.ic_diplay_notification)
            .setContentTitle(getString(R.string.settings_radar_alerts))
            .setContentText(getString(R.string.radar_running))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.radar_stop), stop).build())
            .build()
        return try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_RUNNING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(NOTIFICATION_RUNNING, notification)
            true
        } catch (error: RuntimeException) {
            // Android 12+ can refuse a location service started from the background; DiPlay starts it again when opened.
            Log.w(TAG, "Could not start the speed camera service in the foreground", error)
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun subscribe() {
        if (subscribed) return
        val manager = getSystemService(LocationManager::class.java)
        val providers = buildList {
            add(LocationManager.GPS_PROVIDER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
        }.filter { it in manager.allProviders }
        for (provider in providers) {
            try {
                manager.requestLocationUpdates(provider, 1_000L, 0f, listener, Looper.getMainLooper())
                subscribed = true
                manager.getLastKnownLocation(provider)?.let { if (lastFix == null) onFix(it) }
            } catch (error: Exception) {
                Log.w(TAG, "Could not follow location from $provider", error)
            }
        }
    }

    private fun onFix(location: Location) {
        // The fused provider can lag behind GPS; ignore a fix older than the one we have.
        if (System.currentTimeMillis() - location.time > STALE_FIX_MILLIS) return
        val fix = DrivingFix(
            latitude = location.latitude,
            longitude = location.longitude,
            speedMetersPerSecond = location.speed.takeIf { location.hasSpeed() },
            bearingDegrees = location.bearing.takeIf { location.hasBearing() },
        )
        lastFix = fix
        maybeDownload(fix)
        engine.alertDistanceMeters = RadarAlertSettings.distanceMeters(this).toFloat()
        val update = engine.update(store.cameras, fix)
        update.newAlert?.let { (ahead, level) -> warn(ahead, level) }
        overlay.updateAlertDistance(update.nearestAhead)
        if (update.overLimit && System.currentTimeMillis() - lastOverLimitToneMillis > OVER_LIMIT_TONE_EVERY_MILLIS) {
            lastOverLimitToneMillis = System.currentTimeMillis()
            tone(ToneGenerator.TONE_PROP_ACK, 2, 140L)
        }
        overlay.updateFloating(
            visible = RadarAlertSettings.floatingCard(this),
            speedKmh = fix.speedMetersPerSecond?.let { (it * 3.6f).roundToInt() },
            ahead = update.nearestAhead,
            overLimit = update.overLimit,
        )
    }

    private fun warn(ahead: CameraAhead, level: RadarAlertLevel) {
        val meters = ((ahead.distanceMeters / 10f).roundToInt() * 10).coerceAtLeast(10)
        when (level) {
            RadarAlertLevel.APPROACHING -> tone(ToneGenerator.TONE_PROP_BEEP, 3, 250L)
            RadarAlertLevel.CLOSE -> tone(ToneGenerator.TONE_PROP_BEEP2, 4, 170L)
        }
        if (level == RadarAlertLevel.APPROACHING && RadarAlertSettings.voice(this) && speechReady) {
            val limit = ahead.camera.limitKmh
            val sentence = if (limit != null) getString(R.string.radar_speak_with_limit, meters, limit)
                else getString(R.string.radar_speak, meters)
            // Spoken after the tones.
            main.postDelayed({ speech?.speak(sentence, TextToSpeech.QUEUE_FLUSH, null, "radar") }, 800L)
        }
        if (overlay.permitted()) overlay.showAlert(ahead) else notifyAlert(ahead, meters)
    }

    private fun tone(type: Int, count: Int, gapMillis: Long) {
        val generator = tones ?: return
        for (i in 0 until count) main.postDelayed({ runCatching { generator.startTone(type, 140) } }, i * gapMillis)
    }

    private fun notifyAlert(ahead: CameraAhead, meters: Int) {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ALERTS)
        else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setPriority(Notification.PRIORITY_HIGH)
        }
        val limit = ahead.camera.limitKmh
        val notification = builder
            .setSmallIcon(R.drawable.ic_diplay_notification)
            .setContentTitle(getString(R.string.radar_alert_title))
            .setContentText(
                if (limit != null) getString(R.string.radar_speak_with_limit, meters, limit)
                else getString(R.string.radar_speak, meters),
            )
            .setAutoCancel(true)
            .setTimeoutAfterCompat(ALERT_NOTIFICATION_MILLIS)
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ALERT, notification) }
    }

    private fun Notification.Builder.setTimeoutAfterCompat(millis: Long): Notification.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) setTimeoutAfter(millis) else this

    private fun maybeDownload(fix: DrivingFix) {
        if (downloading) return
        val now = System.currentTimeMillis()
        val due = forceDownload || store.needsRefresh(fix, now)
        if (!due) return
        if (!forceDownload && now - lastDownloadAttemptMillis < RETRY_MILLIS) return
        val forced = forceDownload
        forceDownload = false
        downloading = true
        lastDownloadAttemptMillis = now
        io.execute {
            val ok = runCatching { store.download(fix) }.getOrDefault(false)
            main.post {
                downloading = false
                if (forced) {
                    Toast.makeText(
                        this,
                        if (ok) getString(R.string.radar_update_done, store.cameras.size)
                        else getString(R.string.settings_radar_update_failed),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun markHere() {
        val fix = lastFix ?: run {
            Toast.makeText(this, R.string.radar_mark_no_position, Toast.LENGTH_SHORT).show()
            return
        }
        io.execute {
            val camera = runCatching { store.markHere(fix) }.getOrNull()
            main.post {
                if (camera != null) {
                    engine.markWarned(camera.id)
                    tone(ToneGenerator.TONE_PROP_ACK, 1, 0L)
                    Toast.makeText(this, R.string.radar_marked, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    companion object {
        private const val TAG = "DiPlay-Radar"
        private const val ACTION_STOP = "com.shihab.diplay.RADAR_STOP"
        private const val ACTION_UPDATE = "com.shihab.diplay.RADAR_UPDATE"
        private const val CHANNEL_RUNNING = "diplay_radar_running"
        private const val CHANNEL_ALERTS = "diplay_radar_alerts"
        private const val NOTIFICATION_RUNNING = 4_201
        private const val NOTIFICATION_ALERT = 4_202
        private const val STALE_FIX_MILLIS = 10_000L
        private const val RETRY_MILLIS = 10L * 60 * 1000
        private const val OVER_LIMIT_TONE_EVERY_MILLIS = 4_000L
        private const val ALERT_NOTIFICATION_MILLIS = 15_000L

        /** Starts the alerts when the driver has switched them on. Safe to call often. */
        fun startIfEnabled(context: Context) {
            if (!RadarAlertSettings.enabled(context)) return
            if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
            val intent = Intent(context, RadarAlertService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
            } catch (error: RuntimeException) {
                Log.w(TAG, "Could not start the speed camera service", error)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RadarAlertService::class.java))
        }

        /** Downloads the camera list again at the next position. */
        fun requestUpdate(context: Context) {
            if (!RadarAlertSettings.enabled(context)) return
            if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
            val intent = Intent(context, RadarAlertService::class.java).setAction(ACTION_UPDATE)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
            } catch (error: RuntimeException) {
                Log.w(TAG, "Could not ask for a camera update", error)
            }
        }
    }
}

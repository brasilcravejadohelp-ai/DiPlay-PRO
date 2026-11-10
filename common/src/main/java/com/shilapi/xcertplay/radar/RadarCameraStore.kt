package com.shilapi.xcertplay.radar

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Speed cameras from OpenStreetMap, saved in the app's files so alerts keep working without internet.
 * The download covers a box around the car and is refreshed once a day or when the car leaves it.
 * Cameras the driver marks are kept in a separate file that downloads never replace.
 */
internal class RadarCameraStore(context: Context) {
    private val appContext = context.applicationContext
    private val folder = File(appContext.filesDir, "radar").apply { mkdirs() }
    private val downloaded = File(folder, "cameras.csv")
    private val marked = File(folder, "marked.csv")
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Volatile var cameras: List<SpeedCamera> = emptyList()
        private set

    val lastUpdateMillis: Long get() = prefs.getLong(KEY_UPDATED, 0L)

    fun load() {
        cameras = RadarCameraCsv.parse(readOrEmpty(downloaded), markedByDriver = false, idPrefix = "osm") +
            RadarCameraCsv.parse(readOrEmpty(marked), markedByDriver = true, idPrefix = "mine")
        prefs.edit().putInt(KEY_COUNT, cameras.size).apply()
    }

    /** True when the saved list is missing, older than a day, or does not cover [fix]. */
    fun needsRefresh(fix: DrivingFix, nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (!downloaded.exists() || prefs.getLong(KEY_UPDATED, 0L) == 0L) return true
        if (nowMillis - prefs.getLong(KEY_UPDATED, 0L) > REFRESH_AGE_MILLIS) return true
        val centerLat = prefs.getFloat(KEY_CENTER_LAT, 0f).toDouble()
        val centerLon = prefs.getFloat(KEY_CENTER_LON, 0f).toDouble()
        return RadarGeo.distanceMeters(fix.latitude, fix.longitude, centerLat, centerLon) > LEAVE_AREA_METERS
    }

    /** Downloads the cameras around [fix]. Blocking: call it off the main thread. */
    fun download(fix: DrivingFix): Boolean {
        val south = fix.latitude - HALF_BOX_DEGREES
        val north = fix.latitude + HALF_BOX_DEGREES
        val west = fix.longitude - HALF_BOX_DEGREES
        val east = fix.longitude + HALF_BOX_DEGREES
        val box = String.format(Locale.US, "%.4f,%.4f,%.4f,%.4f", south, west, north, east)
        val query = "[out:csv(::lat,::lon,maxspeed,direction;false;\",\")][timeout:90];" +
            "(node[\"highway\"=\"speed_camera\"]($box);" +
            "relation[\"type\"=\"enforcement\"][\"enforcement\"~\"maxspeed|average_speed\"]($box););" +
            "out center;"
        val body = "data=" + URLEncoder.encode(query, "UTF-8")
        for (server in SERVERS) {
            val text = runCatching { post(server, body) }
                .onFailure { Log.w(TAG, "Camera download failed from $server", it) }
                .getOrNull() ?: continue
            val parsed = RadarCameraCsv.parse(text, markedByDriver = false, idPrefix = "osm")
            // An empty answer for a whole region is far more likely an error page than a road without cameras.
            if (parsed.isEmpty() && text.length < 50) continue
            val tmp = File(folder, "cameras.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(downloaded)) {
                downloaded.delete()
                if (!tmp.renameTo(downloaded)) continue
            }
            prefs.edit()
                .putLong(KEY_UPDATED, System.currentTimeMillis())
                .putFloat(KEY_CENTER_LAT, fix.latitude.toFloat())
                .putFloat(KEY_CENTER_LON, fix.longitude.toFloat())
                .apply()
            load()
            Log.i(TAG, "Downloaded ${parsed.size} cameras from $server")
            return true
        }
        return false
    }

    /** Saves a camera at [fix]; returns it so the caller can stop it from warning at once. */
    fun markHere(fix: DrivingFix): SpeedCamera {
        val direction = fix.bearingDegrees?.takeIf { fix.speedKmh >= 10f }?.toInt()
        val line = String.format(Locale.US, "%.6f,%.6f,,%s\n", fix.latitude, fix.longitude, direction?.toString() ?: "")
        marked.appendText(line)
        load()
        return cameras.last { it.markedByDriver }
    }

    private fun readOrEmpty(file: File) = if (file.exists()) runCatching { file.readText() }.getOrDefault("") else ""

    private fun post(server: String, body: String): String {
        val connection = URL(server).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20_000
            connection.readTimeout = 120_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.setRequestProperty("User-Agent", "DiPlay-RadarAlert")
            connection.outputStream.use { it.write(body.toByteArray()) }
            check(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val TAG = "DiPlay-Radar"
        private const val PREFS = "diplay_radar_cameras"
        private const val KEY_UPDATED = "updated"
        private const val KEY_COUNT = "count"
        private const val KEY_CENTER_LAT = "center_lat"
        private const val KEY_CENTER_LON = "center_lon"
        private const val HALF_BOX_DEGREES = 0.6          // about 65 km each way
        private const val LEAVE_AREA_METERS = 40_000f
        private const val REFRESH_AGE_MILLIS = 24L * 60 * 60 * 1000
        private val SERVERS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
        )

        fun savedCount(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_COUNT, 0)

        fun savedUpdateMillis(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_UPDATED, 0L)
    }
}

/** Reads `lat,lon,maxspeed,direction` lines, the Overpass CSV answer and the marked-camera file. */
internal object RadarCameraCsv {
    private val compass = mapOf(
        "N" to 0, "NNE" to 22, "NE" to 45, "ENE" to 67, "E" to 90, "ESE" to 112, "SE" to 135, "SSE" to 157,
        "S" to 180, "SSW" to 202, "SW" to 225, "WSW" to 247, "W" to 270, "WNW" to 292, "NW" to 315, "NNW" to 337,
    )

    fun parse(text: String, markedByDriver: Boolean, idPrefix: String): List<SpeedCamera> {
        val result = ArrayList<SpeedCamera>()
        text.lineSequence().forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEachIndexed
            val fields = line.split(',')
            val lat = fields.getOrNull(0)?.trim()?.toDoubleOrNull() ?: return@forEachIndexed
            val lon = fields.getOrNull(1)?.trim()?.toDoubleOrNull() ?: return@forEachIndexed
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0 || (lat == 0.0 && lon == 0.0)) return@forEachIndexed
            result += SpeedCamera(
                id = "$idPrefix-$index-${"%.5f".format(Locale.US, lat)},${"%.5f".format(Locale.US, lon)}",
                latitude = lat,
                longitude = lon,
                limitKmh = parseLimit(fields.getOrNull(2)),
                directionDegrees = parseDirection(fields.getOrNull(3)),
                markedByDriver = markedByDriver,
            )
        }
        return result
    }

    /** "60", "60 km/h" and "60;40" give 60; "40 mph" is converted; anything else is unknown. */
    fun parseLimit(raw: String?): Int? {
        val value = raw?.trim()?.trim('"') ?: return null
        val number = Regex("^\\d+").find(value)?.value?.toIntOrNull() ?: return null
        val kmh = if (value.contains("mph")) (number * 1.609).toInt() else number
        return kmh.takeIf { it in 5..200 }
    }

    fun parseDirection(raw: String?): Int? {
        val value = raw?.trim()?.trim('"')?.uppercase(Locale.US)?.takeIf { it.isNotEmpty() } ?: return null
        compass[value]?.let { return it }
        return value.split(';').first().toFloatOrNull()?.let { ((it % 360f + 360f) % 360f).toInt() }
    }
}

package com.shilapi.xcertplay.radar

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** One speed camera. [limitKmh] and [directionDegrees] are null when the source does not say. */
data class SpeedCamera(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val limitKmh: Int? = null,
    val directionDegrees: Int? = null,
    val markedByDriver: Boolean = false,
)

/** A position from the car. Speed in metres per second and bearing in degrees, when the receiver has them. */
data class DrivingFix(
    val latitude: Double,
    val longitude: Double,
    val speedMetersPerSecond: Float? = null,
    val bearingDegrees: Float? = null,
) {
    val speedKmh: Float get() = (speedMetersPerSecond ?: 0f) * 3.6f
}

enum class RadarAlertLevel { APPROACHING, CLOSE }

data class CameraAhead(val camera: SpeedCamera, val distanceMeters: Float)

data class RadarAlertUpdate(
    /** The nearest camera in front of the car, if one is within [RadarAlertEngine.LOOKAHEAD_METERS]. */
    val nearestAhead: CameraAhead?,
    /** A camera that has just crossed a warning distance; null when nothing new should sound. */
    val newAlert: Pair<CameraAhead, RadarAlertLevel>?,
    /** True while the car is faster than the limit of the camera it is approaching. */
    val overLimit: Boolean,
)

/**
 * Decides when a speed camera warning sounds. Each camera warns once when it comes within
 * [alertDistanceMeters], once more within [closeDistanceMeters], and only again after the car has
 * left it by [resetDistanceMeters]. While the car moves, only cameras in front of it count.
 */
class RadarAlertEngine(
    var alertDistanceMeters: Float = 400f,
    private val closeDistanceMeters: Float = 150f,
    private val resetMarginMeters: Float = 250f,
    private val aheadAngleDegrees: Float = 50f,
    private val minSpeedForDirectionKmh: Float = 10f,
    private val overLimitToleranceKmh: Int = 3,
) {
    private val warned = HashMap<String, RadarAlertLevel>()

    fun reset() = warned.clear()

    fun update(cameras: Collection<SpeedCamera>, fix: DrivingFix): RadarAlertUpdate {
        val resetDistance = alertDistanceMeters + resetMarginMeters
        var nearest: CameraAhead? = null
        var alert: Pair<CameraAhead, RadarAlertLevel>? = null
        for (camera in cameras) {
            // About 2.2 km of latitude: a cheap test before the full distance.
            if (abs(camera.latitude - fix.latitude) > BOX_DEGREES || abs(camera.longitude - fix.longitude) > BOX_DEGREES) {
                warned.remove(camera.id)
                continue
            }
            val distance = RadarGeo.distanceMeters(fix.latitude, fix.longitude, camera.latitude, camera.longitude)
            if (distance > resetDistance) warned.remove(camera.id)
            if (!isAhead(camera, fix)) continue
            val ahead = CameraAhead(camera, distance)
            if (distance <= LOOKAHEAD_METERS && (nearest == null || distance < nearest.distanceMeters)) nearest = ahead
            val previous = warned[camera.id]
            val level = when {
                distance <= closeDistanceMeters && previous != RadarAlertLevel.CLOSE -> RadarAlertLevel.CLOSE
                distance <= alertDistanceMeters && previous == null -> RadarAlertLevel.APPROACHING
                else -> null
            } ?: continue
            warned[camera.id] = level
            // The closest new warning wins when two start together.
            if (alert == null || distance < alert.first.distanceMeters) alert = ahead to level
        }
        val limit = nearest?.camera?.limitKmh
        val overLimit = nearest != null && limit != null && nearest.distanceMeters <= alertDistanceMeters &&
            fix.speedKmh > limit + overLimitToleranceKmh
        return RadarAlertUpdate(nearest, alert, overLimit)
    }

    /** A camera the driver just marked must not warn at once. */
    fun markWarned(cameraId: String) {
        warned[cameraId] = RadarAlertLevel.CLOSE
    }

    internal fun isAhead(camera: SpeedCamera, fix: DrivingFix): Boolean {
        val bearing = fix.bearingDegrees ?: return true
        if (fix.speedKmh < minSpeedForDirectionKmh) return true
        val toCamera = RadarGeo.bearingDegrees(fix.latitude, fix.longitude, camera.latitude, camera.longitude)
        if (RadarGeo.angleBetween(toCamera, bearing) > aheadAngleDegrees) return false
        val direction = camera.directionDegrees ?: return true
        // A camera that faces one way: accept it for that way or the opposite, since mapping varies.
        return RadarGeo.angleBetween(direction.toFloat(), bearing) <= 60f ||
            RadarGeo.angleBetween((direction + 180f) % 360f, bearing) <= 60f
    }

    companion object {
        const val LOOKAHEAD_METERS = 3_000f
        private const val BOX_DEGREES = 0.02
    }
}

internal object RadarGeo {
    private const val EARTH_RADIUS_METERS = 6_371_000.0

    private fun rad(degrees: Double) = degrees * PI / 180.0

    /** Equirectangular distance: exact enough below a few kilometres. */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val x = rad(lon2 - lon1) * cos(rad((lat1 + lat2) / 2.0))
        val y = rad(lat2 - lat1)
        return (sqrt(x * x + y * y) * EARTH_RADIUS_METERS).toFloat()
    }

    fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val y = sin(rad(lon2 - lon1)) * cos(rad(lat2))
        val x = cos(rad(lat1)) * sin(rad(lat2)) - sin(rad(lat1)) * cos(rad(lat2)) * cos(rad(lon2 - lon1))
        val degrees = atan2(y, x) * 180.0 / PI
        return ((degrees + 360.0) % 360.0).toFloat()
    }

    fun angleBetween(a: Float, b: Float): Float {
        val d = abs((a - b) % 360f)
        return if (d > 180f) 360f - d else d
    }
}

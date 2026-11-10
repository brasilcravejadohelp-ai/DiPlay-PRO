package com.shilapi.xcertplay.radar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarAlertEngineTest {
    private val lat0 = -23.6000
    private val lon0 = -46.6500
    private val metre = 1.0 / 111_320.0

    private fun north(metres: Double) = lat0 + metres * metre
    private fun fix(metresNorth: Double, kmh: Float = 56f, bearing: Float? = 0f) =
        DrivingFix(north(metresNorth), lon0, kmh / 3.6f, bearing)

    private val camera = SpeedCamera("a", north(1_000.0), lon0, limitKmh = 60)

    @Test fun warnsOnceWhenApproachingAndOnceMoreWhenClose() {
        val engine = RadarAlertEngine(alertDistanceMeters = 400f)
        assertNull(engine.update(listOf(camera), fix(0.0)).newAlert)
        val approaching = engine.update(listOf(camera), fix(610.0)).newAlert
        assertNotNull(approaching)
        assertEquals(RadarAlertLevel.APPROACHING, approaching!!.second)
        assertNull(engine.update(listOf(camera), fix(700.0)).newAlert)
        assertEquals(RadarAlertLevel.CLOSE, engine.update(listOf(camera), fix(860.0)).newAlert?.second)
        assertNull(engine.update(listOf(camera), fix(900.0)).newAlert)
    }

    @Test fun ignoresACameraBehindTheCar() {
        val engine = RadarAlertEngine(alertDistanceMeters = 400f)
        val behind = SpeedCamera("b", north(-200.0), lon0, limitKmh = 60)
        val update = engine.update(listOf(behind), fix(0.0))
        assertNull(update.newAlert)
        assertNull(update.nearestAhead)
    }

    @Test fun countsEveryNearbyCameraWhileStopped() {
        val engine = RadarAlertEngine(alertDistanceMeters = 400f)
        val behind = SpeedCamera("b", north(-200.0), lon0)
        assertNotNull(engine.update(listOf(behind), fix(0.0, kmh = 0f)).newAlert)
    }

    @Test fun warnsAgainOnlyAfterLeavingTheCamera() {
        val engine = RadarAlertEngine(alertDistanceMeters = 400f)
        engine.update(listOf(camera), fix(650.0))
        // Turned round and came back without going far: no new warning.
        assertNull(engine.update(listOf(camera), fix(650.0)).newAlert)
        engine.update(listOf(camera), fix(-500.0))
        assertNotNull(engine.update(listOf(camera), fix(650.0)).newAlert)
    }

    @Test fun reportsOverLimitNearACameraWithALimit() {
        val engine = RadarAlertEngine(alertDistanceMeters = 400f)
        assertTrue(engine.update(listOf(camera), fix(700.0, kmh = 74f)).overLimit)
        assertFalse(engine.update(listOf(camera), fix(710.0, kmh = 62f)).overLimit)
        assertFalse(engine.update(listOf(camera.copy(id = "c", limitKmh = null)), fix(700.0, kmh = 90f)).overLimit)
    }

    @Test fun aOneWayCameraFacingAcrossTheRoadDoesNotWarn() {
        val engine = RadarAlertEngine(alertDistanceMeters = 400f)
        val sideways = camera.copy(id = "s", directionDegrees = 90)
        assertNull(engine.update(listOf(sideways), fix(700.0)).newAlert)
    }

    @Test fun aMarkedCameraDoesNotWarnAtOnce() {
        val engine = RadarAlertEngine(alertDistanceMeters = 400f)
        val here = SpeedCamera("m", north(0.0), lon0, markedByDriver = true)
        engine.markWarned(here.id)
        assertNull(engine.update(listOf(here), fix(0.0, kmh = 0f)).newAlert)
    }

    @Test fun readsTheOverpassCsvAnswer() {
        val text = "-23.5919,-46.6500,60,\n-23.58,-46.64,50 km/h,N\n-23.57,-46.63,,90\nbroken line\n-23.56,-46.62,BR:urban,forward\n"
        val cameras = RadarCameraCsv.parse(text, markedByDriver = false, idPrefix = "osm")
        assertEquals(4, cameras.size)
        assertEquals(60, cameras[0].limitKmh)
        assertEquals(50, cameras[1].limitKmh)
        assertEquals(0, cameras[1].directionDegrees)
        assertNull(cameras[2].limitKmh)
        assertEquals(90, cameras[2].directionDegrees)
        assertNull(cameras[3].limitKmh)
        assertNull(cameras[3].directionDegrees)
    }

    @Test fun convertsMilesPerHour() {
        assertEquals(64, RadarCameraCsv.parseLimit("40 mph"))
        assertEquals(60, RadarCameraCsv.parseLimit("60;40"))
        assertNull(RadarCameraCsv.parseLimit("none"))
    }
}

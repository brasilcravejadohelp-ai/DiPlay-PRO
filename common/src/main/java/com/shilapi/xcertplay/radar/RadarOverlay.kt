package com.shilapi.xcertplay.radar

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The speed camera windows over every screen, CarPlay included:
 * - the alert card, large and red, at the top centre for a few seconds when a camera warns;
 * - the floating card, small and draggable, with the speed and the next camera. Holding it marks a camera.
 * Both need "display over other apps"; [permitted] tells the service to fall back to notifications.
 * Main thread only.
 */
internal class RadarOverlay(context: Context, private val onMarkHere: () -> Unit) {
    private val appContext = context.applicationContext
    private val windows = appContext.getSystemService(WindowManager::class.java)
    private val density = appContext.resources.displayMetrics.density
    private val main = Handler(Looper.getMainLooper())
    private val prefs = appContext.getSharedPreferences("diplay_radar_overlay", Context.MODE_PRIVATE)

    private var alertView: LinearLayout? = null
    private var alertDistance: TextView? = null
    private var alertSign: LimitSign? = null
    private val hideAlert = Runnable { removeAlert() }

    private var floatingView: LinearLayout? = null
    private var floatingSpeed: TextView? = null
    private var floatingNext: TextView? = null
    private var floatingSign: LimitSign? = null

    fun permitted(): Boolean = Settings.canDrawOverlays(appContext)

    private fun dp(value: Int) = (value * density).roundToInt()

    private fun params(width: Int, height: Int, touchable: Boolean) = WindowManager.LayoutParams(
        width,
        height,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT,
    )

    private fun rounded(color: Int, radiusDp: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        stroke?.let { setStroke(dp(2), it) }
    }

    private fun text(sizeSp: Float, bold: Boolean, color: Int = Color.WHITE) = TextView(appContext).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        includeFontPadding = false
    }

    // ---------------------------------------------------------------- alert card

    /** Shows or refreshes the alert card for [ahead]; it hides by itself after a few seconds. */
    fun showAlert(ahead: CameraAhead) {
        if (!permitted()) return
        val view = alertView ?: buildAlert() ?: return
        alertDistance?.text = appContext.getString(R.string.radar_alert_distance, roundedDistance(ahead.distanceMeters))
        alertSign?.limit = ahead.camera.limitKmh
        main.removeCallbacks(hideAlert)
        main.postDelayed(hideAlert, ALERT_SHOW_MILLIS)
        view.alpha = 1f
    }

    /** Keeps the distance on a shown alert card current. */
    fun updateAlertDistance(ahead: CameraAhead?) {
        if (alertView == null || ahead == null) return
        alertDistance?.text = appContext.getString(R.string.radar_alert_distance, roundedDistance(ahead.distanceMeters))
    }

    private fun buildAlert(): LinearLayout? {
        val card = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(ALERT_RED, 28, Color.WHITE)
            setPadding(dp(22), dp(16), dp(28), dp(16))
            elevation = dp(12).toFloat()
        }
        val sign = LimitSign(appContext).apply { layoutParams = LinearLayout.LayoutParams(dp(84), dp(84)) }
        val column = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), 0, 0, 0)
        }
        val title = text(26f, true).apply { text = appContext.getString(R.string.radar_alert_title) }
        val distance = text(44f, true)
        column.addView(title)
        column.addView(distance)
        card.addView(sign)
        card.addView(column)
        card.setOnClickListener { removeAlert() }
        val layout = params(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, touchable = true).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(36)
        }
        return try {
            windows.addView(card, layout)
            alertView = card; alertDistance = distance; alertSign = sign
            card
        } catch (error: RuntimeException) {
            Log.w(TAG, "Could not show the camera alert", error)
            null
        }
    }

    private fun removeAlert() {
        main.removeCallbacks(hideAlert)
        alertView?.let { runCatching { windows.removeViewImmediate(it) } }
        alertView = null; alertDistance = null; alertSign = null
    }

    // ---------------------------------------------------------------- floating card

    /** Shows the floating card with the current [speedKmh] and next camera, or hides it when [visible] is false. */
    fun updateFloating(visible: Boolean, speedKmh: Int?, ahead: CameraAhead?, overLimit: Boolean) {
        if (!visible || !permitted()) { removeFloating(); return }
        floatingView ?: buildFloating() ?: return
        floatingSpeed?.text = speedKmh?.toString() ?: "--"
        floatingSpeed?.setTextColor(if (overLimit) ALERT_RED else Color.WHITE)
        if (ahead != null) {
            floatingNext?.text = appContext.getString(R.string.radar_alert_distance_short, distanceLabel(ahead.distanceMeters))
            floatingSign?.limit = ahead.camera.limitKmh
            floatingSign?.visibility = View.VISIBLE
        } else {
            floatingNext?.text = appContext.getString(R.string.radar_no_camera_ahead)
            floatingSign?.visibility = View.GONE
        }
        floatingView?.background = rounded(
            if (ahead != null && ahead.distanceMeters <= RadarAlertSettings.distanceMeters(appContext)) WARNING_DARK else CARD_DARK,
            22,
            if (overLimit) ALERT_RED else CARD_BORDER,
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildFloating(): LinearLayout? {
        val card = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            elevation = dp(8).toFloat()
        }
        val speedColumn = LinearLayout(appContext).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val speed = text(34f, true)
        val unit = text(12f, false, MUTED).apply { text = "km/h" }
        speedColumn.addView(speed); speedColumn.addView(unit)
        val sign = LimitSign(appContext).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(14) }
        }
        val next = text(18f, true).apply { setPadding(dp(10), 0, 0, 0) }
        card.addView(speedColumn); card.addView(sign); card.addView(next)

        val layout = params(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, touchable = true).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(KEY_X, dp(24))
            y = prefs.getInt(KEY_Y, dp(120))
        }

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        var dragged = false; var held = false
        val slop = dp(10)
        val hold = Runnable { held = true; onMarkHere() }
        card.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY; startX = layout.x; startY = layout.y
                    dragged = false; held = false
                    main.postDelayed(hold, HOLD_TO_MARK_MILLIS)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX; val dy = event.rawY - downY
                    if (!dragged && (abs(dx) > slop || abs(dy) > slop)) { dragged = true; main.removeCallbacks(hold) }
                    if (dragged && !held) {
                        layout.x = (startX + dx).roundToInt().coerceAtLeast(0)
                        layout.y = (startY + dy).roundToInt().coerceAtLeast(0)
                        runCatching { windows.updateViewLayout(view, layout) }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(hold)
                    if (dragged) prefs.edit().putInt(KEY_X, layout.x).putInt(KEY_Y, layout.y).apply()
                }
            }
            true
        }
        return try {
            windows.addView(card, layout)
            floatingView = card; floatingSpeed = speed; floatingNext = next; floatingSign = sign
            card
        } catch (error: RuntimeException) {
            Log.w(TAG, "Could not show the floating camera card", error)
            null
        }
    }

    private fun removeFloating() {
        floatingView?.let { runCatching { windows.removeViewImmediate(it) } }
        floatingView = null; floatingSpeed = null; floatingNext = null; floatingSign = null
    }

    fun removeAll() {
        removeAlert()
        removeFloating()
    }

    private fun roundedDistance(meters: Float) = ((meters / 10f).roundToInt() * 10).coerceAtLeast(10)

    private fun distanceLabel(meters: Float): String =
        if (meters >= 1000f) String.format(java.util.Locale.getDefault(), "%.1f km", meters / 1000f)
        else "${roundedDistance(meters)} m"

    /** A round speed-limit sign; a camera without a known limit shows the camera glyph. */
    private class LimitSign(context: Context) : View(context) {
        var limit: Int? = null
            set(value) { field = value; invalidate() }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ALERT_RED }
        private val face = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(17, 17, 17); typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        }

        override fun onDraw(canvas: Canvas) {
            val r = minOf(width, height) / 2f
            val cx = width / 2f; val cy = height / 2f
            canvas.drawCircle(cx, cy, r, face)
            canvas.drawCircle(cx, cy, r * 0.97f, ring)
            canvas.drawCircle(cx, cy, r * 0.80f, face)
            val value = limit?.toString() ?: "📷"
            label.textSize = r * if (value.length >= 3) 0.72f else 0.9f
            canvas.drawText(value, cx, cy - (label.descent() + label.ascent()) / 2f, label)
        }
    }

    companion object {
        private const val TAG = "DiPlay-Radar"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"
        private const val ALERT_SHOW_MILLIS = 6_000L
        private const val HOLD_TO_MARK_MILLIS = 1_200L
    }
}

private val ALERT_RED = Color.rgb(230, 40, 40)
private val WARNING_DARK = Color.argb(235, 90, 20, 20)
private val CARD_DARK = Color.argb(225, 16, 20, 27)
private val CARD_BORDER = Color.argb(255, 50, 62, 78)
private val MUTED = Color.rgb(150, 162, 178)

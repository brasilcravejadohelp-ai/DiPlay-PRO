package com.shilapi.xcertplay.radar

import android.content.Context

/** The driver's speed camera choices. */
internal object RadarAlertSettings {
    private const val PREFS = "diplay_radar_alerts"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_DISTANCE = "distance_m"
    private const val KEY_VOICE = "voice"
    private const val KEY_FLOATING = "floating_card"

    val distanceChoicesMeters = listOf(200, 300, 400, 600, 800)
    const val DEFAULT_DISTANCE_METERS = 400

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(context: Context) = prefs(context).getBoolean(KEY_ENABLED, false)
    fun setEnabled(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()

    fun distanceMeters(context: Context) = prefs(context).getInt(KEY_DISTANCE, DEFAULT_DISTANCE_METERS)
        .takeIf { it in distanceChoicesMeters } ?: DEFAULT_DISTANCE_METERS
    fun setDistanceMeters(context: Context, value: Int) = prefs(context).edit().putInt(KEY_DISTANCE, value).apply()

    fun voice(context: Context) = prefs(context).getBoolean(KEY_VOICE, true)
    fun setVoice(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_VOICE, value).apply()

    fun floatingCard(context: Context) = prefs(context).getBoolean(KEY_FLOATING, true)
    fun setFloatingCard(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_FLOATING, value).apply()
}

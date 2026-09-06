package com.januarius.gpxstats.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Display formatters shared across the UI. */
object Format {

    private val dateFmt: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.getDefault())

    fun distance(meters: Double): String = when {
        meters >= 1_000 -> String.format(Locale.getDefault(), "%.2f km", meters / 1_000.0)
        else -> String.format(Locale.getDefault(), "%.0f m", meters)
    }

    fun elevation(meters: Double): String =
        String.format(Locale.getDefault(), "%.0f m", meters)

    /** Absolute altitude; null renders as a dash. */
    fun altitude(meters: Double?): String =
        if (meters == null) "–" else String.format(Locale.getDefault(), "%.0f m", meters)

    /** Speed in km/h; a non-positive value renders as a dash. */
    fun speed(metersPerSecond: Double): String =
        if (metersPerSecond <= 0.0) "–"
        else String.format(Locale.getDefault(), "%.1f km/h", metersPerSecond * 3.6)

    /** Average speed from a distance and a time budget (moving time, else elapsed). */
    fun avgSpeed(distanceMeters: Double, movingSeconds: Long, durationSeconds: Long): String {
        val secs = when {
            movingSeconds > 0 -> movingSeconds
            durationSeconds > 0 -> durationSeconds
            else -> return "–"
        }
        return speed(distanceMeters / secs)
    }

    fun duration(seconds: Long): String {
        if (seconds <= 0) return "0:00"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.getDefault(), "%d:%02d", m, s)
        }
    }

    fun durationLong(seconds: Long): String {
        if (seconds <= 0) return "0 min"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        return when {
            h > 0 -> String.format(Locale.getDefault(), "%dh %02dm", h, m)
            else -> String.format(Locale.getDefault(), "%d min", m)
        }
    }

    fun date(millis: Long?): String {
        if (millis == null) return "no date"
        return dateFmt.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
    }
}

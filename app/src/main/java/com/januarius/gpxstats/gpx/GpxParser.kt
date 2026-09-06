package com.januarius.gpxstats.gpx

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Tunables for the elevation gain / loss computation, surfaced on the Settings screen.
 * Defaults are [GpxParser.DEFAULT_ELEVATION_MEDIAN_WINDOW] and
 * [GpxParser.DEFAULT_ELEVATION_THRESHOLD_M].
 */
data class ElevationOptions(
    val medianWindow: Int = GpxParser.DEFAULT_ELEVATION_MEDIAN_WINDOW,
    val thresholdMeters: Double = GpxParser.DEFAULT_ELEVATION_THRESHOLD_M
)

/** Aggregated metrics computed from a GPX file. */
data class GpxSummary(
    val distanceMeters: Double,
    val durationSeconds: Long,
    val movingSeconds: Long,
    /** Cumulative climb, metres, after smoothing + hysteresis (see [GpxParser.elevationGainLoss]). */
    val elevationGainMeters: Double,
    /** Cumulative descent, metres, same method as the gain. */
    val elevationLossMeters: Double,
    /** Fastest plausible segment speed, m/s. 0 when the file has no usable timestamps. */
    val maxSpeedMps: Double,
    /** Highest `<ele>` value seen, metres. null when the file has no elevation data. */
    val maxElevationMeters: Double?,
    val startTimeMillis: Long?,
    val pointCount: Int
)

/**
 * Streaming GPX reader. Walks `<trkpt>` / `<rtept>` / `<wpt>` points and accumulates
 * distance (haversine), elapsed and moving time, max speed and max altitude. Elevation
 * gain / loss are computed after the walk from the collected `<ele>` series: an
 * n-point median filter to drop spikes, then a hysteresis pass that only commits a
 * climb or descent once the profile reverses by more than a threshold.
 */
object GpxParser {

    /** Default width of the median filter applied to the elevation series. */
    const val DEFAULT_ELEVATION_MEDIAN_WINDOW = 5

    /** Default reversal a climb/descent must exceed before it counts, metres. */
    const val DEFAULT_ELEVATION_THRESHOLD_M = 10.0

    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Segments slower than this are treated as a pause and excluded from moving time. */
    private const val MOVING_SPEED_THRESHOLD_MPS = 0.6

    /** Segment speeds above this (~198 km/h) are GPS jumps and are ignored for max speed. */
    private const val MAX_PLAUSIBLE_SPEED_MPS = 55.0

    /** Convenience overload taking a bundled [ElevationOptions]. */
    fun parse(input: InputStream, elevation: ElevationOptions): GpxSummary =
        parse(input, elevation.medianWindow, elevation.thresholdMeters)

    /**
     * @param medianWindow width of the median filter over the raw `<ele>` series
     *        (<= 1 disables smoothing). Default [DEFAULT_ELEVATION_MEDIAN_WINDOW].
     * @param elevationThresholdMeters minimum profile reversal that counts as a real
     *        climb or descent. Default [DEFAULT_ELEVATION_THRESHOLD_M].
     */
    fun parse(
        input: InputStream,
        medianWindow: Int = DEFAULT_ELEVATION_MEDIAN_WINDOW,
        elevationThresholdMeters: Double = DEFAULT_ELEVATION_THRESHOLD_M
    ): GpxSummary {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        var curLat: Double? = null
        var curLon: Double? = null
        var curEle: Double? = null
        var curTime: Long? = null

        var prevLat: Double? = null
        var prevLon: Double? = null
        var prevTime: Long? = null

        var totalDistance = 0.0
        var movingSeconds = 0L
        var maxSpeed = 0.0
        var maxEle: Double? = null
        var startTime: Long? = null
        var endTime: Long? = null
        var pointCount = 0
        val elevations = ArrayList<Double>()

        var inPoint = false
        val text = StringBuilder()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name.lowercase()) {
                        "trkpt", "rtept", "wpt" -> {
                            inPoint = true
                            curLat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                            curLon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                            curEle = null
                            curTime = null
                        }
                    }
                    text.setLength(0)
                }

                XmlPullParser.TEXT -> text.append(parser.text)

                XmlPullParser.END_TAG -> {
                    when (parser.name.lowercase()) {
                        "ele" -> if (inPoint) curEle = text.toString().trim().toDoubleOrNull()
                        "time" -> if (inPoint) curTime = parseTime(text.toString().trim())
                        "trkpt", "rtept", "wpt" -> {
                            val lat = curLat
                            val lon = curLon
                            if (lat != null && lon != null) {
                                pointCount++

                                val pLat = prevLat
                                val pLon = prevLon
                                if (pLat != null && pLon != null) {
                                    val d = haversine(pLat, pLon, lat, lon)
                                    totalDistance += d

                                    val pt = prevTime
                                    val ct = curTime
                                    if (pt != null && ct != null && ct > pt) {
                                        val dtSec = (ct - pt) / 1000.0
                                        if (dtSec > 0) {
                                            val speed = d / dtSec
                                            if (speed >= MOVING_SPEED_THRESHOLD_MPS) {
                                                movingSeconds += dtSec.toLong()
                                            }
                                            if (speed <= MAX_PLAUSIBLE_SPEED_MPS) {
                                                maxSpeed = max(maxSpeed, speed)
                                            }
                                        }
                                    }
                                }

                                val cEle = curEle
                                if (cEle != null) {
                                    elevations.add(cEle)
                                    val prevMax = maxEle
                                    maxEle = if (prevMax == null) cEle else max(prevMax, cEle)
                                }

                                curTime?.let {
                                    if (startTime == null) startTime = it
                                    endTime = it
                                }

                                prevLat = lat
                                prevLon = lon
                                if (curTime != null) prevTime = curTime
                            }
                            inPoint = false
                        }
                    }
                    text.setLength(0)
                }
            }
            event = parser.next()
        }

        val begin = startTime
        val end = endTime
        val elapsed =
            if (begin != null && end != null && end > begin) (end - begin) / 1000 else 0L

        val (gain, loss) = elevationGainLoss(elevations, medianWindow, elevationThresholdMeters)

        return GpxSummary(
            distanceMeters = totalDistance,
            durationSeconds = elapsed,
            movingSeconds = if (movingSeconds in 1 until elapsed) movingSeconds else elapsed,
            elevationGainMeters = gain,
            elevationLossMeters = loss,
            maxSpeedMps = maxSpeed,
            maxElevationMeters = maxEle,
            startTimeMillis = begin,
            pointCount = pointCount
        )
    }

    /**
     * Realistic cumulative elevation gain / loss from a sequence of raw `<ele>` readings.
     *
     * 1. an [medianWindow]-point median filter removes one-off spikes;
     * 2. a hysteresis walk tracks the last confirmed turning point and only commits a
     *    climb or descent once the profile reverses by at least [thresholdMeters], so
     *    sub-threshold GPS wander is ignored.
     *
     * e.g. `300 → 500 → 400 → 700` with a 10 m threshold yields gain 500, loss 100.
     */
    internal fun elevationGainLoss(
        elevations: List<Double>,
        medianWindow: Int,
        thresholdMeters: Double
    ): Pair<Double, Double> {
        val smoothed = medianFilter(elevations, medianWindow)
        if (smoothed.size < 2) return 0.0 to 0.0

        var lastPivot = smoothed.first()
        var ext = smoothed.first()
        var trend = 0
        var gain = 0.0
        var loss = 0.0

        for (i in 1 until smoothed.size) {
            val e = smoothed[i]
            when (trend) {
                1 -> when {
                    e >= ext -> ext = e
                    ext - e >= thresholdMeters -> {
                        gain += ext - lastPivot
                        lastPivot = ext
                        ext = e
                        trend = -1
                    }
                }

                -1 -> when {
                    e <= ext -> ext = e
                    e - ext >= thresholdMeters -> {
                        loss += lastPivot - ext
                        lastPivot = ext
                        ext = e
                        trend = 1
                    }
                }

                else -> when {
                    e - lastPivot >= thresholdMeters -> {
                        trend = 1
                        ext = e
                    }

                    lastPivot - e >= thresholdMeters -> {
                        trend = -1
                        ext = e
                    }

                    else -> ext = e
                }
            }
        }

        // Flush the final, unreversed leg.
        if (ext > lastPivot) gain += ext - lastPivot
        if (ext < lastPivot) loss += lastPivot - ext

        return gain to loss
    }

    /**
     * Sliding-window median. The window shrinks towards each end so the first and last
     * samples are still filtered. `window <= 1` (or a series of <= 2 points) is a no-op.
     */
    private fun medianFilter(values: List<Double>, window: Int): List<Double> {
        if (window <= 1 || values.size <= 2) return values
        val half = window / 2
        return values.indices.map { i ->
            val from = maxOf(0, i - half)
            val to = minOf(values.size - 1, i + half)
            val slice = values.subList(from, to + 1).sorted()
            val n = slice.size
            if (n % 2 == 1) slice[n / 2] else (slice[n / 2 - 1] + slice[n / 2]) / 2.0
        }
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return EARTH_RADIUS_M * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun parseTime(value: String): Long? {
        if (value.isEmpty()) return null
        return runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
            .recoverCatching { Instant.parse(value).toEpochMilli() }
            .getOrNull()
    }
}

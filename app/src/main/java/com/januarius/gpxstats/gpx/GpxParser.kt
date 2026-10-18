package com.januarius.gpxstats.gpx

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
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

/** One raw sample from the GPX: a `<trkpt>`, `<rtept>`, or standalone `<wpt>`. */
internal data class GpxPoint(val lat: Double, val lon: Double, val ele: Double?, val time: Long?)

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
    val pointCount: Int,
    /** Downsampled distance/elevation/position series backing the flythrough screen. */
    val elevationProfile: List<ProfilePoint> = emptyList()
)

/**
 * One sampled point of a track's distance/elevation/position profile, used to drive the
 * elevation-profile flythrough (see `Charts.kt`'s `ElevationProfileChart` /
 * `RoutePathMinimap`). [elevationMeters] is the point's own raw reading (null if the
 * file had none there) — downsampled, not smoothed; this is a visual aid, not another
 * source of the gain/loss numbers in [GpxSummary].
 */
data class ProfilePoint(
    val distanceMeters: Double,
    val elevationMeters: Double?,
    val lat: Double,
    val lon: Double,
    /** Epoch millis of this point, or null if the file had no `<time>` there. */
    val timeMillis: Long? = null
)

/** One interpolated instant along a [ProfilePoint] series at a given distance fraction. */
data class ProfileSample(
    val distanceMeters: Double,
    val elevationMeters: Double?,
    val lat: Double,
    val lon: Double,
    val gradePercent: Double?,
    /** Local (segment-average) ground speed at this instant; null if either side of the
     *  interpolated segment is missing a timestamp. */
    val speedMps: Double? = null
)

/**
 * Streaming GPX reader. Walks `<trkpt>` / `<rtept>` points and accumulates distance
 * (haversine), elapsed and moving time, max speed and max altitude. Standalone `<wpt>`
 * elements (point-of-interest markers some apps — e.g. OpenTracks "indicators" — sprinkle
 * into the file, unrelated in time and space to the recorded path) are collected
 * separately and only used as a fallback for files that have no real track at all; see
 * [GpxPoint] and [selectPoints]. Elevation gain / loss are computed after the walk from
 * the collected `<ele>` series: an n-point median filter to drop spikes, then a
 * hysteresis pass that only commits a climb or descent once the profile reverses by more
 * than a threshold.
 */
object GpxParser {

    /** Default width of the median filter applied to the elevation series. */
    const val DEFAULT_ELEVATION_MEDIAN_WINDOW = 5

    /** Default reversal a climb/descent must exceed before it counts, metres. */
    const val DEFAULT_ELEVATION_THRESHOLD_M = 10.0

    /** Default cap on points kept in a track's stored [ProfilePoint] series. */
    const val DEFAULT_PROFILE_MAX_SAMPLES = 300

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
        var curTag: String? = null

        val trackPoints = ArrayList<GpxPoint>()
        val waypoints = ArrayList<GpxPoint>()

        var inPoint = false
        val text = StringBuilder()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name.lowercase()) {
                        "trkpt", "rtept", "wpt" -> {
                            inPoint = true
                            curTag = parser.name.lowercase()
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
                                val point = GpxPoint(lat, lon, curEle, curTime)
                                if (curTag == "wpt") waypoints.add(point) else trackPoints.add(point)
                            }
                            inPoint = false
                            curTag = null
                        }
                    }
                    text.setLength(0)
                }
            }
            event = parser.next()
        }

        return summarize(selectPoints(trackPoints, waypoints), medianWindow, elevationThresholdMeters)
    }

    /**
     * `<trkpt>`/`<rtept>` are the real path and always win when present. Standalone
     * `<wpt>` markers are only used as a last resort, for the rare file that is nothing
     * but a list of waypoints (no `<trk>`/`<rte>` at all) — never mixed in alongside a
     * real track, since they can land anywhere in time and space relative to it.
     */
    internal fun selectPoints(trackPoints: List<GpxPoint>, waypoints: List<GpxPoint>): List<GpxPoint> =
        trackPoints.ifEmpty { waypoints }

    /** Turns an ordered point sequence into a [GpxSummary]: distance, time, speed, elevation. */
    internal fun summarize(
        points: List<GpxPoint>,
        medianWindow: Int,
        elevationThresholdMeters: Double
    ): GpxSummary {
        var totalDistance = 0.0
        var movingSeconds = 0L
        var maxSpeed = 0.0
        var maxEle: Double? = null
        var startTime: Long? = null
        var endTime: Long? = null
        val elevations = ArrayList<Double>()

        var prevLat: Double? = null
        var prevLon: Double? = null
        var prevTime: Long? = null

        for (point in points) {
            val pLat = prevLat
            val pLon = prevLon
            if (pLat != null && pLon != null) {
                val d = haversine(pLat, pLon, point.lat, point.lon)
                totalDistance += d

                val pt = prevTime
                val ct = point.time
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

            val cEle = point.ele
            if (cEle != null) {
                elevations.add(cEle)
                val prevMax = maxEle
                maxEle = if (prevMax == null) cEle else max(prevMax, cEle)
            }

            point.time?.let {
                if (startTime == null) startTime = it
                endTime = it
            }

            prevLat = point.lat
            prevLon = point.lon
            if (point.time != null) prevTime = point.time
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
            pointCount = points.size,
            elevationProfile = buildElevationProfile(points)
        )
    }

    /**
     * Downsamples [points] (the same list [summarize] uses) to at most [maxSamples]
     * entries, evenly spaced by index, each paired with its cumulative distance from the
     * start. Backs the elevation-profile flythrough — a local-only animated replay of the
     * route, with no map tiles or network involved.
     */
    internal fun buildElevationProfile(
        points: List<GpxPoint>,
        maxSamples: Int = DEFAULT_PROFILE_MAX_SAMPLES
    ): List<ProfilePoint> {
        if (points.isEmpty()) return emptyList()

        val cumulative = DoubleArray(points.size)
        for (i in 1 until points.size) {
            cumulative[i] = cumulative[i - 1] +
                haversine(points[i - 1].lat, points[i - 1].lon, points[i].lat, points[i].lon)
        }

        val sampleCount = minOf(maxSamples, points.size).coerceAtLeast(1)
        if (sampleCount == 1) {
            val p = points[0]
            return listOf(ProfilePoint(cumulative[0], p.ele, p.lat, p.lon, p.time))
        }

        val step = (points.size - 1).toDouble() / (sampleCount - 1)
        return (0 until sampleCount).map { i ->
            val idx = (i * step).roundToInt().coerceIn(0, points.size - 1)
            val p = points[idx]
            ProfilePoint(cumulative[idx], p.ele, p.lat, p.lon, p.time)
        }
    }

    /**
     * Compact serialization for `Track.elevationProfile`: points separated by `;`, fields
     * (distance, elevation, lat, lon, time) separated by `,` — elevation/time left blank
     * when null. Always formatted with [Locale.ROOT] so a device locale that uses `,` as
     * a decimal separator can't corrupt the round trip.
     */
    internal fun encodeProfile(profile: List<ProfilePoint>): String =
        profile.joinToString(";") { p ->
            val ele = p.elevationMeters?.let { "%.1f".format(Locale.ROOT, it) } ?: ""
            val time = p.timeMillis?.toString() ?: ""
            "%.1f,%s,%.6f,%.6f,%s".format(Locale.ROOT, p.distanceMeters, ele, p.lat, p.lon, time)
        }

    /**
     * Inverse of [encodeProfile]. Malformed segments are dropped rather than failing.
     * Also accepts the pre-speed 4-field format (distance, elevation, lat, lon, no time)
     * stored by earlier app versions — those decode fine, just with [ProfilePoint.
     * timeMillis] null (so [sampleProfile] can't derive a speed there) until the track is
     * next re-imported or re-synced.
     */
    internal fun decodeProfile(encoded: String?): List<ProfilePoint> {
        if (encoded.isNullOrBlank()) return emptyList()
        return encoded.split(";").mapNotNull { segment ->
            val parts = segment.split(",")
            if (parts.size != 4 && parts.size != 5) return@mapNotNull null
            val distance = parts[0].toDoubleOrNull() ?: return@mapNotNull null
            val lat = parts[2].toDoubleOrNull() ?: return@mapNotNull null
            val lon = parts[3].toDoubleOrNull() ?: return@mapNotNull null
            val time = parts.getOrNull(4)?.toLongOrNull()
            ProfilePoint(distance, parts[1].toDoubleOrNull(), lat, lon, time)
        }
    }

    /**
     * Interpolated instant along [profile] at [fraction] (0f..1f) of its total distance —
     * the flythrough's marker position and live readout. [profile] must be ordered by
     * non-decreasing [ProfilePoint.distanceMeters] (as [buildElevationProfile] produces).
     */
    internal fun sampleProfile(profile: List<ProfilePoint>, fraction: Float): ProfileSample? {
        if (profile.isEmpty()) return null
        if (profile.size == 1) {
            val p = profile[0]
            return ProfileSample(p.distanceMeters, p.elevationMeters, p.lat, p.lon, null, null)
        }

        val totalDistance = profile.last().distanceMeters
        val targetDistance = fraction.toDouble().coerceIn(0.0, 1.0) * totalDistance

        var hi = 1
        while (hi < profile.lastIndex && profile[hi].distanceMeters < targetDistance) hi++
        val a = profile[hi - 1]
        val b = profile[hi]

        val span = b.distanceMeters - a.distanceMeters
        val t = if (span > 0.0) ((targetDistance - a.distanceMeters) / span).coerceIn(0.0, 1.0) else 0.0

        val elevation = when {
            a.elevationMeters != null && b.elevationMeters != null ->
                a.elevationMeters + (b.elevationMeters - a.elevationMeters) * t
            else -> a.elevationMeters ?: b.elevationMeters
        }
        val grade = if (a.elevationMeters != null && b.elevationMeters != null && span > 0.0) {
            (b.elevationMeters - a.elevationMeters) / span * 100.0
        } else {
            null
        }

        val aTime = a.timeMillis
        val bTime = b.timeMillis
        val speed = if (aTime != null && bTime != null && bTime > aTime) {
            span / ((bTime - aTime) / 1000.0)
        } else {
            null
        }

        return ProfileSample(
            distanceMeters = targetDistance,
            elevationMeters = elevation,
            lat = a.lat + (b.lat - a.lat) * t,
            lon = a.lon + (b.lon - a.lon) * t,
            gradePercent = grade,
            speedMps = speed
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

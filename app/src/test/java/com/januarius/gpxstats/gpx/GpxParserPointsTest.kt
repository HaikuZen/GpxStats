package com.januarius.gpxstats.gpx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pure-JVM tests for point selection ([GpxParser.selectPoints]) and aggregation
 * ([GpxParser.summarize]) — the part of the parser that doesn't touch `android.util.Xml`.
 *
 * Regression coverage for a real bug: a GPX file (OpenTracks export) had three
 * standalone `<wpt>` "indicator" markers, timestamped and located well away from the
 * track, sitting before the `<trk>` in the file. The old parser folded them into the
 * same point stream as the `<trkpt>`s, which (a) added three bogus "teleport" distance
 * segments and (b) made the *first waypoint's* time look like the ride's start time —
 * for this file that pushed the recorded duration from ~4:50 down to ~3:44 and inflated
 * distance from ~93.6 km to ~146 km.
 */
class GpxParserPointsTest {

    private val window = GpxParser.DEFAULT_ELEVATION_MEDIAN_WINDOW
    private val threshold = GpxParser.DEFAULT_ELEVATION_THRESHOLD_M

    private fun point(lat: Double, lon: Double, timeOffsetSeconds: Long) =
        GpxPoint(lat, lon, ele = null, time = BASE_TIME_MILLIS + timeOffsetSeconds * 1000)

    @Test
    fun `track points win over waypoints when both are present`() {
        val trackPoints = listOf(point(45.0, 8.0, 0), point(45.01, 8.0, 60))
        val waypoints = listOf(point(40.0, 7.0, 999_999))
        assertSame(trackPoints, GpxParser.selectPoints(trackPoints, waypoints))
    }

    @Test
    fun `waypoints are used only when there are no track points`() {
        val waypoints = listOf(point(45.0, 8.0, 0), point(45.01, 8.0, 60))
        assertSame(waypoints, GpxParser.selectPoints(emptyList(), waypoints))
    }

    @Test
    fun `stray waypoints never pollute distance or start time when a real track exists`() {
        // Three waypoints "elsewhere", timestamped *after* the track's own points.
        val waypoints = listOf(
            point(40.0, 7.0, 10_000),
            point(40.5, 7.5, 10_500),
            point(41.0, 8.0, 11_000)
        )
        // A short, simple out-and-back track: ~0 -> ~1.11 km north -> back to start.
        val trackPoints = listOf(
            point(45.0, 8.0, 0),
            point(45.01, 8.0, 300),
            point(45.0, 8.0, 600)
        )

        val selected = GpxParser.selectPoints(trackPoints, waypoints)
        val summary = GpxParser.summarize(selected, window, threshold)

        // Roughly 2x the ~1.11 km/degree-of-latitude leg, not inflated by the waypoints.
        assertEquals(2 * 1_112.0, summary.distanceMeters, 50.0)
        assertEquals(600L, summary.durationSeconds)
        assertEquals(BASE_TIME_MILLIS, summary.startTimeMillis)
        assertEquals(3, summary.pointCount)
    }

    @Test
    fun `empty point list summarizes to zero, not a crash`() {
        val summary = GpxParser.summarize(emptyList(), window, threshold)
        assertEquals(0.0, summary.distanceMeters, 0.0)
        assertEquals(0L, summary.durationSeconds)
        assertEquals(null, summary.startTimeMillis)
        assertEquals(0, summary.pointCount)
    }

    private companion object {
        const val BASE_TIME_MILLIS = 1_790_000_000_000L
    }
}

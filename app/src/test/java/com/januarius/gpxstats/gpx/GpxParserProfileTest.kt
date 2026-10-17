package com.januarius.gpxstats.gpx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the elevation-profile flythrough support: downsampling
 * ([GpxParser.buildElevationProfile]), the encode/decode round trip used for
 * `Track.elevationProfile`, and the playhead interpolation ([GpxParser.sampleProfile]).
 */
class GpxParserProfileTest {

    private fun point(lat: Double, lon: Double, ele: Double?) = GpxPoint(lat, lon, ele, time = null)

    @Test
    fun `profile keeps every point when under the sample cap`() {
        val points = listOf(point(45.0, 8.0, 100.0), point(45.01, 8.0, 150.0), point(45.0, 8.0, 100.0))
        val profile = GpxParser.buildElevationProfile(points, maxSamples = 10)
        assertEquals(3, profile.size)
        assertEquals(0.0, profile[0].distanceMeters, 0.001)
        assertTrue(profile[1].distanceMeters > 0.0)
        assertEquals(100.0, profile[0].elevationMeters)
        assertEquals(150.0, profile[1].elevationMeters)
    }

    @Test
    fun `profile is downsampled to the cap, keeping first and last point`() {
        val points = (0..999).map { point(45.0 + it * 0.0001, 8.0, it.toDouble()) }
        val profile = GpxParser.buildElevationProfile(points, maxSamples = 50)
        assertEquals(50, profile.size)
        assertEquals(points.first().ele, profile.first().elevationMeters)
        assertEquals(points.last().ele, profile.last().elevationMeters)
        // Cumulative distance never goes backwards.
        for (i in 1 until profile.size) {
            assertTrue(profile[i].distanceMeters >= profile[i - 1].distanceMeters)
        }
    }

    @Test
    fun `empty point list has no profile`() {
        assertEquals(emptyList<ProfilePoint>(), GpxParser.buildElevationProfile(emptyList()))
    }

    @Test
    fun `encode decode round trip preserves values, including a null elevation`() {
        val original = listOf(
            ProfilePoint(0.0, 100.0, 45.0, 8.0),
            ProfilePoint(123.456, null, 45.001, 8.001),
            ProfilePoint(500.0, 142.25, 45.002, 8.002)
        )
        val decoded = GpxParser.decodeProfile(GpxParser.encodeProfile(original))
        assertEquals(original.size, decoded.size)
        original.zip(decoded).forEach { (expected, actual) ->
            assertEquals(expected.distanceMeters, actual.distanceMeters, 0.1)
            if (expected.elevationMeters == null) {
                assertNull(actual.elevationMeters)
            } else {
                assertEquals(expected.elevationMeters, actual.elevationMeters!!, 0.1)
            }
            assertEquals(expected.lat, actual.lat, 0.00001)
            assertEquals(expected.lon, actual.lon, 0.00001)
        }
    }

    @Test
    fun `decode is forgiving of blank or malformed input`() {
        assertEquals(emptyList<ProfilePoint>(), GpxParser.decodeProfile(null))
        assertEquals(emptyList<ProfilePoint>(), GpxParser.decodeProfile(""))
        assertEquals(emptyList<ProfilePoint>(), GpxParser.decodeProfile("not,a,valid"))
    }

    @Test
    fun `sampleProfile interpolates midway between two points`() {
        val profile = listOf(
            ProfilePoint(0.0, 100.0, 45.0, 8.0),
            ProfilePoint(1000.0, 200.0, 45.01, 8.0)
        )
        val sample = GpxParser.sampleProfile(profile, 0.5f)
        assertEquals(500.0, sample!!.distanceMeters, 0.01)
        assertEquals(150.0, sample.elevationMeters!!, 0.01)
        assertEquals(10.0, sample.gradePercent!!, 0.01) // 100 m rise over 1000 m run
    }

    @Test
    fun `sampleProfile at the endpoints returns the endpoints`() {
        val profile = listOf(
            ProfilePoint(0.0, 100.0, 45.0, 8.0),
            ProfilePoint(1000.0, 200.0, 45.01, 8.0)
        )
        assertEquals(0.0, GpxParser.sampleProfile(profile, 0f)!!.distanceMeters, 0.01)
        assertEquals(1000.0, GpxParser.sampleProfile(profile, 1f)!!.distanceMeters, 0.01)
    }

    @Test
    fun `sampleProfile on an empty profile is null`() {
        assertNull(GpxParser.sampleProfile(emptyList(), 0.5f))
    }
}

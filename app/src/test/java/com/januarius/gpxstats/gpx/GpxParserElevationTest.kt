package com.januarius.gpxstats.gpx

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure-JVM tests for the smoothing + hysteresis elevation maths. */
class GpxParserElevationTest {

    private val window = GpxParser.DEFAULT_ELEVATION_MEDIAN_WINDOW
    private val threshold = GpxParser.DEFAULT_ELEVATION_THRESHOLD_M

    @Test
    fun `worked example - 300 500 400 700 gives gain 500 loss 100`() {
        // No smoothing here: 4 points, median filter is a no-op below 3 samples anyway.
        val (gain, loss) = GpxParser.elevationGainLoss(
            listOf(300.0, 500.0, 400.0, 700.0),
            medianWindow = 1,
            thresholdMeters = 10.0
        )
        assertEquals(500.0, gain, 0.001)
        assertEquals(100.0, loss, 0.001)
    }

    @Test
    fun `sub-threshold wander is ignored`() {
        val flatish = listOf(100.0, 103.0, 98.0, 101.0, 99.0, 102.0, 100.0)
        val (gain, loss) = GpxParser.elevationGainLoss(flatish, window, threshold)
        assertEquals(0.0, gain, 0.001)
        assertEquals(0.0, loss, 0.001)
    }

    @Test
    fun `single-sample spike is removed by the median filter`() {
        // Steady 200 m with one bogus 260 m reading; a 5-point median erases it.
        val withSpike = listOf(200.0, 200.0, 200.0, 260.0, 200.0, 200.0, 200.0)
        val (gain, loss) = GpxParser.elevationGainLoss(withSpike, window, threshold)
        assertEquals(0.0, gain, 0.001)
        assertEquals(0.0, loss, 0.001)
    }

    @Test
    fun `monotonic climb counts once, no loss`() {
        val climb = (0..50).map { 1000.0 + it * 4.0 } // 1000 -> 1200
        // medianWindow = 1: no edge smoothing, so the full ramp is measured.
        val (gain, loss) = GpxParser.elevationGainLoss(climb, medianWindow = 1, thresholdMeters = threshold)
        assertEquals(200.0, gain, 0.001)
        assertEquals(0.0, loss, 0.001)

        // With the 5-point filter the endpoints bias inward by at most one window.
        val (gainSmoothed, lossSmoothed) = GpxParser.elevationGainLoss(climb, window, threshold)
        assertEquals(200.0, gainSmoothed, 20.0)
        assertEquals(0.0, lossSmoothed, 0.001)
    }

    @Test
    fun `empty or single point is zero`() {
        assertEquals(0.0 to 0.0, GpxParser.elevationGainLoss(emptyList(), window, threshold))
        assertEquals(0.0 to 0.0, GpxParser.elevationGainLoss(listOf(500.0), window, threshold))
    }
}

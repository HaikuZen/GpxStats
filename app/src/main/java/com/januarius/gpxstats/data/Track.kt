package com.januarius.gpxstats.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One imported GPX track.
 *
 * The [name] (the GPX file name, without extension) is the primary key, so importing
 * the same file again refreshes its computed metrics without creating a duplicate row
 * and without overwriting the user-chosen [activity].
 */
@Entity(tableName = "tracks")
data class Track(
    @PrimaryKey
    val name: String,

    /** User-defined activity type. Defaults to "bike" on first import. */
    val activity: String = DEFAULT_ACTIVITY,

    val distanceMeters: Double = 0.0,

    /** Elapsed time between the first and last trackpoint. */
    val durationSeconds: Long = 0L,

    /** Time spent actually moving (excludes long pauses). */
    val movingSeconds: Long = 0L,

    /** Cumulative climb in metres (median-filtered + hysteresis, not a raw delta sum). */
    val elevationGainMeters: Double = 0.0,

    /** Cumulative descent in metres, same method as [elevationGainMeters]. */
    val elevationLossMeters: Double = 0.0,

    /** Fastest plausible segment speed in m/s. 0 when the file has no usable timestamps. */
    val maxSpeedMps: Double = 0.0,

    /** Highest elevation in metres, or null when the file has no elevation data. */
    val maxElevationMeters: Double? = null,

    /** Epoch millis of the first trackpoint, or null when the file has no timestamps. */
    val startTimeMillis: Long? = null,

    val pointCount: Int = 0,

    /** Content URI the track was last imported from (informational). */
    val sourceUri: String? = null,

    val importedAtMillis: Long = System.currentTimeMillis()
) {
    companion object {
        const val DEFAULT_ACTIVITY = "bike"
    }
}

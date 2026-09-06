package com.januarius.gpxstats.repo

import android.content.Context
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.januarius.gpxstats.data.Track
import com.januarius.gpxstats.gpx.ElevationOptions
import com.januarius.gpxstats.gpx.GpxParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/**
 * Small persistent settings: the sync folder, the default activity for new imports, and
 * the elevation gain / loss tunables ([elevationOptions]).
 */
class SettingsStore(private val context: Context) {

    private val keyFolder = stringPreferencesKey("sync_folder_uri")
    private val keyDefaultActivity = stringPreferencesKey("default_activity")
    private val keyMedianWindow = intPreferencesKey("elevation_median_window")
    private val keyThreshold = doublePreferencesKey("elevation_threshold_m")

    val syncFolderUri: Flow<String?> = context.dataStore.data.map { it[keyFolder] }

    val defaultActivity: Flow<String> =
        context.dataStore.data.map { it[keyDefaultActivity] ?: Track.DEFAULT_ACTIVITY }

    val elevationMedianWindow: Flow<Int> =
        context.dataStore.data.map {
            it[keyMedianWindow] ?: GpxParser.DEFAULT_ELEVATION_MEDIAN_WINDOW
        }

    val elevationThresholdMeters: Flow<Double> =
        context.dataStore.data.map {
            it[keyThreshold] ?: GpxParser.DEFAULT_ELEVATION_THRESHOLD_M
        }

    /** One-shot read of both elevation tunables, for use at import / sync time. */
    suspend fun elevationOptions(): ElevationOptions = context.dataStore.data.first().let {
        ElevationOptions(
            medianWindow = it[keyMedianWindow] ?: GpxParser.DEFAULT_ELEVATION_MEDIAN_WINDOW,
            thresholdMeters = it[keyThreshold] ?: GpxParser.DEFAULT_ELEVATION_THRESHOLD_M
        )
    }

    suspend fun setSyncFolder(uri: String) {
        context.dataStore.edit { it[keyFolder] = uri }
    }

    suspend fun clearSyncFolder() {
        context.dataStore.edit { it.remove(keyFolder) }
    }

    suspend fun setDefaultActivity(value: String) {
        context.dataStore.edit {
            it[keyDefaultActivity] = value.trim().ifEmpty { Track.DEFAULT_ACTIVITY }
        }
    }

    suspend fun setElevationMedianWindow(points: Int) {
        context.dataStore.edit {
            it[keyMedianWindow] = points.coerceIn(MEDIAN_WINDOW_MIN, MEDIAN_WINDOW_MAX)
        }
    }

    suspend fun setElevationThresholdMeters(meters: Double) {
        context.dataStore.edit {
            it[keyThreshold] = meters.coerceIn(THRESHOLD_MIN_M, THRESHOLD_MAX_M)
        }
    }

    /** Drop the user overrides so the parser falls back to its built-in defaults. */
    suspend fun resetElevationOptions() {
        context.dataStore.edit {
            it.remove(keyMedianWindow)
            it.remove(keyThreshold)
        }
    }

    companion object {
        const val MEDIAN_WINDOW_MIN = 1
        const val MEDIAN_WINDOW_MAX = 15
        const val THRESHOLD_MIN_M = 1.0
        const val THRESHOLD_MAX_M = 50.0
    }
}

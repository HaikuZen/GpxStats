package com.januarius.gpxstats.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.januarius.gpxstats.GpxStatsApp
import com.januarius.gpxstats.data.Track
import com.januarius.gpxstats.gpx.GpxParser
import com.januarius.gpxstats.repo.ImportResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.IsoFields
import java.util.Locale

/** Aggregated numbers for one activity type, one time period, or the current selection. */
data class ActivityStat(
    val label: String,
    val count: Int,
    val distanceMeters: Double,
    val durationSeconds: Long,
    val movingSeconds: Long,
    val elevationGainMeters: Double,
    val elevationLossMeters: Double = 0.0,
    /** Fastest single segment across the tracks in this group, m/s. */
    val maxSpeedMps: Double = 0.0,
    /** Highest elevation across the tracks in this group, m; null if none have elevation. */
    val maxElevationMeters: Double? = null
) {
    /**
     * Group average speed, m/s: total distance over total moving time (falling back to
     * elapsed time). Derived, not summed.
     */
    val avgSpeedMps: Double
        get() = when {
            movingSeconds > 0 -> distanceMeters / movingSeconds
            durationSeconds > 0 -> distanceMeters / durationSeconds
            else -> 0.0
        }
}

/** How the "By period" breakdown buckets tracks by their start date. */
enum class PeriodGrouping(val label: String) { WEEK("Week"), MONTH("Month"), YEAR("Year") }

/** Name of the breakdown one level finer than this grouping (used on PeriodDetailScreen). */
fun PeriodGrouping.finerGroupingLabel(): String = when (this) {
    PeriodGrouping.WEEK -> "day"
    PeriodGrouping.MONTH -> "week"
    PeriodGrouping.YEAR -> "month"
}

/** Label used for tracks with no start time — sorted last, and excluded from time charts. */
const val NO_DATE_LABEL = "No date"

data class Stats(
    val totalCount: Int = 0,
    val byActivity: List<ActivityStat> = emptyList(),
    val total: ActivityStat = ActivityStat("Total", 0, 0.0, 0L, 0L, 0.0),
    val selection: ActivityStat? = null,
    val periodGrouping: PeriodGrouping = PeriodGrouping.MONTH,
    val byPeriod: List<ActivityStat> = emptyList(),
    /** Same by-period breakdown as [byPeriod], scoped to one activity's own tracks. */
    val byActivityPeriod: Map<String, List<ActivityStat>> = emptyMap(),
    /** Activity breakdown for one period bucket's own tracks — keyed by [byPeriod] labels. */
    val byPeriodActivity: Map<String, List<ActivityStat>> = emptyMap(),
    /**
     * One level finer than [periodGrouping] (week -> day, month -> ISO week,
     * year -> month) for one period bucket's own tracks — keyed by [byPeriod] labels.
     */
    val byPeriodSubPeriod: Map<String, List<ActivityStat>> = emptyMap()
)

/** Common activity choices offered in the per-track picker. */
val COMMON_ACTIVITIES = listOf("bike", "run", "hike", "walk", "ski", "swim", "car", "other")

/** Progress of a folder sync: [done] of [total] files processed. */
data class SyncProgress(val done: Int, val total: Int)

class GpxStatsViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = (app as GpxStatsApp).repository
    private val settings = (app as GpxStatsApp).settings

    private val stopTimeout = 5_000L

    val tracks: StateFlow<List<Track>> =
        repo.tracks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(stopTimeout), emptyList())

    val knownActivities: StateFlow<List<String>> =
        repo.activities.stateIn(viewModelScope, SharingStarted.WhileSubscribed(stopTimeout), emptyList())

    val defaultActivity: StateFlow<String> =
        settings.defaultActivity.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(stopTimeout), Track.DEFAULT_ACTIVITY
        )

    val syncFolderUri: StateFlow<String?> =
        settings.syncFolderUri.stateIn(viewModelScope, SharingStarted.WhileSubscribed(stopTimeout), null)

    val elevationMedianWindow: StateFlow<Int> =
        settings.elevationMedianWindow.stateIn(
            viewModelScope, SharingStarted.Eagerly, GpxParser.DEFAULT_ELEVATION_MEDIAN_WINDOW
        )

    val elevationThresholdMeters: StateFlow<Double> =
        settings.elevationThresholdMeters.stateIn(
            viewModelScope, SharingStarted.Eagerly, GpxParser.DEFAULT_ELEVATION_THRESHOLD_M
        )

    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    private val _periodGrouping = MutableStateFlow(PeriodGrouping.MONTH)
    val periodGrouping: StateFlow<PeriodGrouping> = _periodGrouping.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Non-null while a folder sync is running. */
    private val _syncProgress = MutableStateFlow<SyncProgress?>(null)
    val syncProgress: StateFlow<SyncProgress?> = _syncProgress.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Set when a database snapshot is ready to hand to an ACTION_SEND chooser. */
    private val _shareDbUri = MutableStateFlow<Uri?>(null)
    val shareDbUri: StateFlow<Uri?> = _shareDbUri.asStateFlow()

    val stats: StateFlow<Stats> =
        combine(repo.tracks, _selected, _periodGrouping) { list, sel, grouping ->
            computeStats(list, sel, grouping)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(stopTimeout), Stats())

    // --- selection ---------------------------------------------------------

    fun toggleSelection(name: String) = _selected.update {
        if (name in it) it - name else it + name
    }

    fun selectAll() {
        _selected.value = tracks.value.map { it.name }.toSet()
    }

    fun clearSelection() {
        _selected.value = emptySet()
    }

    fun setPeriodGrouping(grouping: PeriodGrouping) {
        _periodGrouping.value = grouping
    }

    // --- edits -----------------------------------------------------------

    fun setActivity(name: String, activity: String) = viewModelScope.launch {
        repo.setActivity(name, activity)
    }

    fun setDefaultActivity(value: String) = viewModelScope.launch {
        settings.setDefaultActivity(value)
    }

    fun setElevationMedianWindow(points: Int) = viewModelScope.launch {
        settings.setElevationMedianWindow(points)
    }

    fun setElevationThresholdMeters(meters: Double) = viewModelScope.launch {
        settings.setElevationThresholdMeters(meters)
    }

    fun resetElevationOptions() = viewModelScope.launch {
        settings.resetElevationOptions()
    }

    fun deleteSelected() = viewModelScope.launch {
        val names = _selected.value
        if (names.isEmpty()) return@launch
        repo.delete(names)
        _selected.value = emptySet()
        _message.value = "Deleted ${names.size} track(s)"
    }

    // --- import / sync ---------------------------------------------------

    fun importFile(uri: Uri) = viewModelScope.launch {
        _busy.value = true
        val result = repo.importFile(uri, defaultActivity.value, settings.elevationOptions())
        _message.value = summarize(result)
        _busy.value = false
    }

    fun setSyncFolder(uri: Uri) = viewModelScope.launch {
        settings.setSyncFolder(uri.toString())
        runSync(uri)
    }

    fun syncNow() = viewModelScope.launch {
        val stored = syncFolderUri.value
        if (stored == null) {
            _message.value = "Choose a sync folder first"
            return@launch
        }
        runSync(Uri.parse(stored))
    }

    fun forgetSyncFolder() = viewModelScope.launch {
        settings.clearSyncFolder()
        _message.value = "Sync folder cleared"
    }

    private suspend fun runSync(uri: Uri) {
        _busy.value = true
        _syncProgress.value = SyncProgress(0, 0)
        val result = repo.syncFolder(uri, defaultActivity.value, settings.elevationOptions()) { done, total ->
            _syncProgress.value = SyncProgress(done, total)
        }
        _syncProgress.value = null
        _message.value = summarize(result)
        _busy.value = false
    }

    // --- share ----------------------------------------------------------

    fun shareDatabase() = viewModelScope.launch {
        if (tracks.value.isEmpty()) {
            _message.value = "Nothing to share yet"
            return@launch
        }
        _busy.value = true
        try {
            _shareDbUri.value = repo.exportDatabase()
        } catch (e: Exception) {
            _message.value = "Export failed: ${e.message ?: "unknown error"}"
        } finally {
            _busy.value = false
        }
    }

    fun consumeShareDbUri() {
        _shareDbUri.value = null
    }

    fun consumeMessage() {
        _message.value = null
    }

    private fun summarize(r: ImportResult): String {
        if (r.total == 0) return "Nothing imported"
        val parts = buildList {
            if (r.imported > 0) add("${r.imported} new")
            if (r.updated > 0) add("${r.updated} updated")
            if (r.failed.isNotEmpty()) add("${r.failed.size} failed")
        }
        val head = parts.joinToString(", ")
        return if (r.failed.isEmpty()) head else "$head — ${r.failed.first()}"
    }

    private fun computeStats(list: List<Track>, sel: Set<String>, grouping: PeriodGrouping): Stats {
        val byActivity = computeActivityBreakdown(list)

        val total = aggregate("Total", list)

        val selectedTracks = list.filter { it.name in sel }
        val selection = if (selectedTracks.isEmpty()) null else aggregate("Selected", selectedTracks)

        val byPeriod = computePeriodSeries(list, grouping)
        val byActivityPeriod = byActivity.associate { stat ->
            stat.label to computePeriodSeries(list.filter { it.activity == stat.label }, grouping)
        }

        val periodGroups = list.groupBy { periodKey(it.startTimeMillis, grouping) }
        val byPeriodActivity = periodGroups.entries.associate { (key, ts) ->
            periodLabel(key, grouping) to computeActivityBreakdown(ts)
        }
        val byPeriodSubPeriod = periodGroups.entries.associate { (key, ts) ->
            periodLabel(key, grouping) to computeSubPeriodSeries(ts, grouping)
        }

        return Stats(
            totalCount = list.size,
            byActivity = byActivity,
            total = total,
            selection = selection,
            periodGrouping = grouping,
            byPeriod = byPeriod,
            byActivityPeriod = byActivityPeriod,
            byPeriodActivity = byPeriodActivity,
            byPeriodSubPeriod = byPeriodSubPeriod
        )
    }

    /** Activity breakdown for an arbitrary subset of tracks (all of them, or one period's). */
    private fun computeActivityBreakdown(ts: List<Track>): List<ActivityStat> =
        ts.groupBy { it.activity }
            .map { (activity, group) -> aggregate(activity, group) }
            .sortedWith(compareByDescending<ActivityStat> { it.count }.thenBy { it.label })

    /** By-period breakdown for an arbitrary subset of tracks (all of them, or one activity's). */
    private fun computePeriodSeries(ts: List<Track>, grouping: PeriodGrouping): List<ActivityStat> =
        ts.groupBy { periodKey(it.startTimeMillis, grouping) }
            .map { (key, group) -> key to aggregate(periodLabel(key, grouping), group) }
            .let { entries ->
                val dated = entries.filter { it.first != NO_DATE_KEY }.sortedByDescending { it.first }
                val undated = entries.filter { it.first == NO_DATE_KEY }
                (dated + undated).map { it.second }
            }

    /**
     * One level finer than [grouping], for one period bucket's own tracks: a week's
     * tracks broken down by day, a month's by ISO week, a year's by month. Tracks with
     * no start time can't be placed on this finer timeline and are skipped (they're
     * still counted in the period's own totals via [aggregate]).
     */
    private fun computeSubPeriodSeries(ts: List<Track>, grouping: PeriodGrouping): List<ActivityStat> {
        val dated = ts.mapNotNull { t -> t.startTimeMillis?.let { t to it } }
        if (dated.isEmpty()) return emptyList()
        return when (grouping) {
            PeriodGrouping.WEEK -> dated.groupBy { (_, millis) -> dayKey(millis) }
                .toSortedMap()
                .map { (key, group) -> aggregate(dayLabel(key), group.map { it.first }) }

            PeriodGrouping.MONTH -> dated.groupBy { (_, millis) -> periodKey(millis, PeriodGrouping.WEEK) }
                .toSortedMap()
                .map { (key, group) -> aggregate(isoWeekLabel(key), group.map { it.first }) }

            PeriodGrouping.YEAR -> dated.groupBy { (_, millis) -> monthKey(millis) }
                .toSortedMap()
                .map { (key, group) -> aggregate(monthOnlyLabel(key), group.map { it.first }) }
        }
    }

    private fun dayKey(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toString()

    private fun dayLabel(key: String): String {
        val date = LocalDate.parse(key)
        val dayName = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        return "$dayName ${date.dayOfMonth}"
    }

    /** `key` is a WEEK [periodKey] like `2026-W39`; shown without the (redundant here) year. */
    private fun isoWeekLabel(key: String): String = "W" + key.substringAfter("-W")

    private fun monthKey(millis: Long): String =
        "%02d".format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).monthValue)

    private fun monthOnlyLabel(key: String): String =
        Month.of(key.toInt()).getDisplayName(TextStyle.SHORT, Locale.getDefault())

    private fun aggregate(label: String, ts: List<Track>) = ActivityStat(
        label = label,
        count = ts.size,
        distanceMeters = ts.sumOf { it.distanceMeters },
        durationSeconds = ts.sumOf { it.durationSeconds },
        movingSeconds = ts.sumOf { it.movingSeconds },
        elevationGainMeters = ts.sumOf { it.elevationGainMeters },
        elevationLossMeters = ts.sumOf { it.elevationLossMeters },
        maxSpeedMps = ts.maxOfOrNull { it.maxSpeedMps } ?: 0.0,
        maxElevationMeters = ts.mapNotNull { it.maxElevationMeters }.maxOrNull()
    )

    /** A sortable key: `2026`, `2026-05`, `2026-W18`, or [NO_DATE_KEY]. */
    private fun periodKey(millis: Long?, grouping: PeriodGrouping): String {
        if (millis == null) return NO_DATE_KEY
        val date = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
        return when (grouping) {
            PeriodGrouping.YEAR -> "%04d".format(date.year)
            PeriodGrouping.MONTH -> "%04d-%02d".format(date.year, date.monthValue)
            PeriodGrouping.WEEK -> "%04d-W%02d".format(
                date.get(IsoFields.WEEK_BASED_YEAR),
                date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
            )
        }
    }

    private fun periodLabel(key: String, grouping: PeriodGrouping): String {
        if (key == NO_DATE_KEY) return NO_DATE_LABEL
        return when (grouping) {
            PeriodGrouping.YEAR -> key
            PeriodGrouping.MONTH -> {
                val (year, month) = key.split("-")
                val name = Month.of(month.toInt())
                    .getDisplayName(TextStyle.SHORT, Locale.getDefault())
                "$name $year"
            }
            PeriodGrouping.WEEK -> key.replace("-W", " · W")
        }
    }

    private companion object {
        const val NO_DATE_KEY = "~nodate"
    }
}

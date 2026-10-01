@file:OptIn(ExperimentalMaterial3Api::class)

package com.januarius.gpxstats.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.januarius.gpxstats.BuildConfig
import com.januarius.gpxstats.R
import com.januarius.gpxstats.data.Track
import com.januarius.gpxstats.repo.SettingsStore
import kotlin.math.roundToInt

private const val ROUTE_MAIN = 0
private const val ROUTE_SETTINGS = 1
private const val ROUTE_ABOUT = 2
private const val ROUTE_ACTIVITY_DETAIL = 3

/** Cap on how many "By period" buckets the trend chart draws (most recent, oldest-first). */
private const val MAX_PERIOD_CHART_BARS = 12

@Composable
fun GpxStatsScreen(
    vm: GpxStatsViewModel,
    onPickFile: () -> Unit,
    onPickFolder: () -> Unit,
    onShareDatabase: () -> Unit
) {
    var route by rememberSaveable { mutableIntStateOf(ROUTE_MAIN) }
    var selectedActivity by rememberSaveable { mutableStateOf<String?>(null) }

    when (route) {
        ROUTE_SETTINGS -> SettingsScreen(vm = vm, onBack = { route = ROUTE_MAIN })
        ROUTE_ABOUT -> AboutScreen(onBack = { route = ROUTE_MAIN })
        ROUTE_ACTIVITY_DETAIL -> {
            val activity = selectedActivity
            if (activity != null) {
                ActivityDetailScreen(vm = vm, activity = activity, onBack = { route = ROUTE_MAIN })
            } else {
                route = ROUTE_MAIN
            }
        }
        else -> MainScreen(
            vm = vm,
            onPickFile = onPickFile,
            onPickFolder = onPickFolder,
            onShareDatabase = onShareDatabase,
            onOpenSettings = { route = ROUTE_SETTINGS },
            onOpenAbout = { route = ROUTE_ABOUT },
            onSelectActivity = { name ->
                selectedActivity = name
                route = ROUTE_ACTIVITY_DETAIL
            }
        )
    }
}

@Composable
private fun MainScreen(
    vm: GpxStatsViewModel,
    onPickFile: () -> Unit,
    onPickFolder: () -> Unit,
    onShareDatabase: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    onSelectActivity: (String) -> Unit
) {
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val syncProgress by vm.syncProgress.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val defaultActivity by vm.defaultActivity.collectAsStateWithLifecycle()
    val syncFolder by vm.syncFolderUri.collectAsStateWithLifecycle()
    val knownActivities by vm.knownActivities.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf("Tracks", "Statistics")
    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("GpxStats") },
                actions = {
                    IconButton(onClick = onShareDatabase, enabled = !busy && tracks.isNotEmpty()) {
                        Icon(Icons.Filled.Share, contentDescription = "Share database")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Info about GpxStats") },
                                onClick = {
                                    menuOpen = false
                                    onOpenAbout()
                                }
                            )
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { i, title ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) })
                }
            }

            SyncStatusBar(busy = busy, progress = syncProgress)

            when (tab) {
                0 -> TracksTab(
                    tracks = tracks,
                    selected = selected,
                    defaultActivity = defaultActivity,
                    syncFolderSet = syncFolder != null,
                    activityOptions = (COMMON_ACTIVITIES + knownActivities).distinct(),
                    busy = busy,
                    onPickFile = onPickFile,
                    onPickFolder = onPickFolder,
                    onSyncNow = vm::syncNow,
                    onForgetFolder = vm::forgetSyncFolder,
                    onDefaultActivityChange = vm::setDefaultActivity,
                    onToggle = vm::toggleSelection,
                    onSelectAll = vm::selectAll,
                    onClearSelection = vm::clearSelection,
                    onDeleteSelected = vm::deleteSelected,
                    onSetActivity = vm::setActivity
                )

                1 -> StatsTab(
                    stats = stats,
                    onPeriodGroupingChange = vm::setPeriodGrouping,
                    onSelectActivity = onSelectActivity
                )
            }
        }
    }
}

@Composable
private fun SettingsScreen(vm: GpxStatsViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val medianWindow by vm.elevationMedianWindow.collectAsStateWithLifecycle()
    val thresholdMeters by vm.elevationThresholdMeters.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            SettingsTab(
                medianWindow = medianWindow,
                thresholdMeters = thresholdMeters,
                onMedianWindowChange = vm::setElevationMedianWindow,
                onThresholdChange = vm::setElevationThresholdMeters,
                onReset = vm::resetElevationOptions
            )
        }
    }
}

@Composable
private fun SyncStatusBar(busy: Boolean, progress: SyncProgress?) {
    when {
        progress != null && progress.total > 0 -> {
            Column(Modifier.fillMaxWidth()) {
                LinearProgressIndicator(
                    progress = { progress.done.toFloat() / progress.total },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Syncing ${progress.done} / ${progress.total} files",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                )
            }
        }

        busy -> LinearProgressIndicator(Modifier.fillMaxWidth())
        else -> Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun TracksTab(
    tracks: List<Track>,
    selected: Set<String>,
    defaultActivity: String,
    syncFolderSet: Boolean,
    activityOptions: List<String>,
    busy: Boolean,
    onPickFile: () -> Unit,
    onPickFolder: () -> Unit,
    onSyncNow: () -> Unit,
    onForgetFolder: () -> Unit,
    onDefaultActivityChange: (String) -> Unit,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onDeleteSelected: () -> Unit,
    onSetActivity: (String, String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            SourceCard(
                syncFolderSet = syncFolderSet,
                busy = busy,
                onPickFile = onPickFile,
                onPickFolder = onPickFolder,
                onSyncNow = onSyncNow,
                onForgetFolder = onForgetFolder
            )
        }

        item {
            DefaultActivityField(
                value = defaultActivity,
                options = COMMON_ACTIVITIES,
                onChange = onDefaultActivityChange
            )
        }

        if (selected.isNotEmpty()) {
            item {
                SelectionBar(
                    count = selected.size,
                    total = tracks.size,
                    onSelectAll = onSelectAll,
                    onClear = onClearSelection,
                    onDelete = onDeleteSelected
                )
            }
        }

        if (tracks.isEmpty()) {
            item {
                Text(
                    "No tracks yet. Import a .gpx file or pick a folder to sync.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }

        items(tracks, key = { it.name }) { track ->
            TrackRow(
                track = track,
                checked = track.name in selected,
                activityOptions = activityOptions,
                onToggle = { onToggle(track.name) },
                onSetActivity = { onSetActivity(track.name, it) }
            )
        }
    }
}

@Composable
private fun SourceCard(
    syncFolderSet: Boolean,
    busy: Boolean,
    onPickFile: () -> Unit,
    onPickFolder: () -> Unit,
    onSyncNow: () -> Unit,
    onForgetFolder: () -> Unit
) {
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Import", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPickFile, enabled = !busy) {
                    Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("File")
                }
                OutlinedButton(onClick = onPickFolder, enabled = !busy) {
                    Icon(Icons.Filled.FolderOpen, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (syncFolderSet) "Change folder" else "Pick folder")
                }
            }
            if (syncFolderSet) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = onSyncNow, enabled = !busy) {
                        Icon(Icons.Filled.Sync, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Sync now")
                    }
                    TextButton(onClick = onForgetFolder, enabled = !busy) { Text("Forget") }
                }
                Text(
                    "Sync re-reads every .gpx in the folder. Existing tracks keep their activity.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun DefaultActivityField(
    value: String,
    options: List<String>,
    onChange: (String) -> Unit
) {
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Default activity for new imports", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                options.forEach { opt ->
                    FilterChip(
                        selected = opt == value,
                        onClick = { onChange(opt) },
                        label = { Text(opt) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectionBar(
    count: Int,
    total: Int,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onDelete: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("$count / $total selected", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onSelectAll) { Text("All") }
            TextButton(onClick = onClear) { Text("None") }
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete selected")
            }
        }
    }
}

@Composable
private fun TrackRow(
    track: Track,
    checked: Boolean,
    activityOptions: List<String>,
    onToggle: () -> Unit,
    onSetActivity: (String) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (checked) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = checked, onCheckedChange = { onToggle() })
                Spacer(Modifier.width(4.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        track.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${Format.date(track.startTimeMillis)} · ${track.pointCount} pts",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Metric("Distance", Format.distance(track.distanceMeters))
                Metric("Duration", Format.duration(track.durationSeconds))
                Metric("Moving", Format.duration(track.movingSeconds))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Metric("Ascent", Format.elevation(track.elevationGainMeters))
                Metric("Descent", Format.elevation(track.elevationLossMeters))
                Metric("Max alt", Format.altitude(track.maxElevationMeters))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Metric("Avg speed", Format.avgSpeed(track.distanceMeters, track.movingSeconds, track.durationSeconds))
                Metric("Max speed", Format.speed(track.maxSpeedMps))
            }

            ActivityPicker(
                current = track.activity,
                options = activityOptions,
                onPick = onSetActivity
            )
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ActivityPicker(
    current: String,
    options: List<String>,
    onPick: (String) -> Unit
) {
    var expanded by remember { mutableIntStateOf(0) }
    Box {
        AssistChip(
            onClick = { expanded = 1 },
            label = { Text(current) },
            leadingIcon = {
                Icon(Icons.AutoMirrored.Filled.DirectionsBike, null, Modifier.size(18.dp))
            }
        )
        DropdownMenu(expanded = expanded == 1, onDismissRequest = { expanded = 0 }) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt) },
                    onClick = {
                        expanded = 0
                        onPick(opt)
                    }
                )
            }
        }
    }
}

@Composable
private fun StatsTab(
    stats: Stats,
    onPeriodGroupingChange: (PeriodGrouping) -> Unit,
    onSelectActivity: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Activities", style = MaterialTheme.typography.titleMedium)
                    Text(
                        stats.totalCount.toString(),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "${Format.distance(stats.total.distanceMeters)} · " +
                            "${Format.durationLong(stats.total.durationSeconds)} total",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "↑ ${Format.elevation(stats.total.elevationGainMeters)} · " +
                            "↓ ${Format.elevation(stats.total.elevationLossMeters)}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "avg ${Format.speed(stats.total.avgSpeedMps)} · " +
                            "max ${Format.speed(stats.total.maxSpeedMps)} · " +
                            "peak ${Format.altitude(stats.total.maxElevationMeters)}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        stats.selection?.let { sel ->
            item { StatDetailCard(title = "Selected (${sel.count})", stat = sel, highlight = true) }
        }

        if (stats.byActivity.isNotEmpty()) {
            item {
                Card {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "Distance by activity",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(bottom = 10.dp)
                        )
                        ActivityBarChart(
                            entries = stats.byActivity,
                            valueOf = { it.distanceMeters },
                            valueLabel = { Format.distance(it.distanceMeters) },
                            secondaryOf = { it.elevationGainMeters },
                            secondaryLabel = "Climb gain"
                        )
                    }
                }
            }
        }

        if (stats.byActivity.isNotEmpty()) {
            item {
                Card {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "By activity",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        Text(
                            "Tap a row for details and a trend chart.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                        StatHeaderRow()
                        HorizontalDivider(
                            Modifier.padding(vertical = 4.dp),
                            color = DividerDefaults.color
                        )
                        stats.byActivity.forEach { s ->
                            StatValueRow(
                                label = s.label,
                                count = s.count,
                                distance = Format.distance(s.distanceMeters),
                                duration = Format.duration(s.durationSeconds),
                                avgSpeed = Format.speed(s.avgSpeedMps),
                                ascent = Format.elevation(s.elevationGainMeters),
                                onClick = { onSelectActivity(s.label) }
                            )
                        }
                        HorizontalDivider(
                            Modifier.padding(vertical = 4.dp),
                            color = DividerDefaults.color
                        )
                        StatValueRow(
                            label = "Total",
                            count = stats.total.count,
                            distance = Format.distance(stats.total.distanceMeters),
                            duration = Format.duration(stats.total.durationSeconds),
                            avgSpeed = Format.speed(stats.total.avgSpeedMps),
                            ascent = Format.elevation(stats.total.elevationGainMeters),
                            bold = true
                        )
                    }
                }
            }
        }

        if (stats.byPeriod.isNotEmpty()) {
            item {
                PeriodCard(
                    grouping = stats.periodGrouping,
                    rows = stats.byPeriod,
                    onGroupingChange = onPeriodGroupingChange
                )
            }
        }

        stats.byActivity.forEach { s ->
            item { StatDetailCard(title = s.label, stat = s, highlight = false) }
        }
    }
}

@Composable
private fun PeriodCard(
    grouping: PeriodGrouping,
    rows: List<ActivityStat>,
    onGroupingChange: (PeriodGrouping) -> Unit
) {
    Card {
        Column(Modifier.padding(12.dp)) {
            Text(
                "By period",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PeriodGrouping.entries.forEach { option ->
                    FilterChip(
                        selected = option == grouping,
                        onClick = { onGroupingChange(option) },
                        label = { Text(option.label) }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            val chartEntries = periodChartEntries(rows)
            if (chartEntries.isNotEmpty()) {
                PeriodBarChart(entries = chartEntries)
                Spacer(Modifier.height(12.dp))
            }

            StatHeaderRow(firstColumn = grouping.label)
            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = DividerDefaults.color)
            rows.forEach { s ->
                StatValueRow(
                    label = s.label,
                    count = s.count,
                    distance = Format.distance(s.distanceMeters),
                    duration = Format.duration(s.durationSeconds),
                    avgSpeed = Format.speed(s.avgSpeedMps),
                    ascent = Format.elevation(s.elevationGainMeters)
                )
            }
        }
    }
}

/**
 * Most recent buckets, oldest -> newest, so the trend reads left to right. "No date"
 * has no place on a timeline and stays out of the chart (it is still in the table).
 */
private fun periodChartEntries(rows: List<ActivityStat>): List<PeriodPoint> =
    rows
        .filterNot { it.label == NO_DATE_LABEL }
        .take(MAX_PERIOD_CHART_BARS)
        .asReversed()
        .map { PeriodPoint(it.label, it.distanceMeters, it.elevationGainMeters) }

/** Detail page for one activity type: its full stat card plus a by-period trend chart. */
@Composable
private fun ActivityDetailScreen(vm: GpxStatsViewModel, activity: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val stats by vm.stats.collectAsStateWithLifecycle()
    val stat = stats.byActivity.find { it.label == activity }
    val periodRows = stats.byActivityPeriod[activity].orEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(activity) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (stat == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    "No tracks left for \"$activity\".",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { StatDetailCard(title = "${stat.count} track(s)", stat = stat, highlight = true) }

            if (periodRows.isNotEmpty()) {
                item {
                    Card {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                "Trend",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                PeriodGrouping.entries.forEach { option ->
                                    FilterChip(
                                        selected = option == stats.periodGrouping,
                                        onClick = { vm.setPeriodGrouping(option) },
                                        label = { Text(option.label) }
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))

                            val chartEntries = periodChartEntries(periodRows)
                            if (chartEntries.isNotEmpty()) {
                                PeriodBarChart(entries = chartEntries)
                                Spacer(Modifier.height(12.dp))
                            }

                            StatHeaderRow(firstColumn = stats.periodGrouping.label)
                            HorizontalDivider(
                                Modifier.padding(vertical = 4.dp),
                                color = DividerDefaults.color
                            )
                            periodRows.forEach { s ->
                                StatValueRow(
                                    label = s.label,
                                    count = s.count,
                                    distance = Format.distance(s.distanceMeters),
                                    duration = Format.duration(s.durationSeconds),
                                    avgSpeed = Format.speed(s.avgSpeedMps),
                                    ascent = Format.elevation(s.elevationGainMeters)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatDetailCard(title: String, stat: ActivityStat, highlight: Boolean) {
    Card(
        colors = if (highlight) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Metric("Count", stat.count.toString())
                Metric("Distance", Format.distance(stat.distanceMeters))
                Metric("Duration", Format.duration(stat.durationSeconds))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Metric("Moving", Format.duration(stat.movingSeconds))
                Metric("Ascent", Format.elevation(stat.elevationGainMeters))
                Metric("Descent", Format.elevation(stat.elevationLossMeters))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Metric("Avg speed", Format.speed(stat.avgSpeedMps))
                Metric("Max speed", Format.speed(stat.maxSpeedMps))
                Metric("Max alt", Format.altitude(stat.maxElevationMeters))
            }
        }
    }
}

@Composable
private fun StatHeaderRow(firstColumn: String = "Activity") {
    Row(Modifier.fillMaxWidth()) {
        Cell(firstColumn, weight = 1.4f, bold = true)
        Cell("#", weight = 0.4f, bold = true)
        Cell("Distance", weight = 1.05f, bold = true)
        Cell("Duration", weight = 1.0f, bold = true)
        Cell("Avg", weight = 1.05f, bold = true)
        Cell("↑", weight = 0.95f, bold = true)
    }
}

@Composable
private fun StatValueRow(
    label: String,
    count: Int,
    distance: String,
    duration: String,
    avgSpeed: String,
    ascent: String,
    bold: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val rowModifier = if (onClick != null) {
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 2.dp)
    } else {
        Modifier.fillMaxWidth().padding(vertical = 2.dp)
    }
    Row(rowModifier) {
        Cell(label, weight = 1.4f, bold = bold)
        Cell(count.toString(), weight = 0.4f, bold = bold)
        Cell(distance, weight = 1.05f, bold = bold)
        Cell(duration, weight = 1.0f, bold = bold)
        Cell(avgSpeed, weight = 1.05f, bold = bold)
        Cell(ascent, weight = 0.95f, bold = bold)
    }
}

@Composable
private fun RowScope.Cell(
    text: String,
    weight: Float,
    bold: Boolean = false
) {
    Text(
        text = text,
        modifier = Modifier.weight(weight),
        style = MaterialTheme.typography.bodySmall,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun SettingsTab(
    medianWindow: Int,
    thresholdMeters: Double,
    onMedianWindowChange: (Int) -> Unit,
    onThresholdChange: (Double) -> Unit,
    onReset: () -> Unit
) {
    val thresholdM = thresholdMeters.roundToInt()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card {
                Column(
                    Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Elevation gain / loss", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "The elevation profile is median-filtered to drop GPS spikes, then only " +
                            "climbs and descents larger than the threshold are counted.",
                        style = MaterialTheme.typography.bodySmall
                    )

                    StepperRow(
                        label = "Median filter window",
                        valueText = "$medianWindow pts",
                        canDecrement = medianWindow > SettingsStore.MEDIAN_WINDOW_MIN,
                        canIncrement = medianWindow < SettingsStore.MEDIAN_WINDOW_MAX,
                        onDecrement = { onMedianWindowChange(medianWindow - 1) },
                        onIncrement = { onMedianWindowChange(medianWindow + 1) }
                    )
                    StepperRow(
                        label = "Hysteresis threshold",
                        valueText = "$thresholdM m",
                        canDecrement = thresholdM > SettingsStore.THRESHOLD_MIN_M,
                        canIncrement = thresholdM < SettingsStore.THRESHOLD_MAX_M,
                        onDecrement = { onThresholdChange((thresholdM - 1).toDouble()) },
                        onIncrement = { onThresholdChange((thresholdM + 1).toDouble()) }
                    )

                    TextButton(onClick = onReset) { Text("Reset to defaults (5 pts · 10 m)") }

                    Text(
                        "Applied on the next import or sync — re-sync the folder to recompute " +
                            "existing tracks.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun StepperRow(
    label: String,
    valueText: String,
    canDecrement: Boolean,
    canIncrement: Boolean,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onDecrement, enabled = canDecrement) {
            Icon(Icons.Filled.Remove, contentDescription = "Decrease $label")
        }
        Text(
            valueText,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 64.dp)
        )
        IconButton(onClick = onIncrement, enabled = canIncrement) {
            Icon(Icons.Filled.Add, contentDescription = "Increase $label")
        }
    }
}

private const val SOURCE_URL = "https://github.com/HaikuZen/GpxStats"

/** Third-party runtime libraries, shown on the About page. All are Apache-2.0. */
private val ABOUT_LIBRARIES = listOf(
    "Jetpack Compose — UI, Material 3, Material Icons",
    "AndroidX Core · Activity · Lifecycle",
    "AndroidX Room (SQLite persistence)",
    "AndroidX DataStore (settings)",
    "AndroidX DocumentFile (Storage Access Framework)",
    "Kotlin standard library & Coroutines"
)

@Composable
private fun AboutScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val uriHandler = LocalUriHandler.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About GpxStats") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    "GpxStats",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            item {
                Text(
                    "Imports GPX tracks, stores their metrics in a local database and shows " +
                        "statistics by activity and by time period. No account, no network, " +
                        "no tracking.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            item {
                Card {
                    Column(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Source code", style = MaterialTheme.typography.titleSmall)
                        OutlinedButton(onClick = { uriHandler.openUri(SOURCE_URL) }) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("github.com/HaikuZen/GpxStats")
                        }
                        Text(
                            "Licensed under the Apache License 2.0.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            item {
                Card {
                    Column(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("Open-source libraries", style = MaterialTheme.typography.titleSmall)
                        ABOUT_LIBRARIES.forEach { name ->
                            Text("• $name", style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            "All under the Apache License 2.0.",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            item {
                Image(
                    painter = painterResource(R.drawable.mylogo),
                    contentDescription = "Born in Napùle 65",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 150.dp)
                        .padding(top = 8.dp)
                )
            }
        }
    }
}

package com.januarius.gpxstats.repo

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.sqlite.db.SimpleSQLiteQuery
import com.januarius.gpxstats.data.AppDatabase
import com.januarius.gpxstats.data.Track
import com.januarius.gpxstats.data.TrackDao
import com.januarius.gpxstats.gpx.ElevationOptions
import com.januarius.gpxstats.gpx.GpxParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Outcome of an import / sync run, surfaced to the user as a short message. */
data class ImportResult(
    val imported: Int = 0,
    val updated: Int = 0,
    val failed: List<String> = emptyList()
) {
    val total: Int get() = imported + updated + failed.size

    operator fun plus(other: ImportResult) = ImportResult(
        imported = imported + other.imported,
        updated = updated + other.updated,
        failed = failed + other.failed
    )
}

class TrackRepository(
    private val dao: TrackDao,
    private val context: Context
) {
    val tracks: Flow<List<Track>> = dao.observeAll()
    val activities: Flow<List<String>> = dao.observeActivities()

    suspend fun setActivity(name: String, activity: String) = withContext(Dispatchers.IO) {
        dao.setActivity(name, activity.trim().ifEmpty { Track.DEFAULT_ACTIVITY })
    }

    suspend fun delete(names: Collection<String>) = withContext(Dispatchers.IO) {
        dao.deleteByNames(names.toList())
    }

    /** Import a single GPX file chosen with the system picker. */
    suspend fun importFile(
        uri: Uri,
        defaultActivity: String,
        elevation: ElevationOptions
    ): ImportResult =
        withContext(Dispatchers.IO) {
            val name = DocumentFile.fromSingleUri(context, uri)?.name
                ?: uri.lastPathSegment
                ?: "unknown.gpx"
            importOne(uri, name, defaultActivity, elevation)
        }

    /**
     * Sync mode: re-scan a previously picked folder tree and import every `.gpx` file in it.
     * Existing tracks keep their user-defined activity; only computed metrics are refreshed.
     *
     * [onProgress] is called with `(done, total)` — once with `(0, total)` before the
     * first file, then after each file — so the caller can show a determinate bar.
     */
    suspend fun syncFolder(
        treeUri: Uri,
        defaultActivity: String,
        elevation: ElevationOptions,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): ImportResult =
        withContext(Dispatchers.IO) {
            val tree = DocumentFile.fromTreeUri(context, treeUri)
                ?: return@withContext ImportResult(failed = listOf("Cannot open the selected folder"))

            val gpxFiles = tree.listFiles().filter { doc ->
                doc.isFile && doc.name?.lowercase()?.endsWith(".gpx") == true
            }
            if (gpxFiles.isEmpty()) {
                return@withContext ImportResult(failed = listOf("No .gpx files found in folder"))
            }

            onProgress(0, gpxFiles.size)
            gpxFiles.foldIndexed(ImportResult()) { index, acc, doc ->
                val next = acc + importOne(
                    doc.uri,
                    doc.name ?: doc.uri.lastPathSegment ?: "unknown.gpx",
                    defaultActivity,
                    elevation
                )
                onProgress(index + 1, gpxFiles.size)
                next
            }
        }

    private suspend fun importOne(
        uri: Uri,
        displayName: String,
        defaultActivity: String,
        elevation: ElevationOptions
    ): ImportResult {
        val key = keyFromFileName(displayName)
        return try {
            val summary = context.contentResolver.openInputStream(uri).use { stream ->
                requireNotNull(stream) { "cannot open stream" }
                GpxParser.parse(stream, elevation)
            }

            val existing = dao.findByName(key)
            if (existing == null) {
                dao.insertIgnore(
                    Track(
                        name = key,
                        activity = defaultActivity.trim().ifEmpty { Track.DEFAULT_ACTIVITY },
                        distanceMeters = summary.distanceMeters,
                        durationSeconds = summary.durationSeconds,
                        movingSeconds = summary.movingSeconds,
                        elevationGainMeters = summary.elevationGainMeters,
                        elevationLossMeters = summary.elevationLossMeters,
                        maxSpeedMps = summary.maxSpeedMps,
                        maxElevationMeters = summary.maxElevationMeters,
                        startTimeMillis = summary.startTimeMillis,
                        pointCount = summary.pointCount,
                        sourceUri = uri.toString()
                    )
                )
                ImportResult(imported = 1)
            } else {
                dao.update(
                    existing.copy(
                        distanceMeters = summary.distanceMeters,
                        durationSeconds = summary.durationSeconds,
                        movingSeconds = summary.movingSeconds,
                        elevationGainMeters = summary.elevationGainMeters,
                        elevationLossMeters = summary.elevationLossMeters,
                        maxSpeedMps = summary.maxSpeedMps,
                        maxElevationMeters = summary.maxElevationMeters,
                        startTimeMillis = summary.startTimeMillis,
                        pointCount = summary.pointCount,
                        sourceUri = uri.toString(),
                        importedAtMillis = System.currentTimeMillis()
                    )
                )
                ImportResult(updated = 1)
            }
        } catch (e: Exception) {
            ImportResult(failed = listOf("$displayName: ${e.message ?: "parse error"}"))
        }
    }

    /** The GPX file name without its extension becomes the primary key. */
    private fun keyFromFileName(fileName: String): String =
        fileName.substringBeforeLast('.').trim().ifEmpty { fileName.trim() }

    /**
     * Copy the live SQLite database into the app cache and return a shareable
     * `content://` uri (via [FileProvider]). The WAL is checkpointed first so the
     * copied `.db` file is self-contained. Callers hand the uri to an
     * `ACTION_SEND` chooser.
     */
    suspend fun exportDatabase(): Uri = withContext(Dispatchers.IO) {
        runCatching { dao.runPragma(SimpleSQLiteQuery("PRAGMA wal_checkpoint(FULL)")) }

        val source = context.getDatabasePath(AppDatabase.NAME)
        require(source.exists()) { "database file not found" }

        val outDir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
        outDir.listFiles()?.forEach { it.delete() }

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val out = File(outDir, "gpxstats-$stamp.db")
        source.inputStream().use { input -> out.outputStream().use { input.copyTo(it) } }

        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", out)
    }

    private companion object {
        const val SHARE_DIR = "shared"
    }
}

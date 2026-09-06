# CLAUDE.md

Guidance for working in this repository.

## What this is

`GpxStats` — an Android app that imports GPX tracks, stores their computed metrics
(distance, duration, moving time, elevation gain / loss, max speed, max altitude) in a
local SQLite database, and shows aggregate statistics — count / distance / duration /
moving / ascent / descent / avg speed / max speed / max altitude — per user-assigned
activity type and per time period (week / month / year). The database can be shared as a
file; a Settings tab tunes the elevation-smoothing parameters. Application id /
namespace: `com.januarius.gpxstats`.

## Build & run

```bash
./gradlew :app:assembleDebug        # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug         # build + adb install to a connected device
./gradlew :app:lintDebug            # Android lint
./gradlew test                      # JVM unit tests (GpxParserElevationTest covers the
                                    # elevation gain/loss maths; parse() itself needs
                                    # instrumentation)
./gradlew clean
```

Toolchain (pinned):

- Gradle must run on **JDK 17** (AGP 8.7 / Gradle 8.9 reject newer JDKs). The path is
  **not** committed — keep it that way (portability / F-Droid). On a box whose default
  `java` is newer, add `org.gradle.java.home=…` to `~/.gradle/gradle.properties`, never
  to the tracked `gradle.properties`.
- Gradle **8.9** (wrapper committed), AGP **8.7.3**, Kotlin **2.0.21**, KSP
  `2.0.21-1.0.28`.
- Android SDK **API 35** + build-tools **35.0.0**. `local.properties` sets `sdk.dir`
  and is not committed.
- minSdk 26, target/compileSdk 35.

## Architecture

Single module (`:app`), single activity, Jetpack Compose UI, unidirectional data flow.

```
GpxStatsApp                Application. Lazily builds AppDatabase, TrackRepository,
                           SettingsStore and exposes them as properties.
MainActivity               Compose host. Owns the SAF launchers
                           (OpenDocument / OpenDocumentTree), forwards Uris to the VM.
                           Observes vm.shareDbUri and fires the ACTION_SEND chooser.

data/
  Track                    @Entity. PRIMARY KEY = `name` (GPX file name, no extension).
                           Stored metrics: distanceMeters, durationSeconds, movingSeconds,
                           elevationGainMeters, elevationLossMeters, maxSpeedMps,
                           maxElevationMeters (nullable).
  TrackDao                 Flow<List<Track>> observers + suspend upsert helpers +
                           runPragma() (@RawQuery, used to checkpoint the WAL).
  AppDatabase              Room, single instance, db file "gpxstats.db" (AppDatabase.NAME),
                           schema v3. MIGRATION_1_2 adds maxSpeedMps / maxElevationMeters;
                           MIGRATION_2_3 adds elevationLossMeters.

gpx/GpxParser              Stateless object. parse(input, medianWindow, threshold) or
                           parse(input, ElevationOptions) -> GpxSummary using
                           android.util.Xml (XmlPullParser). Haversine distance, elapsed +
                           moving time, fastest plausible segment speed (<= 55 m/s),
                           highest <ele>. Elevation gain / loss come from
                           elevationGainLoss(): an n-point median filter
                           (DEFAULT_ELEVATION_MEDIAN_WINDOW = 5) then a hysteresis walk
                           that only commits a climb/descent past a threshold
                           (DEFAULT_ELEVATION_THRESHOLD_M = 10 m).
        ElevationOptions   {medianWindow, thresholdMeters} bundle for the above.

repo/
  TrackRepository          importFile(uri, activity, elevation) / syncFolder(treeUri,
                           activity, elevation, onProgress) via DocumentFile. New file ->
                           insert with default activity. Existing name -> update computed
                           fields ONLY, activity kept. syncFolder calls onProgress(done,
                           total) once with (0,total) then after every file.
                           exportDatabase() -> checkpoints the WAL, copies the .db into
                           cacheDir/shared/, returns a FileProvider content:// uri.
  SettingsStore            DataStore Preferences: sync folder uri, default activity,
                           elevationMedianWindow / elevationThresholdMeters (Flows +
                           setters, coerced to the companion MIN/MAX). elevationOptions()
                           is a one-shot read used at import/sync time.

ui/
  GpxStatsViewModel        AndroidViewModel. Exposes StateFlows (tracks, selection,
                           periodGrouping, busy, syncProgress, message, shareDbUri, stats,
                           elevationMedianWindow, elevationThresholdMeters).
                           computeStats() groups by activity AND by time period
                           (PeriodGrouping WEEK/MONTH/YEAR, tracks with a null start
                           time fall into a "No date" bucket sorted last), aggregating
                           count / distance / duration / moving / ascent / descent (all
                           summed), plus max speed and max altitude (max / max-of-nullable,
                           NOT summed) and ActivityStat.avgSpeedMps (derived: distance /
                           moving, else elapsed); also a "Selected" aggregate over the
                           checked tracks.
                           importFile / runSync read settings.elevationOptions() and pass
                           it to the repo; runSync feeds the repo's progress callback into
                           _syncProgress. shareDatabase() -> repo.exportDatabase(), result
                           in shareDbUri.
  GpxStatsScreen           GpxStatsScreen() is a router over a `route` Int state:
                           MainScreen (default), SettingsScreen, AboutScreen.
                           MainScreen — two tabs "Tracks" / "Statistics"; top bar has
                           Share, a gear icon (-> SettingsScreen) and a ⋮ overflow with
                           "Info about GpxStats" (-> AboutScreen). SyncStatusBar under the
                           tab row: determinate "N / M files" during a folder sync,
                           indeterminate for a single-file import.
                           SettingsScreen — back arrow + the elevation median-window /
                           threshold steppers + reset (SettingsTab content).
                           AboutScreen — back arrow; BuildConfig.VERSION_NAME/CODE, a
                           LocalUriHandler link to SOURCE_URL, the ABOUT_LIBRARIES list,
                           and res/drawable-nodpi/mylogo.jpg at the bottom.
                           SettingsScreen/AboutScreen both take a BackHandler.
                           Stat compact tables show label / # / distance / duration /
                           avg speed / ascent; detail cards add moving / descent / max
                           speed / max altitude.
  Theme / Format           Material 3 theme (dynamic color on S+), display formatters.
```

`FileProvider` is declared in the manifest with authority `${applicationId}.fileprovider`
and `res/xml/file_paths.xml` (only `cacheDir/shared/` is exposed).

Data flow: SAF `Uri` → `MainActivity` → `GpxStatsViewModel` → `TrackRepository`
(IO dispatcher) → Room. Room `Flow`s drive `StateFlow`s the composables collect with
`collectAsStateWithLifecycle`. User messages surface through `_message` → snackbar.
Sharing: `shareDatabase()` → `_shareDbUri` → `MainActivity` `LaunchedEffect` →
`ACTION_SEND` chooser, then `consumeShareDbUri()`.

## Conventions / invariants

- **The file name without extension is the identity of a track.** Never switch inserts
  to `OnConflictStrategy.REPLACE` — it would wipe the user's activity choice on re-sync.
  Re-import = refresh metrics, preserve `activity`.
- Default activity is `"bike"` — the constant is `Track.DEFAULT_ACTIVITY`. Blank input
  falls back to it in both the repository and `SettingsStore`.
- All DB / file / parsing work runs off the main thread (`withContext(Dispatchers.IO)`
  in the repo, `viewModelScope` in the VM). Keep it that way.
- Folder access is scoped (SAF tree uri + persisted permission). Do **not** add
  `READ_EXTERNAL_STORAGE` / `MANAGE_EXTERNAL_STORAGE`.
- Compose Material 3 experimental APIs: opt in with a file-level
  `@file:OptIn(ExperimentalMaterial3Api::class)` rather than scattered annotations.
- `buildConfig = true` is on so `AboutScreen` can read `BuildConfig.VERSION_NAME` /
  `VERSION_CODE`. `SOURCE_URL` (the GitHub repo) and `ABOUT_LIBRARIES` live in
  `GpxStatsScreen.kt` — update them if the repo moves or deps change.
- `GpxParser.parse()` depends on `android.util.Xml`, so it needs an instrumented test or
  Robolectric. But `elevationGainLoss()` is `internal` and pure (no Android types) — it
  is the right place for plain JVM unit tests of the smoothing / hysteresis maths.
- The shared database is a plain copy of `gpxstats.db` — no encryption, no redaction.
  It is written to `cacheDir/shared/` (wiped and rewritten on each share) and only ever
  leaves the app through the user-driven `ACTION_SEND` chooser. Keep it that way; do not
  auto-upload or attach it anywhere without an explicit user action.
- Time-period bucketing uses `java.time` with the device's default zone and ISO week
  numbering (`IsoFields.WEEK_BASED_YEAR` / `WEEK_OF_WEEK_BASED_YEAR`). All aggregation is
  in-memory in the VM — there is no SQL `GROUP BY` for periods.
- Speed metrics: `maxSpeedMps` is a stored per-track value (fastest GPS segment, glitch
  segments above 55 m/s dropped). `avgSpeedMps` is **derived** in `ActivityStat`
  (total distance / total moving time, elapsed as fallback) — never store or sum it.
  `maxElevationMeters` is nullable end-to-end: null means the file had no `<ele>` data,
  and it renders as `–`.
- Elevation gain / loss are **never** a raw sum of sample deltas. They come from
  `GpxParser.elevationGainLoss()` — median filter then hysteresis — and are stored
  per-track (`elevationGainMeters`, `elevationLossMeters`); aggregation just sums them.
  If you touch the smoothing/threshold logic, re-check the worked example in the KDoc
  (`300 → 500 → 400 → 700` @ 10 m ⇒ gain 500, loss 100). The window/threshold are
  `parse()` parameters, not hard-coded — keep them that way.
- The elevation window/threshold live in `SettingsStore` (DataStore). Read them for a
  parse via `settings.elevationOptions()` (one-shot `first()`), **not** via the display
  `StateFlow.value` — those use `SharingStarted.Eagerly` for the UI but the one-shot read
  is the source of truth at import time. Changes take effect on the next import / sync
  only; existing rows need a re-sync to recompute.
- Adding a stored metric column means a schema bump + a `Migration` registered in
  `addMigrations(...)` (see `MIGRATION_1_2`, `MIGRATION_2_3`). Do not rely on
  `fallbackToDestructiveMigration()` now that real users may have data — it would drop
  their activity assignments.

## Gotchas

- `material-icons-extended` is a dependency for a single icon
  (`AutoMirrored.Filled.DirectionsBike`) and inflates the APK to ~17 MB. Swap for a core
  icon and drop the dep if size matters.
- The system Gradle on the build box is ancient (4.4.1); always use `./gradlew`.
- The JDK 17 path lives in `~/.gradle/gradle.properties` on this box, not in the repo.
  A fresh clone on a JDK-17-default machine needs nothing; on a newer default it fails
  with a Gradle/JDK-support error until you add the `org.gradle.java.home` line there.
- Licensed **Apache-2.0** (`LICENSE`, `NOTICE`). No permissions, no network, no trackers
  — keep it that way so it stays F-Droid-eligible.
- GPX files without `<time>` elements import fine but have `durationSeconds == 0` and
  `startTimeMillis == null` (they sort last).
- `samples/ride-sample.gpx` is a small fixture for manual import testing.

## Common changes

- **New track metric**: add the column to `Track`, bump `@Database(version=)` and add a
  `Migration` next to `MIGRATION_1_2` (register it in `addMigrations(...)`), populate it
  in `GpxParser`/`GpxSummary`, thread it through `TrackRepository.importOne` (both the
  insert and the update branch), aggregate it in `GpxStatsViewModel.aggregate` (or add a
  derived getter on `ActivityStat` if it is a ratio like avg speed), add a `Format`
  helper, render it in `GpxStatsScreen` (`TrackRow` + `StatDetailCard`, and a table
  column if it belongs in the compact view).
- **New statistic**: extend `ActivityStat` / `Stats` and `computeStats`, then the
  Statistics tab composables.
- **New period grouping**: add a value to `PeriodGrouping` and a branch in
  `GpxStatsViewModel.periodKey` / `periodLabel`; the chip row and table pick it up
  automatically (`PeriodGrouping.entries`).
- **Expose more of the cache dir to sharing**: add a `<paths>` entry to
  `res/xml/file_paths.xml`; the authority is already wired.
- **New user setting**: add a key + `Flow` + setter (coerced) in `SettingsStore` (and a
  field in `elevationOptions()`-style one-shot reads if it affects parsing/sync); expose
  a `StateFlow` + setter on `GpxStatsViewModel`; render it in `SettingsTab`. Use
  `SharingStarted.Eagerly` for a setting whose `.value` might be read outside an active
  collector.

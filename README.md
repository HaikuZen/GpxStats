# GpxStats

An Android app that imports GPX tracks, stores each track's computed metrics in a local
SQLite database, and shows aggregate statistics grouped by a user-assigned activity type
and by time period (week / month / year). The database can be exported and shared as a
file.

Package: `com.januarius.gpxstats`

---

## Features

### Import

| Mode | How | Behaviour |
|------|-----|-----------|
| **Single file** | *Tracks → File* | Opens the system picker, imports one `.gpx`. |
| **Sync mode** | *Tracks → Pick folder*, then *Sync now* | Remembers a folder; every "Sync now" re-reads all `.gpx` files in it, with a *N / M files* progress bar. |

Folder access uses the Storage Access Framework with a persisted permission — no
broad storage permission is requested.

### Per-track parsing

Each GPX file is parsed (streaming `XmlPullParser`) into:

- **Distance** — haversine sum over consecutive track points
- **Duration** — elapsed time between first and last point
- **Moving time** — elapsed time minus pauses (segments below ~0.6 m/s)
- **Elevation gain / loss** — cumulative climb and descent, computed *realistically*
  (see below) rather than by summing raw sample-to-sample deltas
- **Max speed** — fastest GPS segment (glitch spikes above ~198 km/h dropped)
- **Max altitude** — highest `<ele>` value in the file
- **Start date** and **track-point count**

Files without `<time>` elements still import (distance and elevation only; duration,
moving time and max speed are `0`). Files without `<ele>` show `–` for altitude and `0`
for gain / loss.

#### Elevation gain / loss algorithm

Raw GPS/barometric elevation jitters by several metres per sample, so summing every
positive delta massively overestimates the climb. Instead:

1. **Median filter** — an *n*-point sliding median over the `<ele>` series removes
   one-off spikes (`n = 5` by default).
2. **Hysteresis** — a walk over the smoothed profile tracks the last turning point and
   only commits a climb or descent once the profile reverses by more than a **threshold**
   (`10 m` by default). Wander smaller than the threshold is ignored.

Both `n` (1–15) and the threshold (1–50 m) are editable on the **Settings** tab; they
default to **5** and **10 m** and are stored per install. New values apply to the next
import or sync — re-sync the folder to recompute existing tracks.

Worked example: `300 m → 500 m → 400 m → 700 m` with a 10 m threshold →
**gain 500 m**, **loss 100 m**.

### Storage

- Persisted with **Room / SQLite** (`gpxstats.db`).
- **The file name without extension is the primary key.** Re-importing the same file
  refreshes its metrics **without creating a duplicate** and **without overwriting the
  activity you assigned** — only computed fields are updated.

### Share database

- The **Share** action in the top bar exports a snapshot of `gpxstats.db` and opens the
  system share sheet (send to Drive, email, another device, a file manager, …).
- The snapshot is a plain copy written to the app cache; the WAL is checkpointed first so
  the file is self-contained. It only leaves the app through the share sheet you invoke.

### Activity

- Every track has a user-defined activity. Tap the chip on a track row to change it.
- New imports default to **`bike`**. The default is editable on the Tracks tab
  (free text, plus quick chips: bike / run / hike / walk / ski / swim / car / other).

### Statistics tab

- **Summary** — total number of tracks, total distance and duration, total ascent /
  descent, plus average speed, max speed and peak altitude across everything.
- **Per activity type** — count, distance, duration, average speed and ascent, with a
  **Total** row.
- **By period** — the same columns (including **elevation gain**) bucketed by **week**,
  **month** or **year** (switchable with a chip row). Tracks without a start time fall
  into a *No date* row.
- **Detail cards** per activity — the full set: count, distance, duration, moving time,
  ascent, descent, average speed, max speed, max altitude.
- **Selected** — check any tracks on the Tracks tab and a Selected aggregate (same full
  set) appears here.

Distance, duration, ascent and descent are summed across the group; average speed is
derived (total distance ÷ total moving time); max speed and max altitude are the highest
values across the group.

### Settings (gear icon)

- **Median filter window** and **hysteresis threshold** for the elevation gain / loss
  computation (see above). Stepper controls; a *Reset to defaults* button restores
  5 points · 10 m.

### About (⋮ → *Info about GpxStats*)

- App version and build number, a link to the source repository, and the list of
  open-source libraries used (all Apache-2.0).

---

## Build

Requirements:

- JDK 17
- Android SDK with **API 35** and **build-tools 35.0.0**

```bash
# APK -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleDebug

# build and install to a connected device / emulator
./gradlew :app:installDebug
```

Gradle must run on **JDK 17** (AGP 8.7 / Gradle 8.9 don't support newer JDKs). The path
is deliberately **not** committed, so the build stays portable (F-Droid, CI). If your
default `java` is newer, point Gradle at a JDK 17 without editing tracked files:

```bash
echo 'org.gradle.java.home=/path/to/jdk-17' >> ~/.gradle/gradle.properties
```

Machine-specific config (not committed):

- `local.properties` — `sdk.dir=/path/to/Android/sdk`

### Versions

| | |
|---|---|
| Gradle | 8.9 (wrapper committed) |
| Android Gradle Plugin | 8.7.3 |
| Kotlin | 2.0.21 (+ KSP `2.0.21-1.0.28`) |
| minSdk / target / compile | 26 / 35 / 35 |
| UI | Jetpack Compose, Material 3 |
| Persistence | Room 2.6.1, DataStore Preferences 1.1.1 |

---

## Project layout

```
app/src/main/java/com/januarius/gpxstats/
  GpxStatsApp.kt            Application — wires database + repository + settings
  MainActivity.kt           Compose host; file/folder pickers (Storage Access Framework)
  data/
    Track.kt                @Entity, primary key = name (file name without extension)
    TrackDao.kt             Room DAO (Flow observers + suspend upserts)
    AppDatabase.kt          Room database (single instance)
  gpx/
    GpxParser.kt            InputStream -> GpxSummary; ElevationOptions (median window + threshold)
  repo/
    TrackRepository.kt      import one file / sync a folder tree (with progress) / export the db
    SettingsStore.kt        DataStore: sync folder uri, default activity, elevation options
  ui/
    GpxStatsViewModel.kt    StateFlows + statistics aggregation (by activity + by period)
    GpxStatsScreen.kt       Tracks / Statistics tabs + Settings & About sub-screens
    Theme.kt, Format.kt     Material 3 theme, display formatters

app/src/main/res/drawable-nodpi/mylogo.jpg   logo shown on the About page
app/src/main/res/xml/file_paths.xml          FileProvider paths for the shared db copy
app/src/test/.../GpxParserElevationTest.kt    JVM tests for the gain/loss maths
samples/ride-sample.gpx                       small fixture for manual import testing
```

Data flow: picker `Uri` → `MainActivity` → `GpxStatsViewModel` → `TrackRepository`
(IO thread) → Room. Room `Flow`s feed `StateFlow`s that the Compose UI collects.

---

## Notes

- GPX time parsing accepts ISO-8601 with an offset or trailing `Z`.
- `material-icons-extended` is pulled in for one icon and inflates the debug APK to
  ~17 MB; drop it (use a built-in icon) if size matters.
- See `CLAUDE.md` for architecture invariants and how to extend metrics/statistics.

## License

[Apache License 2.0](LICENSE) — see also [`NOTICE`](NOTICE).

The app requests no permissions, contains no analytics, ad, or tracking code, and makes
no network connections — it should be a clean fit for F-Droid.

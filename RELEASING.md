# Releasing

## Rule: `versionCode` only ever goes up

Every published release **must** have a `versionCode` strictly greater than the
previous one, and a `versionCode` is **never** reused or edited once it has been
tagged/pushed. F-Droid (and Android itself) treat `versionCode` as the update
ordering key; a repeat or a decrease means users silently stop getting updates.

| Release | `versionCode` | `versionName` | Tag |
|---------|---------------|---------------|-----|
| 1.0     | 1             | `1.0`         | `v1.0` |
| 1.1     | 2             | `1.1`         | `v1.1` |

## Steps for a new release

1. **Bump the version** in `app/build.gradle.kts`:
   - `versionCode` = previous + 1 (never anything else).
   - `versionName` = the human version, e.g. `"1.2"`.
2. **Add the changelog**: `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`
   (the file name is the *versionCode*, not the versionName). Keep it short.
3. Update `CurrentVersion` / `CurrentVersionCode` and append a new `Builds:` entry in
   `fdroid/com.januarius.gpxstats.yml` (append-only - don't touch old entries).
4. `./gradlew :app:assembleDebug :app:testDebugUnitTest` - must pass clean.
5. Commit, then:
   ```bash
   git tag -a v<versionName> -m "GpxStats <versionName> (versionCode <n>)"
   git push origin main --follow-tags
   ```
6. Create a GitHub release for the tag (the F-Droid `Changelog:` link points at
   `/releases`).
7. First time only: submit `fdroid/com.januarius.gpxstats.yml` to `fdroiddata`.
   After that F-Droid picks up new tags automatically (`UpdateCheckMode: Tags`).

## Signing

F-Droid builds and signs the app with its own key from the tagged source. The debug
APK produced locally is for testing only.

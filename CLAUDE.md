# CLAUDE.md

Offline Android file manager. Kotlin + Jetpack Compose (Material 3), single `:app` module.
Package / applicationId `com.offlinefiles.explorer`, minSdk 26, compile/targetSdk 36.
User-facing docs (Korean) are in `README.md`.

## Commands

```bash
./gradlew assembleDebug            # debug APK
./gradlew testDebugUnitTest        # 37 JVM tests (Robolectric + Roborazzi)
./gradlew testDebugUnitTest --tests '*GalleryCoreTest'   # one class
./gradlew lintRelease              # report: app/build/reports/lint-results-release.txt
./gradlew assembleRelease bundleRelease   # needs the upload key, see Signing
```

- Cloud sessions: `.claude/hooks/session-start.sh` installs the Android SDK (`/opt/android-sdk`),
  writes `local.properties`, installs `7z`, and pre-fetches Gradle deps.
- Maven Central sometimes returns HTTP 429 to this environment (Gradle deps and Robolectric's
  `android-all-instrumented` jar). Re-run the command; `--max-workers=2` helps. It is not a code problem.
- ArchiveTest skips its three 7-Zip compatibility tests when `7z` is missing; some tests need `python3`.

## Layout (`app/src/main/java/com/offlinefiles/explorer/`)

- `core/` pure Kotlin, no Android: file ops, sorting, archives (`core/archive`), gallery grouping (`core/gallery/Media.kt`)
- `data/` Android access: storage volumes, MediaStore (`MediaLibrary.kt`), prefs, intents (`Platform.kt`)
- `ops/` background operations; `ui/` Compose screens (`browser`, `gallery`, `home`, `archive`, `settings`, `dialogs`)
- `ui/MainViewModel.kt` holds app state
- Tests: `app/src/test/...` — `FileOpsTest` 13, `ArchiveTest` 10, `AppFlowTest` 4, `GalleryCoreTest` 6, `GalleryFlowTest` 4

## Screenshots

`AppFlowTest` / `GalleryFlowTest` rewrite `app/screenshots/*.png` on every test run (00–30 file manager,
40–48 gallery). After UI changes, open the changed ones and check layout and Korean/English text, then
commit them with the change. Unrelated diffs are usually only the clock/date shown on screen.
Video thumbnails show a placeholder under Robolectric (no video decoding); check those on a device.

## Rules

- Offline app: never add the `INTERNET` permission. Do not add new libraries.
- Do not change `applicationId`, the signing config, or existing file-management behavior unless asked.
- Every string change goes in both `res/values/strings.xml` (English) and `res/values-ko/strings.xml` (Korean).
- Keep README's feature list and test counts in sync with the code.
- Use only APIs available at minSdk 26 or guard them with `Build.VERSION.SDK_INT`. EXIF uses the framework
  `android.media.ExifInterface` (no androidx exifinterface), so androidx-only tag constants do not exist.

## Signing

- `upload-keystore.jks` + `keystore.properties` go in the repo root; both are gitignored. **Never commit them.**
  They are not in this repo — ask the user for the key backup zip (`FileExplorer-upload-key.zip`).
- Without them, release builds still work but come out unsigned (`app-release-unsigned.apk`).
- Expected upload-cert SHA-256: `7bc573b031b3a3092fa372ae6cba5988bd924d73a2930a3ab47c7a538d0cb6f2`
  - APK: `$ANDROID_HOME/build-tools/36.0.0/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk`
  - AAB: `keytool -printcert -jarfile app/build/outputs/bundle/release/app-release.aab`
- Bump `versionCode` / `versionName` in `app/build.gradle.kts` for each Play release (1.1.0 = code 2).
- Keep `app/build/outputs/mapping/release/mapping.txt` for each release (Play crash deobfuscation).

## History

- `docs/release-1.1.0-checklist.md`: the build/verify checklist used for 1.1.0.

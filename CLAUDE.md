# CLAUDE.md

Offline Android file manager. Kotlin + Jetpack Compose (Material 3), single `:app` module.
Package / applicationId `com.offlinefiles.explorer`, minSdk 26, compile/targetSdk 36.
User-facing docs (Korean) are in `README.md`.

## Commands

```bash
./gradlew assembleDebug            # debug APK
./gradlew testDebugUnitTest        # 66 JVM tests (Robolectric + Roborazzi)
./gradlew testDebugUnitTest --tests '*GalleryCoreTest'   # one class
./gradlew lintRelease              # report: app/build/reports/lint-results-release.txt
./gradlew assembleRelease bundleRelease   # needs the upload key, see Signing
```

- CI: `.github/workflows/apk.yml` builds debug APKs on every push and republishes them as the GitHub release
  `apk-<branch>` (README links `apk-main`): `FileExplorer-<branch>.apk` (arm64) and `-universal.apk`.
  Debug-signed, so the key differs per run. APKs are split per ABI (`splits.abi`) because of libVLC.
- Cloud sessions: `.claude/hooks/session-start.sh` installs the Android SDK (`/opt/android-sdk`),
  writes `local.properties`, installs `7z`, and pre-fetches Gradle deps.
- Maven Central sometimes returns HTTP 429 to this environment (Gradle deps and Robolectric's
  `android-all-instrumented` jar). Re-run the command; `--max-workers=2` helps. It is not a code problem.
- ArchiveTest skips its three 7-Zip compatibility tests when `7z` is missing; some tests need `python3`.

## Layout (`app/src/main/java/com/offlinefiles/explorer/`)

- `core/` pure Kotlin, no Android: file ops, sorting, archives (`core/archive`), gallery grouping (`core/gallery/Media.kt`),
  player logic and subtitle parsing (`core/player`)
- `data/` Android access: storage volumes, MediaStore (`MediaLibrary.kt`), prefs, player memory (`PlayerStore.kt`), intents (`Platform.kt`)
- `ops/` background operations; `ui/` Compose screens (`browser`, `gallery`, `home`, `archive`, `settings`, `dialogs`, `player`)
- `ui/MainViewModel.kt` holds app state; `ui/player/PlayerModel.kt` drives an `Engine` (`ui/player/Engines.kt`).
  The app plays everything with `VlcEngine` (libVLC); Android's `MediaPlayer` is not used for playback and VLC can't be
  switched off (owner's decision). `Engines.factory` makes the engine: VLC in the app; `PlayerFlowTest` swaps in
  `MediaPlayerEngine` (test sources, Robolectric's `ShadowMediaPlayer`) because libVLC can't load on a PC.
  Never let `LibVLC` load its natives itself (it calls `System.exit` on failure) — `VlcEngine` loads them first.
  Videos/music never go to other apps (no in-app/external setting; no "Open with" for them; `openWith`/`openAs`
  play media in the app). VLC options follow VLC for Android's defaults plus `--avi-index=3`; VLC dialogs are
  answered by `VlcEngine.answerDialogs` (errors logged to diagnostics). `core/player` `DecoderChoice`: hardware only
  for h264/hevc/vp8/vp9/av1, legacy containers (avi, mpg, vob, wmv, flv, rmvb, 3gp...) in software; `PlayerModel`
  and `InlinePlayer` retry in software on error, on a legacy codec at prepare, or (full player) on no picture in 6 s.
  Settings › Version shows `BuildConfig.BUILD_ID` (commit hash + time) so a tester can tell builds apart.
- Subtitles: files next to the video (USB document trees copied to the cache), a file picked by hand
  (`PlayerModel.addSubtitleFile`, system picker), and the choice per video in `PlayerStore.subtitleChoice`;
  then a Korean embedded track (`autoSelectEmbedded`). Matching rules are in `core/player/Subtitles.kt`.
- `data/CrashLog.kt` records the last uncaught exception; `data/UsbDiagnostics.kt` builds Settings › Diagnostics (crash,
  Android exit reasons including native crashes, USB).
- `ui/gallery/InlineVideo.kt`: videos play in place in the photo viewer (same engines); file-list videos open there
  when `Settings.videosInViewer` (default on). Tests that drive the full player from the list turn it off.
- Tests: `app/src/test/...` — `FileOpsTest` 13, `ArchiveTest` 10, `AppFlowTest` 4, `GalleryCoreTest` 6, `GalleryFlowTest` 4,
  `StoreOpsTest` 6, `UsbStorageTest` 5, `UsbFlowTest` 2, `PlayerCoreTest` 10, `PlayerFlowTest` 6

## USB drives

- Many devices (Pixel, AOSP) mount USB drives where apps can't reach them by path; `StorageVolumes` reports those as
  `VolumeAccess.NEEDS_GRANT` (path `/mnt/media_rw/<uuid>`, used only as a name). The user grants the drive root in the
  system picker; after that it is `DOCUMENTS` and `data/DocumentTree.kt` serves it through the external storage
  provider (document id = `<uuid>:<path on drive>`). Drives readable by path stay `PATH` (Samsung etc.).
- `data/Stores` maps a path to its `core/FileStore`: a `DocumentTree` or `LocalStore`. Work touching only local files
  keeps using `FileOps`/`ArchiveOps` unchanged; anything involving a document tree goes through `core/StoreOps`
  (same rules as `FileOps`), and archive work through `ops/DocumentStaging` (cache copies).
- Tests fake the provider with `FakeDriveProvider` (a host folder) and Robolectric's `StorageVolumeBuilder`.

## Screenshots

`AppFlowTest` / `GalleryFlowTest` / `UsbFlowTest` / `PlayerFlowTest` rewrite `app/screenshots/*.png` on every test run
(00–30 file manager, 40–48 gallery, 50–55 USB, 60–69 player/viewer). After UI changes, open the changed ones and check layout and Korean/English text, then
commit them with the change. Unrelated diffs are usually only the clock/date shown on screen.
Video thumbnails show a placeholder under Robolectric (no video decoding); check those on a device.
The player screenshots have a black video area for the same reason (Robolectric's `ShadowMediaPlayer` prepares and
seeks but draws nothing); `PlayerFlowTest` sets lengths with `ShadowMediaPlayer.setMediaInfoProvider`.

## Rules

- Offline app: never add the `INTERNET` permission. Libraries may be added when they help (pin exact versions,
  nothing that needs network access); list each one in Settings › Licenses (`SettingsScreen.kt` `libraries`).
- Do not change `applicationId`, the signing config, or existing file-management behavior unless asked.
- Every string change goes in both `res/values/strings.xml` (English) and `res/values-ko/strings.xml` (Korean).
- Keep README's feature list and test counts in sync with the code.
- Use only APIs available at minSdk 26 or guard them with `Build.VERSION.SDK_INT`. EXIF uses
  `androidx.exifinterface.media.ExifInterface` (not the framework class).

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
- `docs/player.md`: built-in player design, VLC feature comparison, MX Player UI mapping, what is left out and why.

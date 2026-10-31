package com.offlinefiles.explorer.ui

import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.os.Environment
import android.os.Looper
import android.os.storage.StorageManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.offlinefiles.explorer.App
import com.offlinefiles.explorer.core.player.RepeatMode
import com.offlinefiles.explorer.data.MediaLibrary
import com.offlinefiles.explorer.ui.player.SubtitleSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource
import java.io.File

/**
 * The built-in player driven through the real UI on the JVM, with screenshots in app/screenshots/.
 * Robolectric's MediaPlayer stand-in prepares, seeks and reports a length but decodes nothing,
 * so the video area stays black here; check real playback on a device.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ko-rKR-w891dp-h411dp-land-xhdpi")
class PlayerFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var app: App
    private lateinit var root: File
    private lateinit var trip: File
    private lateinit var music: File

    @Before
    fun setUp() {
        app = compose.activity.application as App
        val appOps = app.getSystemService(AppOpsManager::class.java)
        shadowOf(appOps).setMode(
            "android:manage_external_storage", app.applicationInfo.uid, app.packageName, AppOpsManager.MODE_ALLOWED,
        )
        shadowOf(app.getSystemService(StorageStatsManager::class.java))
            .setStorageDeviceFreeAndTotalBytes(StorageManager.UUID_DEFAULT, 71_400_000_000L, 128_000_000_000L)
        root = Environment.getExternalStorageDirectory()
        root.deleteRecursively()
        root.mkdirs()
        trip = File(root, "Movies/제주 브이로그")
        music = File(root, "Music")
        for (name in listOf("01_제주 도착.mp4", "02_성산일출봉.mp4", "03_우도.mkv", "04_옛날 영상.avi")) {
            File(trip, name).apply { parentFile?.mkdirs(); writeBytes(ByteArray(2048) { it.toByte() }) }
        }
        File(trip, "02_성산일출봉.ko.srt").writeText(
            "1\n00:01:20,000 --> 00:01:26,000\n성산일출봉 정상에 도착했어요!\n바람이 정말 시원하네요\n\n" +
                "2\n00:01:27,000 --> 00:01:30,000\n<i>저 멀리 우도가 보입니다</i>\n",
        )
        File(trip, "01_제주 도착.smi").writeText(
            "<SAMI><HEAD><STYLE>.KRCC { Name:한국어; } .ENCC { Name:English; }</STYLE></HEAD><BODY>" +
                "<SYNC Start=1000><P Class=KRCC>공항에 도착<SYNC Start=1000><P Class=ENCC>Landed" +
                "<SYNC Start=4000><P Class=KRCC>&nbsp;<SYNC Start=4000><P Class=ENCC>&nbsp;</BODY></SAMI>",
        )
        for (name in listOf("봄날.mp3", "outro.flac")) {
            File(music, name).apply { parentFile?.mkdirs(); writeBytes(ByteArray(2048) { it.toByte() }) }
        }
        File(root, "DCIM/Camera").mkdirs()
        // Lengths per file. The old AVI gets none, so opening it fails like an unsupported format.
        fun length(f: File, ms: Int) = ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(f.path), ShadowMediaPlayer.MediaInfo(ms, 0))
        length(File(trip, "01_제주 도착.mp4"), 321_000)
        length(File(trip, "02_성산일출봉.mp4"), 754_000)
        length(File(trip, "03_우도.mkv"), 412_000)
        length(File(music, "봄날.mp3"), 215_000)
        length(File(music, "outro.flac"), 182_000)
    }

    private fun start(vm: MainViewModel) {
        compose.setContent { AppRoot(vm) }
        compose.waitForIdle()
    }

    private fun waitFor(timeoutMs: Long = 8000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            compose.waitForIdle()
            if (condition()) return
            if (System.currentTimeMillis() > end) throw AssertionError("condition not met in $timeoutMs ms")
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun settle(ms: Long = 400) {
        compose.waitForIdle()
        Thread.sleep(ms)
        compose.waitForIdle()
    }

    private fun screen(name: String) = captureScreenRoboImage("screenshots/$name.png")

    private fun openFolder(vm: MainViewModel, dir: File) {
        compose.runOnIdle { vm.openDir(dir.path) }
        waitFor { vm.browser.value.path == dir.path && !vm.browser.value.loading }
        settle()
    }

    private fun back() = compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }

    /** The player is drawn above the screen that opened it, so its buttons come last. */
    private fun clickLast(description: String) = compose.onAllNodesWithContentDescription(description).onLast().performClick()

    @Test
    fun videoFromTheFileList() {
        val vm = MainViewModel(app)
        start(vm)
        waitFor { vm.volumes.value.isNotEmpty() }
        openFolder(vm, trip)
        val peak = vm.visibleItems.value.first { it.name == "02_성산일출봉.mp4" }
        compose.runOnIdle { vm.onItemClick(peak) }
        waitFor { vm.player.ui.value?.prepared == true }

        // The folder's videos are queued in list order; subtitles and music are not.
        val ui = vm.player.ui.value!!
        assertEquals(listOf("01_제주 도착.mp4", "02_성산일출봉.mp4", "03_우도.mkv", "04_옛날 영상.avi"), ui.queue.map { it.name })
        assertEquals(1, ui.index)
        assertEquals(754_000L, ui.durationMs)
        assertTrue(ui.playing)

        // The subtitle file named after the video is picked up and shown at the right time.
        waitFor { vm.player.ui.value?.subtitle != null }
        val sub = vm.player.ui.value!!.subtitle as SubtitleSource.External
        assertTrue(sub.path.endsWith("02_성산일출봉.ko.srt"))
        compose.runOnIdle {
            vm.player.seekTo(83_000)
            vm.player.pause()
        }
        waitFor { vm.player.position.value == 83_000L }
        assertEquals("성산일출봉 정상에 도착했어요!\n바람이 정말 시원하네요", vm.player.subtitleText.value)
        settle(600)
        screen("50_player")

        clickLast("더보기")
        settle()
        screen("51_player_menu")
        compose.onNodeWithText("재생 속도").performClick()
        compose.runOnIdle { vm.player.setSpeed(1.5f) }
        settle()
        screen("52_player_speed")
        assertEquals(1.5f, vm.player.ui.value!!.speed)
        back()
        settle()

        clickLast("자막")
        settle()
        // Both languages of the other video's SAMI file are offered too.
        assertTrue(vm.player.ui.value!!.subtitles.any { it.label.contains("02_성산일출봉.ko.srt") })
        // A later sync moves the lines: 3.5 s later, the line at 1:23 hasn't started yet.
        compose.runOnIdle { vm.player.setSubtitleDelay(3_500) }
        assertNull(vm.player.subtitleText.value)
        compose.runOnIdle { vm.player.setSubtitleDelay(500) }
        assertNotNull(vm.player.subtitleText.value)
        settle()
        screen("53_player_subtitles")
        back()
        settle()
        assertNotNull(vm.player.ui.value)

        compose.onNodeWithContentDescription("조작 잠금").performClick()
        settle()
        screen("54_player_locked")
        back() // locked: back only shows the unlock button
        assertNotNull(vm.player.ui.value)
        compose.onNodeWithContentDescription("눌러서 잠금 해제").performClick()
        settle()

        // Next file: no subtitle of its own, so none is turned on.
        compose.runOnIdle { vm.player.next() }
        waitFor { vm.player.ui.value?.index == 2 && vm.player.ui.value?.prepared == true }
        settle()
        assertNull(vm.player.ui.value!!.subtitle)
        assertEquals(83_000L, app.playerStore.position(peak.path))

        // The AVI fails to open: the player offers another app and the next file instead of crashing.
        compose.runOnIdle { vm.player.next() }
        waitFor { vm.player.ui.value?.error == true }
        assertFalse(vm.player.ui.value!!.canNext)

        back()
        settle()
        assertNull(vm.player.ui.value)
        assertTrue(vm.route.value is Route.Browse)

        // Opening it again resumes where it was left off.
        compose.runOnIdle { vm.onItemClick(peak) }
        waitFor { vm.player.ui.value?.prepared == true && vm.player.position.value == 83_000L }
        assertEquals(83_000L, vm.player.ui.value!!.resumedFrom)
        compose.runOnIdle { vm.player.close() }
    }

    @Test
    @Config(qualifiers = "ko-rKR-w411dp-h891dp-xhdpi")
    fun musicPlayer() {
        val vm = MainViewModel(app)
        start(vm)
        waitFor { vm.volumes.value.isNotEmpty() }
        openFolder(vm, music)
        val song = vm.visibleItems.value.first { it.name == "봄날.mp3" }
        compose.runOnIdle { vm.onItemClick(song) }
        waitFor { vm.player.ui.value?.prepared == true }
        val ui = vm.player.ui.value!!
        assertFalse(ui.isVideo)
        assertEquals(listOf("outro.flac", "봄날.mp3"), ui.queue.map { it.name })
        assertFalse(ui.canNext)

        compose.runOnIdle {
            vm.player.cycleRepeat()
            vm.player.seekTo(64_000)
            vm.player.pause()
        }
        waitFor { vm.player.position.value == 64_000L }
        assertEquals(RepeatMode.ALL, vm.player.ui.value!!.repeat)
        assertTrue(vm.player.ui.value!!.canNext)
        settle(600)
        screen("55_player_music")

        // Past the first seconds of a song, "previous" restarts it.
        compose.runOnIdle { vm.player.previous() }
        waitFor { vm.player.position.value == 0L }
        assertEquals(1, vm.player.ui.value!!.index)
        // Short songs don't get a resume point.
        compose.runOnIdle { vm.player.close() }
        assertNull(app.playerStore.position(song.path))
    }

    @Test
    fun galleryAndSettings() {
        val vm = MainViewModel(app).also { it.gallery.source = { ctx -> MediaLibrary.scanFolders(ctx) } }
        start(vm)
        waitFor { vm.volumes.value.isNotEmpty() }
        val clips = listOf("01_제주 도착.mp4", "02_성산일출봉.mp4").map { name ->
            com.offlinefiles.explorer.core.FileItem.from(File(trip, name)).let { com.offlinefiles.explorer.core.gallery.MediaItem(-1, it.path, it.name, true, "video/mp4", it.size, 0, 0) }
        }
        val photo = com.offlinefiles.explorer.core.gallery.MediaItem(-1, File(root, "DCIM/Camera/a.jpg").path, "a.jpg", false, "image/jpeg", 1, 0, 0)

        // The photo viewer's play button queues the videos around it (photos are skipped).
        compose.runOnIdle { vm.openMedia(clips[1], listOf(clips[0], photo, clips[1])) }
        waitFor { vm.player.ui.value?.prepared == true }
        assertEquals(listOf("01_제주 도착.mp4", "02_성산일출봉.mp4"), vm.player.ui.value!!.queue.map { it.name })
        assertEquals(1, vm.player.ui.value!!.index)
        compose.runOnIdle { vm.player.close() }

        // With the setting off, videos go to another app as before.
        compose.runOnIdle { vm.updateSettings { it.copy(playerInApp = false) } }
        compose.runOnIdle { vm.openMedia(clips[0], clips) }
        openFolder(vm, trip)
        compose.runOnIdle { vm.onItemClick(vm.visibleItems.value.first { it.name == "03_우도.mkv" }) }
        settle()
        assertNull(vm.player.ui.value)
        compose.runOnIdle { vm.updateSettings { it.copy(playerInApp = true) } }

        // Deleting the file on screen closes the player.
        compose.runOnIdle { vm.onItemClick(vm.visibleItems.value.first { it.name == "03_우도.mkv" }) }
        waitFor { vm.player.ui.value?.prepared == true }
        compose.runOnIdle { vm.delete(listOf(File(trip, "03_우도.mkv").path)) }
        waitFor { vm.operations.running.value == null && vm.player.ui.value == null }
    }

    @Test
    @Config(qualifiers = "en-rUS-w891dp-h411dp-land-xhdpi")
    fun englishPlayer() {
        val vm = MainViewModel(app)
        start(vm)
        waitFor { vm.volumes.value.isNotEmpty() }
        openFolder(vm, trip)
        compose.runOnIdle { vm.onItemClick(vm.visibleItems.value.first { it.name == "01_제주 도착.mp4" }) }
        waitFor { vm.player.ui.value?.prepared == true }
        compose.runOnIdle {
            vm.player.cycleRepeat()
            vm.player.abRepeat()
            vm.player.pause()
        }
        settle()
        clickLast("More")
        settle()
        screen("56_en_player_menu")
        back()
        compose.runOnIdle { vm.player.close() }
    }
}

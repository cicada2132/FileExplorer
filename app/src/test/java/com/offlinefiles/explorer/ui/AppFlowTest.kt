package com.offlinefiles.explorer.ui

import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Environment
import android.os.Looper
import android.os.storage.StorageManager
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.AppNotIdleException
import androidx.test.espresso.IdlingPolicies
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.offlinefiles.explorer.App
import com.offlinefiles.explorer.core.archive.ArchiveOps
import com.offlinefiles.explorer.core.archive.ArchiveType
import com.offlinefiles.explorer.core.archive.CompressionLevel
import com.offlinefiles.explorer.core.Conflict
import com.offlinefiles.explorer.core.ConflictAction
import com.offlinefiles.explorer.core.ConflictDecision
import com.offlinefiles.explorer.core.OperationListener
import com.offlinefiles.explorer.core.Progress
import com.offlinefiles.explorer.data.ViewMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Drives the real UI (Compose + ViewModel + file engine) on the JVM and saves screenshots
 * to app/screenshots/. Files live in Robolectric's fake external storage.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ko-rKR-w411dp-h891dp-xhdpi")
class AppFlowTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var app: App
    private lateinit var root: File
    private lateinit var download: File

    private object Quiet : OperationListener {
        override fun onProgress(progress: Progress) {}
        override suspend fun onConflict(conflict: Conflict) = ConflictDecision(ConflictAction.OVERWRITE, true)
    }

    private fun File.bytes(n: Int, at: String): File = also {
        parentFile?.mkdirs()
        writeBytes(ByteArray(n) { i -> (i * 7).toByte() })
        setLastModified(stamp(at))
    }

    private fun stamp(at: String): Long = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).parse(at)!!.time

    private fun photo(f: File, hue: Int, at: String) {
        f.parentFile?.mkdirs()
        val bmp = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.45f, 0.95f)))
        val p = Paint().apply { color = Color.HSVToColor(floatArrayOf((hue + 40f) % 360, 0.6f, 0.7f)) }
        c.drawCircle(220f, 90f, 50f, p)
        p.color = Color.HSVToColor(floatArrayOf((hue + 180f) % 360, 0.4f, 0.5f))
        c.drawRect(0f, 170f, 320f, 240f, p)
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        f.setLastModified(stamp(at))
    }

    private fun setAllFilesAccess(granted: Boolean) {
        val appOps = app.getSystemService(AppOpsManager::class.java)
        shadowOf(appOps).setMode(
            "android:manage_external_storage", app.applicationInfo.uid, app.packageName,
            if (granted) AppOpsManager.MODE_ALLOWED else AppOpsManager.MODE_ERRORED,
        )
    }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        setAllFilesAccess(true)
        shadowOf(app.getSystemService(StorageStatsManager::class.java))
            .setStorageDeviceFreeAndTotalBytes(StorageManager.UUID_DEFAULT, 71_400_000_000L, 128_000_000_000L)
        root = Environment.getExternalStorageDirectory()
        root.deleteRecursively()
        root.mkdirs()
        for (d in listOf("DCIM/Camera", "Pictures", "Documents", "Music", "Movies", "Android/data")) File(root, d).mkdirs()
        download = File(root, "Download")
        File(download, "강의자료/1주차_오리엔테이션.pdf").bytes(2_400_000, "2026-09-01 10:00")
        File(download, "강의자료/2주차_실습.zip").bytes(5_800_000, "2026-09-08 10:00")
        File(download, "강의자료/과제_제출양식.hwp").bytes(84_000, "2026-09-08 10:05")
        File(download, "강의자료").setLastModified(stamp("2026-09-08 10:05"))
        File(download, "보고서_2026_3분기.pdf").bytes(1_840_000, "2026-10-20 09:12")
        File(download, "회의록.docx").bytes(48_300, "2026-10-24 17:40")
        File(download, "notes.txt").apply { writeText("메모\n") ; setLastModified(stamp("2026-10-26 22:10")) }
        File(download, "bgm.mp3").bytes(3_210_000, "2026-08-14 13:00")
        File(download, "app-release.apk").bytes(12_600_000, "2026-10-27 08:30")
        File(download, "movie.7z.001").bytes(1_000_000, "2026-10-27 07:00")
        File(download, "movie.7z.002").bytes(420_000, "2026-10-27 07:00")
        File(download, ".nomedia").bytes(0, "2026-01-01 00:00")
        photo(File(download, "IMG_20261027_142201.jpg"), 200, "2026-10-27 14:22")
        photo(File(download, "IMG_20261026_091530.jpg"), 30, "2026-10-26 09:15")
        val trip = File(app.cacheDir, "trip/여행 사진")
        photo(File(trip, "제주/바다.jpg"), 190, "2026-07-01 10:00")
        photo(File(trip, "제주/오름.jpg"), 100, "2026-07-01 11:00")
        File(trip, "일정표.txt").apply { parentFile?.mkdirs(); writeText("1일차: 공항 → 숙소") }
        runBlocking {
            ArchiveOps.compress(listOf(trip), File(download, "여행 사진.zip"), ArchiveType.ZIP, CompressionLevel.NORMAL, null, Quiet)
        }
        File(download, "여행 사진.zip").setLastModified(stamp("2026-10-25 20:00"))
        download.setLastModified(stamp("2026-10-27 14:22"))
    }

    private fun start(vm: MainViewModel) {
        compose.setContent { AppRoot(vm) }
        compose.waitForIdle()
    }

    /** Robolectric only runs posted main-thread work when idled, so poll and idle. */
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

    private fun idleTolerant(block: () -> Unit) {
        IdlingPolicies.setMasterPolicyTimeout(3, TimeUnit.SECONDS)
        try {
            block()
        } catch (_: AppNotIdleException) {
            // expected for dialogs that contain a text field (see above)
        } finally {
            IdlingPolicies.setMasterPolicyTimeout(60, TimeUnit.SECONDS)
        }
    }

    private fun settle(ms: Long = 400) {
        compose.waitForIdle()
        Thread.sleep(ms)
        compose.waitForIdle()
    }

    private fun shot(name: String) = compose.onRoot().captureRoboImage("screenshots/$name.png")

    private fun screen(name: String) = captureScreenRoboImage("screenshots/$name.png")

    private fun openFolder(vm: MainViewModel, dir: File) {
        compose.runOnIdle { vm.openDir(dir.path) }
        waitFor { vm.browser.value.path == dir.path && !vm.browser.value.loading }
        settle()
    }

    @Test
    fun mainFlow() {
        val vm = MainViewModel(app)
        start(vm)
        waitFor(5000) { vm.volumes.value.isNotEmpty() }
        settle()
        shot("01_home")

        openFolder(vm, download)
        assertTrue(vm.visibleItems.value.none { it.name == ".nomedia" })
        shot("02_browser_list")

        compose.runOnIdle {
            vm.onItemLongClick(vm.visibleItems.value.first { it.name == "보고서_2026_3분기.pdf" })
            vm.onItemLongClick(vm.visibleItems.value.first { it.name == "회의록.docx" })
        }
        settle()
        shot("03_selection")
        compose.runOnIdle { vm.clearSelection() }

        compose.runOnIdle { vm.updateSettings { it.copy(viewMode = ViewMode.GRID) } }
        settle(600)
        shot("04_grid")
        compose.runOnIdle { vm.updateSettings { it.copy(viewMode = ViewMode.LIST) } }
        settle()

        val zip = vm.visibleItems.value.first { it.name == "여행 사진.zip" }
        compose.runOnIdle { vm.showItemActions(zip) }
        settle()
        screen("05_item_menu")
        compose.runOnIdle { vm.showDialog(null) }

        compose.runOnIdle { vm.onItemClick(zip) }
        settle()
        screen("06_archive_sheet")

        // Browse inside the zip
        compose.runOnIdle { vm.openArchiveViewer(zip.path) }
        waitFor(5000) { vm.archive.value?.loading == false }
        settle()
        compose.runOnIdle { vm.archiveOpenFolder("여행 사진/제주") }
        settle()
        shot("07_archive_viewer")
        compose.runOnIdle { vm.back() }
        settle()

        // Extract into its own folder through the real operation pipeline
        compose.runOnIdle { vm.extractArchive(zip.path, ExtractChoice.OWN_FOLDER) }
        waitFor(8000) { vm.operations.running.value == null && File(download, "여행 사진/제주/바다.jpg").exists() }
        settle()
        assertTrue(File(download, "여행 사진/일정표.txt").readText().startsWith("1일차"))

        // Copy notes.txt into 강의자료, then again to trigger the conflict dialog
        val lectures = File(download, "강의자료")
        compose.runOnIdle { vm.startCopy(listOf(File(download, "notes.txt").path), move = false) }
        openFolder(vm, lectures)
        shot("08_paste_bar")
        compose.runOnIdle { vm.pasteHere() }
        waitFor(8000) { vm.operations.running.value == null && File(lectures, "notes.txt").exists() }
        settle()
        compose.runOnIdle { vm.startCopy(listOf(File(download, "notes.txt").path), move = false) }
        settle()
        compose.runOnIdle { vm.pasteHere() }
        waitFor(8000) { vm.operations.conflict.value != null }
        settle()
        screen("09_conflict")
        compose.onNodeWithText("둘 다 유지").performClick()
        waitFor(8000) { vm.operations.running.value == null && File(lectures, "notes (1).txt").exists() }
        settle()

        compose.runOnIdle { vm.back() }
        waitFor(5000) { vm.browser.value.path == download.path && !vm.browser.value.loading }
        settle()
        compose.runOnIdle {
            vm.showDialog(DialogState.Compress(listOf(File(download, "강의자료").path, File(download, "notes.txt").path)))
        }
        // Under Robolectric, a text field inside any Compose dialog never reports idle
        // (a bare OutlinedTextField in an AlertDialog does the same), so let it render with a
        // short idle timeout, capture, and close the dialog directly.
        idleTolerant { settle() }
        idleTolerant { screen("10_compress") }
        vm.showDialog(null)
        settle()
        compose.runOnIdle { vm.showDialog(DialogState.Properties(listOf(lectures.path))) }
        settle(800)
        screen("11_properties")
        compose.runOnIdle { vm.showDialog(DialogState.Sort) }
        settle()
        screen("12_sort")
        compose.runOnIdle { vm.showDialog(null) }

        compose.runOnIdle { vm.startSearch(); vm.setSearchQuery("사진") }
        settle()
        compose.runOnIdle { vm.setRecursiveSearch(true) }
        waitFor(5000) { vm.search.value?.running == false && vm.search.value?.results?.isNotEmpty() == true }
        settle()
        shot("13_search")
        compose.runOnIdle { vm.closeSearch() }

        openFolder(vm, File(root, "Android/data"))
        shot("14_denied")
        assertEquals(BrowserUi.Status.OK, vm.browser.value.status) // Robolectric can read it; real devices show the lock screen
        compose.runOnIdle { vm.goHome(); vm.openSettings() }
        settle()
        shot("15_settings")
    }

    @Test
    @Config(qualifiers = "ko-rKR-w411dp-h891dp-night-xhdpi")
    fun darkTheme() {
        val vm = MainViewModel(app)
        start(vm)
        waitFor(5000) { vm.volumes.value.isNotEmpty() }
        openFolder(vm, download)
        shot("20_dark_browser")
        compose.runOnIdle { vm.goHome() }
        settle()
        shot("21_dark_home")
    }

    @Test
    @Config(qualifiers = "en-rUS-w411dp-h891dp-xhdpi")
    fun englishBrowser() {
        val vm = MainViewModel(app)
        start(vm)
        waitFor(5000) { vm.volumes.value.isNotEmpty() }
        openFolder(vm, download)
        shot("30_en_browser")
    }

    @Test
    fun permissionScreen() {
        setAllFilesAccess(false)
        val vm = MainViewModel(app)
        start(vm)
        settle()
        shot("00_permission")
    }
}

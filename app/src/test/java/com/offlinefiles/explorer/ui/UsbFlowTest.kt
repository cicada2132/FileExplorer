package com.offlinefiles.explorer.ui

import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Environment
import android.os.Looper
import android.os.Process
import android.os.storage.StorageManager
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.offlinefiles.explorer.App
import com.offlinefiles.explorer.data.FakeDriveProvider
import com.offlinefiles.explorer.data.StorageVolumes
import com.offlinefiles.explorer.data.Stores
import com.offlinefiles.explorer.data.VolumeAccess
import com.offlinefiles.explorer.data.VolumeKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.StorageVolumeBuilder
import java.io.File
import java.io.FileOutputStream

/**
 * A USB drive that Android only shares through the system file picker, from the home screen to
 * copying files both ways, with screenshots in app/screenshots/ (50–55). The picker itself is
 * system UI, so the test hands the view model what it returns.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ko-rKR-w411dp-h891dp-xhdpi")
class UsbFlowTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var app: App
    private lateinit var download: File
    private lateinit var stick: File
    private val uuid = "1A2B-3C4D"
    private val mount = "/mnt/media_rw/1A2B-3C4D"

    private fun stamp(at: String): Long = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).parse(at)!!.time

    private fun File.bytes(n: Int, at: String): File = also {
        parentFile?.mkdirs()
        writeBytes(ByteArray(n) { i -> (i * 7).toByte() })
        setLastModified(stamp(at))
    }

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

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app.getSystemService(AppOpsManager::class.java)).setMode(
            "android:manage_external_storage", app.applicationInfo.uid, app.packageName, AppOpsManager.MODE_ALLOWED,
        )
        shadowOf(app.getSystemService(StorageStatsManager::class.java))
            .setStorageDeviceFreeAndTotalBytes(StorageManager.UUID_DEFAULT, 71_400_000_000L, 128_000_000_000L)
        val root = Environment.getExternalStorageDirectory()
        root.deleteRecursively()
        for (d in listOf("DCIM/Camera", "Pictures", "Documents", "Music", "Movies")) File(root, d).mkdirs()
        download = File(root, "Download")
        File(download, "보고서_2026_3분기.pdf").bytes(1_840_000, "2026-10-20 09:12")
        File(download, "회의록.docx").bytes(48_300, "2026-10-24 17:40")
        download.setLastModified(stamp("2026-10-24 17:40"))

        // The USB stick's files, served by the stand-in document provider.
        stick = tmp.newFolder("stick")
        photo(File(stick, "DCIM/IMG_0001.jpg"), 200, "2026-10-12 14:02")
        photo(File(stick, "DCIM/IMG_0002.jpg"), 30, "2026-10-12 14:05")
        File(stick, "DCIM").setLastModified(stamp("2026-10-12 14:05"))
        File(stick, "발표자료/기말 발표.pptx").bytes(6_200_000, "2026-10-28 23:10")
        File(stick, "발표자료").setLastModified(stamp("2026-10-28 23:10"))
        File(stick, "음악/플레이리스트.mp3").bytes(4_100_000, "2026-09-03 08:00")
        File(stick, "음악").setLastModified(stamp("2026-09-03 08:00"))
        File(stick, "백업_2026.zip").bytes(12_400_000, "2026-10-01 21:30")
        File(stick, "readme.txt").apply { writeText("USB 메모\n"); setLastModified(stamp("2026-08-30 12:00")) }
        FakeDriveProvider.drives.clear()
        FakeDriveProvider.drives[uuid] = stick
        FakeDriveProvider.install()
        val label = if (app.resources.configuration.locales[0].language == "ko") "SanDisk USB 드라이브" else "SanDisk USB drive"
        val volume = StorageVolumeBuilder("public:8,1", File(mount), label, Process.myUserHandle(), Environment.MEDIA_MOUNTED)
            .setIsRemovable(true)
            .setFsUuid(uuid)
            .build()
        shadowOf(app.getSystemService(StorageManager::class.java)).addStorageVolume(volume)
    }

    @After
    fun tearDown() {
        Stores.update(app.contentResolver, emptyList())
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

    private fun settle(ms: Long = 400) {
        compose.waitForIdle()
        Thread.sleep(ms)
        compose.waitForIdle()
    }

    private fun shot(name: String) = compose.onRoot().captureRoboImage("screenshots/$name.png")

    private fun screen(name: String) = captureScreenRoboImage("screenshots/$name.png")

    private fun openFolder(vm: MainViewModel, path: String) {
        compose.runOnIdle { vm.openDir(path) }
        waitFor { vm.browser.value.path == path && !vm.browser.value.loading }
        settle()
    }

    private fun idleOps(vm: MainViewModel, condition: () -> Boolean) =
        waitFor(10_000) { vm.operations.running.value == null && condition() }

    @Test
    fun usbFlow() {
        val vm = MainViewModel(app)
        start(vm)
        waitFor { vm.volumes.value.any { it.kind == VolumeKind.USB } }
        settle()
        shot("50_home_usb")
        val usb = vm.volumes.value.single { it.kind == VolumeKind.USB }
        assertEquals(VolumeAccess.NEEDS_GRANT, usb.access)

        compose.runOnIdle { vm.openVolume(usb) }
        settle()
        screen("51_usb_access")
        assertTrue(vm.dialog.value is DialogState.UsbAccess)

        // The user taps "Use this folder" → "Allow" in the picker, which returns the drive.
        compose.runOnIdle {
            vm.showDialog(null)
            vm.onAccessResult(FakeDriveProvider.treeUri(uuid))
        }
        waitFor { vm.browser.value.path == mount && !vm.browser.value.loading }
        assertEquals(VolumeAccess.DOCUMENTS, vm.volumes.value.single { it.kind == VolumeKind.USB }.access)
        assertEquals(
            listOf("DCIM", "발표자료", "음악", "readme.txt", "백업_2026.zip"),
            vm.visibleItems.value.map { it.name },
        )
        settle(1200)
        shot("52_usb_browser")

        // A photo on the drive opens in the built-in viewer.
        openFolder(vm, "$mount/DCIM")
        val first = vm.visibleItems.value.first { it.name == "IMG_0001.jpg" }
        compose.runOnIdle { vm.onItemClick(first) }
        settle(1500)
        assertNotNull(vm.gallery.viewer.value)
        screen("55_usb_viewer")
        compose.runOnIdle { vm.gallery.closeViewer(null) }
        settle()
        openFolder(vm, mount)

        // Phone → drive through the paste bar.
        val report = File(download, "보고서_2026_3분기.pdf")
        compose.runOnIdle { vm.startCopy(listOf(report.path), move = false) }
        settle()
        shot("53_usb_paste")
        compose.runOnIdle { vm.pasteHere() }
        idleOps(vm) { File(stick, report.name).exists() }
        assertTrue(File(stick, report.name).readBytes().contentEquals(report.readBytes()))
        waitFor { vm.visibleItems.value.any { it.name == report.name } }

        // Simple changes on the drive.
        compose.runOnIdle { vm.createFolder(mount, "백업") }
        waitFor { File(stick, "백업").isDirectory && vm.visibleItems.value.any { it.name == "백업" } }
        compose.runOnIdle { vm.rename("$mount/readme.txt", "읽어보기.txt") }
        waitFor { File(stick, "읽어보기.txt").isFile && vm.visibleItems.value.any { it.name == "읽어보기.txt" } }
        assertFalse(File(stick, "readme.txt").exists())
        compose.runOnIdle { vm.delete(listOf("$mount/백업")) }
        idleOps(vm) { !File(stick, "백업").exists() }

        // Drive → phone.
        compose.runOnIdle { vm.startCopy(listOf(first.path), move = false) }
        openFolder(vm, download.path)
        compose.runOnIdle { vm.pasteHere() }
        idleOps(vm) { File(download, "IMG_0001.jpg").exists() }
        assertEquals(File(stick, "DCIM/IMG_0001.jpg").length(), File(download, "IMG_0001.jpg").length())

        // Pulling the drive out while it is open goes back home.
        openFolder(vm, "$mount/DCIM")
        shadowOf(app.getSystemService(StorageManager::class.java)).resetStorageVolumeList()
        compose.runOnIdle { vm.onUsbDeviceChanged() }
        waitFor { vm.route.value == Route.Home && vm.volumes.value.none { it.kind == VolumeKind.USB } }
        assertFalse(Stores.isDocument(mount))
    }

    @Test
    @Config(qualifiers = "en-rUS-w411dp-h891dp-night-xhdpi")
    fun englishDarkDrive() {
        // Access given earlier: the drive opens straight away.
        StorageVolumes.acceptGrant(app, FakeDriveProvider.treeUri(uuid), StorageVolumes.list(app))
        val vm = MainViewModel(app)
        start(vm)
        waitFor { vm.volumes.value.any { it.kind == VolumeKind.USB && it.access == VolumeAccess.DOCUMENTS } }
        compose.runOnIdle { vm.openVolume(vm.volumes.value.single { it.kind == VolumeKind.USB }) }
        waitFor { vm.browser.value.path == mount && !vm.browser.value.loading }
        settle(1200)
        shot("54_en_dark_usb_browser")
    }
}

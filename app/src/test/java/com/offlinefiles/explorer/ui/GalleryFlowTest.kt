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
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.offlinefiles.explorer.App
import com.offlinefiles.explorer.core.gallery.AlbumType
import com.offlinefiles.explorer.core.gallery.GalleryIndex
import com.offlinefiles.explorer.data.MediaLibrary
import com.offlinefiles.explorer.ui.gallery.GalleryTab
import org.junit.Assert.assertEquals
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
import java.io.File
import java.io.FileOutputStream

/**
 * Gallery screens and the photo viewer on the JVM, with screenshots in app/screenshots/.
 * Robolectric has no media provider, so the gallery reads the fake storage by folder scan.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ko-rKR-w411dp-h891dp-xhdpi")
class GalleryFlowTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var app: App
    private lateinit var root: File
    private lateinit var jeju: File

    private fun stamp(at: String): Long = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).parse(at)!!.time

    /** A small landscape picture: sky, sun and ground in colours derived from [hue]. */
    private fun photo(f: File, hue: Int, at: String, png: Boolean = false, w: Int = 480, h: Int = 360) {
        f.parentFile?.mkdirs()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.45f, 0.95f)))
        val p = Paint().apply { color = Color.HSVToColor(floatArrayOf((hue + 40f) % 360, 0.6f, 0.75f)) }
        c.drawCircle(w * 0.7f, h * 0.35f, h * 0.18f, p)
        p.color = Color.HSVToColor(floatArrayOf((hue + 180f) % 360, 0.4f, 0.5f))
        c.drawRect(0f, h * 0.72f, w.toFloat(), h.toFloat(), p)
        FileOutputStream(f).use {
            bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 85, it)
        }
        f.setLastModified(stamp(at))
    }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        val appOps = app.getSystemService(AppOpsManager::class.java)
        shadowOf(appOps).setMode(
            "android:manage_external_storage", app.applicationInfo.uid, app.packageName, AppOpsManager.MODE_ALLOWED,
        )
        shadowOf(app.getSystemService(StorageStatsManager::class.java))
            .setStorageDeviceFreeAndTotalBytes(StorageManager.UUID_DEFAULT, 71_400_000_000L, 128_000_000_000L)
        root = Environment.getExternalStorageDirectory()
        root.deleteRecursively()
        root.mkdirs()
        val camera = File(root, "DCIM/Camera")
        photo(File(camera, "IMG_20261025_183000.jpg"), 20, "2026-10-25 18:30")
        photo(File(camera, "IMG_20261025_101500.jpg"), 200, "2026-10-25 10:15")
        photo(File(camera, "IMG_20261024_090000.jpg"), 120, "2026-10-24 09:00", w = 360, h = 480)
        File(camera, "VID_20261024_120000.mp4").apply {
            writeBytes(ByteArray(4096) { it.toByte() })
            setLastModified(stamp("2026-10-24 12:00"))
        }
        photo(File(root, "Pictures/Screenshots/Screenshot_20261026_210000.png"), 260, "2026-10-26 21:00", png = true, w = 360, h = 780)
        jeju = File(root, "Pictures/제주 여행")
        photo(File(jeju, "바다.jpg"), 190, "2026-07-01 10:00")
        photo(File(jeju, "오름.jpg"), 100, "2026-07-01 11:00")
        photo(File(jeju, "노을.jpg"), 15, "2026-07-02 19:00")
        // Hidden from galleries, like on a real device.
        File(root, "Download/.nomedia").apply { parentFile?.mkdirs(); writeBytes(ByteArray(0)) }
        photo(File(root, "Download/sticker.jpg"), 300, "2026-10-27 08:00")
        File(root, "Documents").mkdirs()
    }

    private fun newViewModel(): MainViewModel =
        MainViewModel(app).also { vm -> vm.gallery.source = { MediaLibrary.scanFolders(it) } }

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

    private fun shot(name: String) = compose.onRoot().captureRoboImage("screenshots/$name.png")

    private fun screen(name: String) = captureScreenRoboImage("screenshots/$name.png")

    @Test
    fun galleryFlow() {
        val vm = newViewModel()
        start(vm)
        waitFor { vm.volumes.value.isNotEmpty() && vm.gallery.ui.value.loaded }
        settle(1500)
        shot("40_home_gallery")

        val index = vm.gallery.ui.value.index
        assertEquals(8, index.items.size)
        assertEquals(7, index.photoCount)
        assertEquals(1, index.videoCount)
        assertTrue(index.items.none { it.path.contains("/Download/") })
        assertEquals("Screenshot_20261026_210000.png", index.items.first().name)
        assertEquals(
            listOf(File(root, "DCIM/Camera").path, GalleryIndex.VIDEOS, File(root, "Pictures/Screenshots").path, jeju.path),
            index.albums.map { it.key },
        )
        assertEquals(AlbumType.CAMERA, index.albums.first().type)

        compose.runOnIdle { vm.openGallery() }
        settle(1500)
        shot("41_gallery_photos")

        compose.runOnIdle { vm.gallery.tab.value = GalleryTab.ALBUMS }
        settle(1500)
        shot("42_gallery_albums")

        compose.runOnIdle { vm.openAlbum(jeju.path) }
        settle(1200)
        shot("43_album")
        val trip = vm.gallery.ui.value.index.itemsOf(jeju.path)
        assertEquals(listOf("노을.jpg", "오름.jpg", "바다.jpg"), trip.map { it.name })

        compose.runOnIdle {
            vm.gallery.toggle(trip[0].path)
            vm.gallery.toggle(trip[2].path)
        }
        settle()
        shot("44_album_selection")
        compose.runOnIdle { vm.gallery.clearSelection() }

        compose.runOnIdle { vm.gallery.openViewer(jeju.path, trip[1].path) }
        settle(1500)
        screen("45_viewer")

        compose.runOnIdle { vm.showDialog(DialogState.MediaInfo(trip[1])) }
        settle(1200)
        screen("46_viewer_details")
        compose.runOnIdle { vm.showDialog(null) }
        settle()

        // Deleting the photo on screen leaves the viewer open on the next one.
        compose.runOnIdle { vm.delete(listOf(trip[1].path)) }
        waitFor {
            vm.operations.running.value == null && !File(trip[1].path).exists() &&
                vm.gallery.ui.value.index.itemsOf(jeju.path).size == 2
        }
        assertNotNull(vm.gallery.viewer.value)
        compose.runOnIdle { vm.gallery.closeViewer(null) }
        settle()
        assertNull(vm.gallery.viewer.value)

        compose.runOnIdle { vm.back() }
        settle()
        assertEquals(Route.Gallery, vm.route.value)
    }

    @Test
    fun photosFromTheFileListOpenInTheViewer() {
        val vm = newViewModel()
        start(vm)
        waitFor { vm.volumes.value.isNotEmpty() }
        compose.runOnIdle { vm.openDir(jeju.path) }
        waitFor { vm.browser.value.path == jeju.path && !vm.browser.value.loading }
        settle()
        val sea = vm.visibleItems.value.first { it.name == "바다.jpg" }
        compose.runOnIdle { vm.onItemClick(sea) }
        settle(1000)
        val request = vm.gallery.viewer.value
        assertNotNull(request)
        assertEquals(3, request!!.items!!.size)
        assertEquals(sea.path, request.startPath)

        // With the setting off, the photo goes to another app as before.
        compose.runOnIdle {
            vm.gallery.closeViewer(null)
            vm.updateSettings { it.copy(openMediaInApp = false) }
            vm.onItemClick(sea)
        }
        settle()
        assertNull(vm.gallery.viewer.value)
        compose.runOnIdle { vm.updateSettings { it.copy(openMediaInApp = true) } }
    }

    @Test
    @Config(qualifiers = "ko-rKR-w411dp-h891dp-night-xhdpi")
    fun darkGallery() {
        val vm = newViewModel()
        start(vm)
        waitFor { vm.gallery.ui.value.loaded }
        compose.runOnIdle { vm.openGallery() }
        settle(1500)
        shot("47_dark_gallery")
    }

    @Test
    @Config(qualifiers = "en-rUS-w411dp-h891dp-xhdpi")
    fun englishAlbums() {
        val vm = newViewModel()
        start(vm)
        waitFor { vm.gallery.ui.value.loaded }
        compose.runOnIdle {
            vm.openGallery()
            vm.gallery.tab.value = GalleryTab.ALBUMS
        }
        settle(1500)
        shot("48_en_albums")
    }
}

package com.offlinefiles.explorer.data

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Process
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.offlinefiles.explorer.App
import com.offlinefiles.explorer.core.Conflict
import com.offlinefiles.explorer.core.ConflictAction
import com.offlinefiles.explorer.core.ConflictDecision
import com.offlinefiles.explorer.core.FailureReason
import com.offlinefiles.explorer.core.FileOpException
import com.offlinefiles.explorer.core.ListResult
import com.offlinefiles.explorer.core.OperationListener
import com.offlinefiles.explorer.core.Progress
import com.offlinefiles.explorer.core.StoreOps
import com.offlinefiles.explorer.core.archive.ArchiveFormats
import com.offlinefiles.explorer.core.archive.ArchiveOps
import com.offlinefiles.explorer.core.archive.ArchiveType
import com.offlinefiles.explorer.core.archive.Archives
import com.offlinefiles.explorer.core.archive.CompressionLevel
import com.offlinefiles.explorer.ops.DocumentStaging
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.StorageVolumeBuilder
import java.io.File

/**
 * A USB drive that Android keeps away from apps' file paths (as on Pixel phones): found as a
 * volume that needs access, opened through the system file picker, then used through the
 * document provider. The provider is a stand-in backed by a folder on the host.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class UsbStorageTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var app: App
    private lateinit var stick: File
    private val uuid = "1A2B-3C4D"
    private val mount = "/mnt/media_rw/1A2B-3C4D"

    private object Quiet : OperationListener {
        override fun onProgress(progress: Progress) {}
        override suspend fun onConflict(conflict: Conflict) = ConflictDecision(ConflictAction.OVERWRITE, true)
    }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        stick = tmp.newFolder("stick")
        FakeDriveProvider.drives.clear()
        FakeDriveProvider.drives[uuid] = stick
        FakeDriveProvider.install()
        val volume = StorageVolumeBuilder("public:8,1", File(mount), "SanDisk USB drive", Process.myUserHandle(), Environment.MEDIA_MOUNTED)
            .setIsRemovable(true)
            .setFsUuid(uuid)
            .build()
        shadowOf(app.getSystemService(StorageManager::class.java)).addStorageVolume(volume)
    }

    @After
    fun tearDown() {
        Stores.update(app.contentResolver, emptyList())
    }

    private fun usb() = StorageVolumes.list(app).single { it.kind == VolumeKind.USB }

    private fun grant() {
        assertEquals(GrantResult.Granted(uuid), StorageVolumes.acceptGrant(app, FakeDriveProvider.treeUri(uuid), StorageVolumes.list(app)))
        Stores.update(app.contentResolver, StorageVolumes.list(app))
    }

    @Test
    fun hiddenDriveAsksForAccessThenUsesDocuments() {
        val usb = usb()
        assertEquals(VolumeAccess.NEEDS_GRANT, usb.access)
        assertEquals(mount, usb.path)
        assertEquals("SanDisk USB drive", usb.label)
        assertTrue(usb.canGrant)
        assertEquals(VolumeKind.INTERNAL, StorageVolumes.list(app).first().kind)

        val intent = StorageVolumes.accessIntent(app, usb)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, intent.action)
        @Suppress("DEPRECATION")
        val initial = intent.getParcelableExtra<Uri>(DocumentsContract.EXTRA_INITIAL_URI)
        assertTrue(initial.toString().contains(uuid))

        val volumes = StorageVolumes.list(app)
        assertEquals(GrantResult.NotRoot, StorageVolumes.acceptGrant(app, FakeDriveProvider.treeUri(uuid, "DCIM"), volumes))
        assertEquals(GrantResult.NotADrive, StorageVolumes.acceptGrant(app, FakeDriveProvider.treeUri("FFFF-0000"), volumes))
        assertTrue(StorageVolumes.grantedTrees(app).isEmpty())
        grant()

        val granted = usb()
        assertEquals(VolumeAccess.DOCUMENTS, granted.access)
        assertEquals(FakeDriveProvider.treeUri(uuid).toString(), granted.treeUri)
        assertFalse(granted.canGrant)
        assertTrue(Stores.isDocument("$mount/DCIM"))
        assertFalse(Stores.isDocument(Environment.getExternalStorageDirectory().path))

        StorageVolumes.releaseGrants(app)
        assertEquals(VolumeAccess.NEEDS_GRANT, usb().access)
    }

    @Test
    fun documentTreeFileOperations() {
        File(stick, "DCIM/Camera").mkdirs()
        File(stick, "DCIM/Camera/IMG_1.jpg").writeBytes(ByteArray(1000) { it.toByte() })
        File(stick, "readme.txt").writeText("hello")
        val tree = DocumentTree(app.contentResolver, mount, FakeDriveProvider.treeUri(uuid))

        val top = (tree.list(mount) as ListResult.Ok).items.associateBy { it.name }
        assertEquals(setOf("DCIM", "readme.txt"), top.keys)
        assertTrue(top.getValue("DCIM").isDirectory)
        assertEquals("$mount/readme.txt", top.getValue("readme.txt").path)
        assertEquals(5L, top.getValue("readme.txt").size)
        assertEquals(1000L, tree.stat("$mount/DCIM/Camera/IMG_1.jpg")!!.size)
        assertNull(tree.stat("$mount/nope.txt"))
        assertEquals(ListResult.NotFound, tree.list("$mount/nope"))
        assertEquals("hello", tree.openInput("$mount/readme.txt").use { it.readBytes().decodeToString() })

        val folder = tree.createFolder(mount, "Backup")
        assertEquals("$mount/Backup", folder)
        val created = tree.createFile(folder, "notes.txt")
        created.output.use { it.write("메모".toByteArray()) }
        assertEquals("$mount/Backup/notes.txt", created.path)
        assertEquals("메모", File(stick, "Backup/notes.txt").readText())

        // Taken names are refused (the provider would quietly add " (1)"); case-only renames work.
        try {
            tree.rename("$mount/readme.txt", "Backup")
            fail()
        } catch (e: FileOpException) {
            assertEquals(FailureReason.ALREADY_EXISTS, e.reason)
        }
        assertEquals("$mount/README.txt", tree.rename("$mount/readme.txt", "README.txt"))
        assertTrue(File(stick, "README.txt").isFile)

        // A finished copy replaces the old file; the old one is set aside until then.
        val temp = tree.createFile(folder, ".notes.txt.part")
        temp.output.use { it.write("new".toByteArray()) }
        assertEquals("$mount/Backup/notes.txt", tree.replace(temp.path, "$mount/Backup/notes.txt"))
        assertEquals("new", File(stick, "Backup/notes.txt").readText())
        assertEquals(listOf("notes.txt"), File(stick, "Backup").list()!!.toList())

        assertEquals("$mount/Backup/Camera", tree.moveInto("$mount/DCIM/Camera", "$mount/Backup"))
        assertTrue(File(stick, "Backup/Camera/IMG_1.jpg").isFile)
        assertNull(tree.moveInto("$mount/Backup/Camera", "$mount/Backup"))
        assertTrue(tree.delete("$mount/DCIM"))
        assertFalse(File(stick, "DCIM").exists())
    }

    @Test
    fun copiesBetweenPhoneAndDrive() = runBlocking {
        grant()
        val phone = tmp.newFolder("phone")
        File(phone, "사진/바다.jpg").apply { parentFile?.mkdirs() }.writeBytes(ByteArray(50_000) { (it % 7).toByte() })
        File(phone, "문서.pdf").writeText("pdf")

        val r = StoreOps.copyOrMove(listOf(File(phone, "사진").path, File(phone, "문서.pdf").path), mount, false, Stores, Quiet)
        assertEquals(2, r.succeeded)
        assertTrue(r.failures.isEmpty())
        assertEquals(listOf("$mount/사진", "$mount/문서.pdf"), r.outputs.map { it.path })
        assertEquals(50_000L, File(stick, "사진/바다.jpg").length())
        assertEquals("pdf", File(stick, "문서.pdf").readText())

        // Copying again replaces the files on the drive (the listener answers "replace").
        File(phone, "문서.pdf").writeText("pdf v2")
        assertEquals(1, StoreOps.copyOrMove(listOf(File(phone, "문서.pdf").path), mount, false, Stores, Quiet).succeeded)
        assertEquals("pdf v2", File(stick, "문서.pdf").readText())
        assertTrue(stick.list()!!.none { it.endsWith(".part") || it.endsWith(".old") })

        val back = tmp.newFolder("back")
        val r2 = StoreOps.copyOrMove(listOf("$mount/사진"), back.path, true, Stores, Quiet)
        assertEquals(1, r2.succeeded)
        assertEquals(50_000L, File(back, "사진/바다.jpg").length())
        assertFalse(File(stick, "사진").exists())

        val r3 = StoreOps.delete(listOf("$mount/문서.pdf"), Stores, Quiet)
        assertEquals(1, r3.succeeded)
        assertEquals(0, stick.list()!!.size)
    }

    @Test
    fun archivesOnTheDriveGoThroughTheCache() = runBlocking {
        grant()
        assertEquals(listOf("a.7z.001", "a.7z.002", "a.7z.003"), ArchiveFormats.partNames("a.7z.003", listOf("a.7z.001", "a.7z.002", "a.7z.003", "b.7z.001")).sorted())
        assertEquals(listOf("a.z01", "a.zip"), ArchiveFormats.partNames("a.zip", listOf("a.z01", "a.zip", "a.txt")).sorted())
        assertEquals(listOf("photo.zip"), ArchiveFormats.partNames("photo.zip", listOf("photo.zip", "photo.jpg")))

        val phone = tmp.newFolder("phone")
        File(phone, "여행/a.txt").apply { parentFile?.mkdirs() }.writeText("A")
        File(phone, "여행/b.txt").writeText("B")
        ArchiveOps.compress(listOf(File(phone, "여행")), File(stick, "여행.zip"), ArchiveType.ZIP, CompressionLevel.NORMAL, null, Quiet)

        val cache = File(app.cacheDir, "usb-test")
        val staging = DocumentStaging(cache, Stores)
        val staged = staging.acquireArchive("$mount/여행.zip", null)
        assertTrue(staged.file.isFile)
        // "Extract to folder": the archive already holds one "여행" folder, so no extra level.
        val r = staging.extractInto(staged.file, "여행.zip", null, mount, Quiet, null, "", smartFolder = true)
        staging.release(staged)
        assertFalse(staged.file.exists())
        assertEquals(2, r.succeeded)
        assertEquals(listOf("$mount/여행"), r.outputs.map { it.path })
        assertEquals("A", File(stick, "여행/a.txt").readText())

        val r2 = staging.compress(listOf("$mount/여행"), "$mount/백업.7z", ArchiveType.SEVEN_Z, CompressionLevel.NORMAL, null, Quiet)
        assertEquals(listOf("$mount/백업.7z"), r2.outputs.map { it.path })
        val entries = Archives.open(File(stick, "백업.7z"), null).use { reader ->
            reader.entries.filter { !it.isDirectory }.map { it.path }.sorted()
        }
        assertEquals(listOf("여행/a.txt", "여행/b.txt"), entries)
        assertTrue(cache.listFiles().orEmpty().none { it.name.startsWith("work-") })
    }
}

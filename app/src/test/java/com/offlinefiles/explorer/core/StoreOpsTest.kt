package com.offlinefiles.explorer.core

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * [StoreOps] between the local file system and a drive that behaves like a USB stick opened
 * through the system file picker: names ignore case and modification times can't be set.
 */
class StoreOpsTest {
    @get:Rule val tmp = TemporaryFolder()

    /** A FAT-like drive in memory, mounted at [root]. */
    private class MemoryDrive(val root: String) : FileStore {
        private class Node(var name: String, val dir: Boolean) {
            var data = ByteArray(0)
            var modified = 0L
            val children = LinkedHashMap<String, Node>()
        }

        private val top = Node("", true)
        private var clock = 1_000L
        var moves = 0

        private fun key(name: String) = name.lowercase()

        private fun node(path: String): Node? {
            if (path == root) return top
            if (!path.startsWith("$root/")) return null
            var cur = top
            for (part in path.removePrefix("$root/").split('/')) cur = cur.children[key(part)] ?: return null
            return cur
        }

        private fun item(path: String, n: Node) =
            FileItem(path, path.substringAfterLast('/'), n.dir, if (n.dir) 0 else n.data.size.toLong(), n.modified, n.name.startsWith("."))

        override fun list(dir: String): ListResult {
            val n = node(dir) ?: return ListResult.NotFound
            return ListResult.Ok(n.children.values.map { item("$dir/${it.name}", it) })
        }

        override fun stat(path: String): FileItem? = node(path)?.let { item(path, it) }

        override fun openInput(path: String): InputStream =
            ByteArrayInputStream(node(path)?.takeIf { !it.dir }?.data ?: throw FileNotFoundException(path))

        override fun createFile(dir: String, name: String): NewFile {
            val parent = node(dir) ?: throw FileNotFoundException(dir)
            if (key(name) in parent.children) throw FileOpException(FailureReason.ALREADY_EXISTS)
            val n = Node(name, false).also { it.modified = clock++ }
            parent.children[key(name)] = n
            return NewFile("$dir/$name", object : ByteArrayOutputStream() {
                override fun close() {
                    n.data = toByteArray()
                }
            })
        }

        override fun createFolder(dir: String, name: String): String {
            val parent = node(dir) ?: throw FileNotFoundException(dir)
            if (key(name) in parent.children) throw FileOpException(FailureReason.ALREADY_EXISTS)
            parent.children[key(name)] = Node(name, true).also { it.modified = clock++ }
            return "$dir/$name"
        }

        override fun rename(path: String, newName: String): String {
            val parentPath = path.substringBeforeLast('/')
            val parent = node(parentPath)!!
            val n = node(path) ?: throw FileNotFoundException(path)
            val other = parent.children[key(newName)]
            if (other != null && other !== n) throw FileOpException(FailureReason.ALREADY_EXISTS)
            parent.children.remove(key(n.name))
            n.name = newName
            parent.children[key(newName)] = n
            return "$parentPath/$newName"
        }

        override fun delete(path: String): Boolean {
            val n = node(path) ?: return false
            if (n.dir && n.children.isNotEmpty()) return false
            node(path.substringBeforeLast('/'))!!.children.remove(key(n.name))
            return true
        }

        override fun replace(temp: String, target: String): String {
            delete(target)
            return rename(temp, target.substringAfterLast('/'))
        }

        override fun moveInto(path: String, destDir: String): String? {
            val n = node(path) ?: return null
            val dest = node(destDir) ?: return null
            if (key(n.name) in dest.children) return null
            node(path.substringBeforeLast('/'))!!.children.remove(key(n.name))
            dest.children[key(n.name)] = n
            moves++
            return "$destDir/${n.name}"
        }

        override fun setLastModified(path: String, millis: Long) = Unit

        override fun isSame(a: String, b: String) = a.equals(b, ignoreCase = true)

        override fun isSameOrAncestor(ancestor: String, path: String) =
            path.equals(ancestor, true) || path.startsWith("$ancestor/", true)

        fun write(path: String, text: String) {
            val dir = path.substringBeforeLast('/')
            mkdirs(dir)
            if (stat(path) != null) delete(path)
            createFile(dir, path.substringAfterLast('/')).output.use { it.write(text.toByteArray()) }
        }

        fun mkdirs(path: String) {
            if (path == root || stat(path) != null) return
            mkdirs(path.substringBeforeLast('/'))
            createFolder(path.substringBeforeLast('/'), path.substringAfterLast('/'))
        }

        fun read(path: String): String = openInput(path).readBytes().decodeToString()

        fun names(dir: String): List<String> = (list(dir) as ListResult.Ok).items.map { it.name }.sorted()
    }

    private val drive = MemoryDrive("/mnt/media_rw/1A2B-3C4D")
    private val stores = StoreResolver { path -> if (path.startsWith(drive.root)) drive else LocalStore }

    private class Answers(vararg actions: ConflictDecision) : OperationListener {
        val queue = ArrayDeque(actions.toList())
        val asked = mutableListOf<Conflict>()
        var last: Progress? = null
        override fun onProgress(progress: Progress) { last = progress }
        override suspend fun onConflict(conflict: Conflict): ConflictDecision {
            asked += conflict
            return queue.removeFirstOrNull() ?: ConflictDecision(ConflictAction.SKIP, false)
        }
    }

    private fun File.write(text: String): File = also { parentFile?.mkdirs(); writeText(text) }

    @Test fun copyLocalToDriveAndBack() = runBlocking {
        val src = tmp.newFolder("src")
        File(src, "사진/2026/바다.jpg").apply { parentFile?.mkdirs() }.writeBytes(ByteArray(300_000) { (it % 251).toByte() })
        File(src, "사진/메모.txt").write("hello")
        File(src, "사진/빈 폴더").mkdirs()
        File(src, "사진/메모.txt").setLastModified(1_700_000_000_000L)
        val l = Answers()
        val r = StoreOps.copyOrMove(listOf(File(src, "사진").path), drive.root, move = false, stores, l)
        assertEquals(1, r.succeeded)
        assertTrue(r.failures.isEmpty())
        assertEquals(listOf("${drive.root}/사진"), r.outputs.map { it.path })
        assertEquals(listOf("2026", "메모.txt", "빈 폴더"), drive.names("${drive.root}/사진"))
        assertEquals(300_000L, drive.stat("${drive.root}/사진/2026/바다.jpg")!!.size)
        assertEquals("hello", drive.read("${drive.root}/사진/메모.txt"))
        assertEquals(1f, l.last!!.fraction!!, 0.001f)
        assertTrue(File(src, "사진/메모.txt").exists())

        // …and back to the phone, keeping the drive's time stamps.
        val back = tmp.newFolder("back")
        val r2 = StoreOps.copyOrMove(listOf("${drive.root}/사진"), back.path, move = false, stores, Answers())
        assertEquals(1, r2.succeeded)
        assertEquals("hello", File(back, "사진/메모.txt").readText())
        assertEquals(300_000L, File(back, "사진/2026/바다.jpg").length())
        assertTrue(File(back, "사진/빈 폴더").isDirectory)
        assertEquals(drive.stat("${drive.root}/사진/메모.txt")!!.lastModified, File(back, "사진/메모.txt").lastModified())
    }

    @Test fun movesAcrossStoresRemoveTheSource() = runBlocking {
        val src = tmp.newFolder("src")
        File(src, "a/b.txt").write("b")
        File(src, "c.txt").write("c")
        val r = StoreOps.copyOrMove(listOf(File(src, "a").path, File(src, "c.txt").path), drive.root, move = true, stores, Answers())
        assertEquals(2, r.succeeded)
        assertFalse(File(src, "a").exists())
        assertFalse(File(src, "c.txt").exists())
        assertEquals("b", drive.read("${drive.root}/a/b.txt"))

        val dest = tmp.newFolder("dest")
        val r2 = StoreOps.copyOrMove(listOf("${drive.root}/a", "${drive.root}/c.txt"), dest.path, move = true, stores, Answers())
        assertEquals(2, r2.succeeded)
        assertEquals(emptyList<String>(), drive.names(drive.root))
        assertEquals("c", File(dest, "c.txt").readText())
        assertEquals("b", File(dest, "a/b.txt").readText())
    }

    @Test fun conflictsOnTheDriveIgnoreCase() = runBlocking {
        val src = tmp.newFolder("src")
        File(src, "Photo.JPG").write("new photo")
        File(src, "notes.txt").write("new notes")
        File(src, "docs/a.txt").write("new a")
        File(src, "docs/b.txt").write("new b")
        drive.write("${drive.root}/photo.jpg", "old photo")
        drive.write("${drive.root}/notes.txt", "old notes")
        drive.write("${drive.root}/docs/a.txt", "old a")

        // Skip, then keep both: "photo.jpg" clashes with "Photo.JPG" on a FAT drive.
        val l = Answers(ConflictDecision(ConflictAction.SKIP, false), ConflictDecision(ConflictAction.KEEP_BOTH, false))
        val r = StoreOps.copyOrMove(listOf(File(src, "Photo.JPG").path, File(src, "notes.txt").path), drive.root, false, stores, l)
        assertEquals(2, l.asked.size)
        assertEquals(1, r.skipped)
        assertEquals("old photo", drive.read("${drive.root}/photo.jpg"))
        assertEquals("new notes", drive.read("${drive.root}/notes (1).txt"))
        assertEquals("old notes", drive.read("${drive.root}/notes.txt"))

        // Replace: the old file goes only once the new copy is complete, and no temp file stays behind.
        val r2 = StoreOps.copyOrMove(listOf(File(src, "notes.txt").path), drive.root, false, stores, Answers(ConflictDecision(ConflictAction.OVERWRITE, false)))
        assertEquals(1, r2.succeeded)
        assertEquals("new notes", drive.read("${drive.root}/notes.txt"))
        assertTrue(drive.names(drive.root).none { it.endsWith(".part") })

        // Folders merge; a clash inside is asked about.
        val l3 = Answers(ConflictDecision(ConflictAction.OVERWRITE, false), ConflictDecision(ConflictAction.OVERWRITE, false))
        val r3 = StoreOps.copyOrMove(listOf(File(src, "docs").path), drive.root, false, stores, l3)
        assertEquals(listOf(true, false), l3.asked.map { it.targetIsDirectory })
        assertEquals(1, r3.succeeded)
        assertEquals("new a", drive.read("${drive.root}/docs/a.txt"))
        assertEquals("new b", drive.read("${drive.root}/docs/b.txt"))
    }

    @Test fun movesWithinTheDriveAreInstant() = runBlocking {
        drive.write("${drive.root}/DCIM/a.jpg", "a")
        drive.write("${drive.root}/DCIM/sub/b.jpg", "b")
        drive.mkdirs("${drive.root}/Backup")
        val r = StoreOps.copyOrMove(listOf("${drive.root}/DCIM"), "${drive.root}/Backup", move = true, stores, Answers())
        assertEquals(1, r.succeeded)
        assertEquals(1, drive.moves)
        assertEquals("b", drive.read("${drive.root}/Backup/DCIM/sub/b.jpg"))
        assertNull(drive.stat("${drive.root}/DCIM"))

        // Into itself, onto itself, and a copy in the same folder.
        val r2 = StoreOps.copyOrMove(listOf("${drive.root}/Backup"), "${drive.root}/Backup/DCIM", false, stores, Answers())
        assertEquals(FailureReason.INTO_ITSELF, r2.failures.single().reason)
        val r3 = StoreOps.copyOrMove(listOf("${drive.root}/Backup/DCIM/a.jpg"), "${drive.root}/Backup/DCIM", true, stores, Answers())
        assertEquals(1, r3.skipped)
        val r4 = StoreOps.copyOrMove(listOf("${drive.root}/Backup/DCIM/a.jpg"), "${drive.root}/Backup/DCIM", false, stores, Answers())
        assertEquals(1, r4.succeeded)
        assertEquals(listOf("a (1).jpg", "a.jpg", "sub"), drive.names("${drive.root}/Backup/DCIM"))
    }

    @Test fun deleteMeasureAndSearch() = runBlocking {
        drive.write("${drive.root}/Music/곡 1.mp3", "12345")
        drive.write("${drive.root}/Music/앨범/곡 2.mp3", "123")
        drive.write("${drive.root}/Music/.hidden/곡 3.mp3", "1")
        drive.write("${drive.root}/readme.txt", "r")
        val music = drive.stat("${drive.root}/Music")!!
        assertEquals(DirStats(files = 3, folders = 2, bytes = 9), StoreOps.measureTree(drive, music))

        val found = StoreOps.search(drive, drive.root, "곡", includeHidden = false).toList().map { it.name }
        assertEquals(listOf("곡 1.mp3", "곡 2.mp3"), found)
        assertEquals(3, StoreOps.search(drive, drive.root, "곡", includeHidden = true).toList().size)

        val l = Answers()
        val r = StoreOps.delete(listOf("${drive.root}/Music", "${drive.root}/gone.txt"), stores, l)
        assertEquals(1, r.succeeded)
        assertEquals(FailureReason.NOT_FOUND, r.failures.single().reason)
        assertEquals(listOf("readme.txt"), drive.names(drive.root))
        assertEquals(6, l.last!!.totalItems)
    }

    @Test fun namesAndSimpleActions() {
        drive.write("${drive.root}/a.txt", "1")
        drive.write("${drive.root}/a (1).txt", "1")
        assertEquals("A (2).TXT", StoreOps.uniqueName(drive, drive.root, "A.TXT", false))
        assertEquals("b.txt", StoreOps.uniqueName(drive, drive.root, "b.txt", false))

        val folder = StoreOps.createFolder(drive, drive.root, "새 폴더")
        assertTrue(drive.stat(folder)!!.isDirectory)
        assertNotNull(drive.stat(StoreOps.createFile(drive, folder, "메모.txt")))
        try {
            StoreOps.createFolder(drive, drive.root, "새 폴더")
            fail()
        } catch (e: FileOpException) {
            assertEquals(FailureReason.ALREADY_EXISTS, e.reason)
        }
        try {
            StoreOps.rename(drive, "${drive.root}/a.txt", "x:y")
            fail()
        } catch (e: FileOpException) {
            assertEquals(FailureReason.IO_ERROR, e.reason)
        }
        assertEquals("${drive.root}/A.txt", StoreOps.rename(drive, "${drive.root}/a.txt", "A.txt"))
        assertEquals(listOf("A.txt", "a (1).txt", "새 폴더"), drive.names(drive.root))
    }
}

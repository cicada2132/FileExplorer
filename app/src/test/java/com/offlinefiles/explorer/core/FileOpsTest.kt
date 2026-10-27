package com.offlinefiles.explorer.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileOpsTest {
    @get:Rule val tmp = TemporaryFolder()

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

    @Test fun naturalOrder() {
        val sorted = listOf("file10.txt", "file2.txt", "File1.txt", "file02.txt", "a", "B", "가나", "10", "9").sortedWith(NaturalOrder)
        assertEquals(listOf("9", "10", "a", "B", "File1.txt", "file2.txt", "file02.txt", "file10.txt", "가나"), sorted)
    }

    @Test fun sortingKeepsFoldersFirst() {
        val items = listOf(
            FileItem("/x/b.txt", "b.txt", false, 10, 3, false),
            FileItem("/x/A", "A", true, 0, 1, false),
            FileItem("/x/a.txt", "a.txt", false, 300, 2, false),
            FileItem("/x/c", "c", true, 0, 5, false),
        )
        assertEquals(listOf("A", "c", "a.txt", "b.txt"), Sorting.sort(items, SortSpec(SortField.NAME, true)).map { it.name })
        assertEquals(listOf("A", "c", "a.txt", "b.txt"), Sorting.sort(items, SortSpec(SortField.SIZE, false)).map { it.name })
        assertEquals(listOf("c", "A", "b.txt", "a.txt"), Sorting.sort(items, SortSpec(SortField.DATE, false)).map { it.name })
    }

    @Test fun uniqueNames() {
        val d = tmp.newFolder("u")
        File(d, "a.txt").write("1")
        assertEquals("a (1).txt", FileOps.uniqueChild(d, "a.txt", false).name)
        File(d, "a (1).txt").write("1")
        assertEquals("a (2).txt", FileOps.uniqueChild(d, "a.txt", false).name)
        assertEquals("a (2).txt", FileOps.uniqueChild(d, "a (1).txt", false).name)
        File(d, "folder.v2").mkdir()
        assertEquals("folder.v2 (1)", FileOps.uniqueChild(d, "folder.v2", true).name)
        assertEquals("report (2023) (1).pdf", run { File(d, "report (2023).pdf").write("x"); FileOps.uniqueChild(d, "report (2023).pdf", false).name })
    }

    @Test fun validateNames() {
        assertEquals(NameError.EMPTY, FileOps.validateName("  "))
        assertEquals(NameError.RESERVED, FileOps.validateName(".."))
        assertEquals(NameError.INVALID_CHARS, FileOps.validateName("a/b"))
        assertEquals(NameError.INVALID_CHARS, FileOps.validateName("a:b"))
        assertEquals(NameError.TOO_LONG, FileOps.validateName("가".repeat(90)))
        assertNull(FileOps.validateName("사진 2026.jpg"))
    }

    @Test fun copyTreeWithProgress() = runBlocking {
        val src = tmp.newFolder("src")
        File(src, "a/b/c.txt").write("hello")
        File(src, "a/d.bin").writeBytes(ByteArray(700_000) { it.toByte() })
        File(src, "a/empty").mkdirs()
        val dest = tmp.newFolder("dest")
        val l = Answers()
        val r = FileOps.copyOrMove(listOf(File(src, "a")), dest, move = false, listener = l)
        assertEquals(1, r.succeeded)
        assertTrue(r.failures.isEmpty())
        assertEquals("hello", File(dest, "a/b/c.txt").readText())
        assertEquals(700_000L, File(dest, "a/d.bin").length())
        assertTrue(File(dest, "a/empty").isDirectory)
        assertTrue(File(src, "a/b/c.txt").exists())
        assertEquals(l.last!!.totalBytes, l.last!!.doneBytes)
    }

    @Test fun copyIntoSameFolderMakesNumberedCopy() = runBlocking {
        val d = tmp.newFolder("same")
        val f = File(d, "doc.txt").write("x")
        val r = FileOps.copyOrMove(listOf(f), d, move = false, listener = Answers())
        assertEquals(1, r.succeeded)
        assertTrue(File(d, "doc (1).txt").exists())
        assertEquals("x", f.readText())
    }

    @Test fun copyFolderIntoItselfIsRefused() = runBlocking {
        val d = tmp.newFolder("self")
        File(d, "inner/x.txt").write("x")
        val r = FileOps.copyOrMove(listOf(d), File(d, "inner"), move = false, listener = Answers())
        assertEquals(0, r.succeeded)
        assertEquals(FailureReason.INTO_ITSELF, r.failures.single().reason)
    }

    @Test fun conflictsOverwriteSkipKeepBoth() = runBlocking {
        val src = tmp.newFolder("s")
        val dest = tmp.newFolder("t")
        val a = File(src, "a.txt").write("new-a")
        val b = File(src, "b.txt").write("new-b")
        val c = File(src, "c.txt").write("new-c")
        File(dest, "a.txt").write("old-a")
        File(dest, "b.txt").write("old-b")
        File(dest, "c.txt").write("old-c")
        val l = Answers(
            ConflictDecision(ConflictAction.OVERWRITE, false),
            ConflictDecision(ConflictAction.SKIP, false),
            ConflictDecision(ConflictAction.KEEP_BOTH, false),
        )
        val r = FileOps.copyOrMove(listOf(a, b, c), dest, move = false, listener = l)
        assertEquals(3, l.asked.size)
        assertEquals(2, r.succeeded)
        assertEquals(1, r.skipped)
        assertEquals("new-a", File(dest, "a.txt").readText())
        assertEquals("old-b", File(dest, "b.txt").readText())
        assertEquals("old-c", File(dest, "c.txt").readText())
        assertEquals("new-c", File(dest, "c (1).txt").readText())
        // no temp files left behind
        assertTrue(dest.list()!!.none { it.endsWith(".part") })
    }

    @Test fun mergeFoldersAskingOnlyForFiles() = runBlocking {
        val src = tmp.newFolder("ms")
        val dest = tmp.newFolder("md")
        File(src, "pics/1.jpg").write("new1")
        File(src, "pics/2.jpg").write("new2")
        File(dest, "pics/1.jpg").write("old1")
        File(dest, "pics/3.jpg").write("old3")
        val l = Answers(ConflictDecision(ConflictAction.OVERWRITE, true))
        val r = FileOps.copyOrMove(listOf(File(src, "pics")), dest, move = true, listener = l)
        // one question for the folder (merge), applied to all: the file conflict isn't asked again
        assertEquals(1, l.asked.size)
        assertEquals(1, r.succeeded)
        assertEquals("new1", File(dest, "pics/1.jpg").readText())
        assertEquals("new2", File(dest, "pics/2.jpg").readText())
        assertEquals("old3", File(dest, "pics/3.jpg").readText())
        assertFalse(File(src, "pics").exists())
    }

    @Test fun moveByRename() = runBlocking {
        val src = tmp.newFolder("mv")
        val dest = tmp.newFolder("mvd")
        val f = File(src, "x/y.txt").write("y")
        val r = FileOps.copyOrMove(listOf(File(src, "x")), dest, move = true, listener = Answers())
        assertEquals(1, r.succeeded)
        assertTrue(File(dest, "x/y.txt").exists())
        assertFalse(f.exists())
    }

    @Test fun deleteTree() = runBlocking {
        val d = tmp.newFolder("del")
        File(d, "a/b/c/d.txt").write("1")
        File(d, "e.txt").write("1")
        val l = Answers()
        val r = FileOps.delete(listOf(File(d, "a"), File(d, "e.txt")), l)
        assertEquals(2, r.succeeded)
        assertTrue(d.list()!!.isEmpty())
        assertEquals(l.last!!.totalItems, l.last!!.doneItems)
    }

    @Test fun renameAndCreate() {
        val d = tmp.newFolder("rn")
        val f = File(d, "old.txt").write("x")
        val g = FileOps.rename(f, "new.txt")
        assertEquals("new.txt", g.name)
        assertFalse(f.exists())
        val folder = FileOps.createFolder(d, "새 폴더")
        assertTrue(folder.isDirectory)
        try {
            FileOps.createFolder(d, "새 폴더"); throw AssertionError("expected failure")
        } catch (e: FileOpException) {
            assertEquals(FailureReason.ALREADY_EXISTS, e.reason)
        }
        try {
            FileOps.rename(g, "새 폴더"); throw AssertionError("expected failure")
        } catch (e: FileOpException) {
            assertEquals(FailureReason.ALREADY_EXISTS, e.reason)
        }
    }

    @Test fun measure() = runBlocking {
        val d = tmp.newFolder("m")
        File(d, "a/1.txt").write("12345")
        File(d, "b/2.txt").write("123")
        File(d, "b/c").mkdirs()
        val s = FileOps.measureTree(d)
        assertEquals(2, s.files)
        assertEquals(3, s.folders)
        assertEquals(8L, s.bytes)
    }
}

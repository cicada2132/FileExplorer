package com.offlinefiles.explorer.core.archive

import com.offlinefiles.explorer.core.Conflict
import com.offlinefiles.explorer.core.ConflictAction
import com.offlinefiles.explorer.core.ConflictDecision
import com.offlinefiles.explorer.core.FailureReason
import com.offlinefiles.explorer.core.OperationListener
import com.offlinefiles.explorer.core.Progress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class ArchiveTest {
    @get:Rule val tmp = TemporaryFolder()

    private object Quiet : OperationListener {
        override fun onProgress(progress: Progress) {}
        override suspend fun onConflict(conflict: Conflict) = ConflictDecision(ConflictAction.OVERWRITE, true)
    }

    private fun File.write(text: String): File = also { parentFile?.mkdirs(); writeText(text) }

    /** A small tree with Korean names, an empty folder and a binary file. */
    private fun sampleTree(): File {
        val root = tmp.newFolder("src")
        File(root, "사진/여름 휴가/바다.txt").write("파도 소리")
        File(root, "사진/메모.txt").write("memo")
        File(root, "사진/빈 폴더").mkdirs()
        File(root, "사진/data.bin").writeBytes(ByteArray(300_000) { (it * 31).toByte() })
        return File(root, "사진")
    }

    private fun assertSameTree(expected: File, actual: File) {
        val e = expected.walkTopDown().map { it.relativeTo(expected).path }.sorted().toList()
        val a = actual.walkTopDown().map { it.relativeTo(actual).path }.sorted().toList()
        assertEquals(e, a)
        expected.walkTopDown().filter { it.isFile }.forEach {
            assertTrue("content of $it", it.readBytes().contentEquals(File(actual, it.relativeTo(expected).path).readBytes()))
        }
    }

    private fun expectError(error: ArchiveError, block: () -> Unit) {
        try {
            block()
            fail("expected $error")
        } catch (e: ArchiveException) {
            assertEquals(error, e.error)
        }
    }

    @Test fun zipRoundTrip() = runBlocking {
        for (level in CompressionLevel.entries) {
            val src = sampleTree()
            val zip = File(tmp.root, "out-$level.zip")
            ArchiveOps.compress(listOf(src), zip, ArchiveType.ZIP, level, null, Quiet)
            val dest = tmp.newFolder("x-$level")
            val r = ArchiveOps.extract(zip, null, dest, Quiet)
            assertTrue(r.failures.isEmpty())
            assertSameTree(src, File(dest, "사진"))
            src.parentFile.deleteRecursively()
        }
    }

    @Test fun sevenZipRoundTrip() = runBlocking {
        for (level in CompressionLevel.entries) {
            val src = sampleTree()
            val archive = File(tmp.root, "out-$level.7z")
            ArchiveOps.compress(listOf(src), archive, ArchiveType.SEVEN_Z, level, null, Quiet)
            val dest = tmp.newFolder("y-$level")
            ArchiveOps.extract(archive, null, dest, Quiet)
            assertSameTree(src, File(dest, "사진"))
            src.parentFile.deleteRecursively()
        }
    }

    @Test fun passwords() = runBlocking {
        for (type in ArchiveType.entries) {
            val src = sampleTree()
            val archive = File(tmp.root, "secret.${type.extension}")
            ArchiveOps.compress(listOf(src), archive, type, CompressionLevel.NORMAL, "비번123".toCharArray(), Quiet)
            val dest = tmp.newFolder("p-$type")
            expectError(ArchiveError.PASSWORD_REQUIRED) { runBlocking { ArchiveOps.extract(archive, null, dest, Quiet) } }
            expectError(ArchiveError.WRONG_PASSWORD) { runBlocking { ArchiveOps.extract(archive, "nope".toCharArray(), dest, Quiet) } }
            ArchiveOps.extract(archive, "비번123".toCharArray(), dest, Quiet)
            assertSameTree(src, File(dest, "사진"))
            src.parentFile.deleteRecursively()
        }
    }

    @Test fun extractSelectionFromInsideFolder() = runBlocking {
        val src = sampleTree()
        val zip = File(tmp.root, "sel.zip")
        ArchiveOps.compress(listOf(src), zip, ArchiveType.ZIP, CompressionLevel.FAST, null, Quiet)
        val dest = tmp.newFolder("sel")
        val r = ArchiveOps.extract(zip, null, dest, Quiet, selection = setOf("사진/여름 휴가", "사진/메모.txt"), stripPrefix = "사진/")
        assertEquals(2, r.succeeded)
        assertEquals("파도 소리", File(dest, "여름 휴가/바다.txt").readText())
        assertEquals("memo", File(dest, "메모.txt").readText())
        assertFalse(File(dest, "data.bin").exists())
        assertEquals(setOf("여름 휴가", "메모.txt"), r.outputs.map { it.name }.toSet())
    }

    @Test fun splitVolumesResolve() {
        val d = tmp.newFolder("split")
        listOf("movie.7z.001", "movie.7z.002", "movie.7z.003").forEach { File(d, it).writeText("x") }
        val v = ArchiveFormats.resolve(File(d, "movie.7z.002"))
        assertEquals(listOf("movie.7z.001", "movie.7z.002", "movie.7z.003"), v.parts.map { it.name })
        assertEquals("movie", ArchiveFormats.baseName("movie.7z.002"))
        assertEquals("photos", ArchiveFormats.baseName("photos.ZIP"))
    }

    // ---------------------------------------------------------------- python-built fixtures

    private fun python(script: String, vararg args: String) {
        val p = ProcessBuilder(listOf("python3", "-c", script) + args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(60, TimeUnit.SECONDS)
        assertEquals(out, 0, p.exitValue())
    }

    @Test fun zipSlipIsBlocked() = runBlocking {
        val zip = File(tmp.root, "evil.zip")
        python(
            """
import zipfile, sys
with zipfile.ZipFile(sys.argv[1], 'w') as z:
    z.writestr('../evil.txt', 'x')
    z.writestr('/abs.txt', 'x')
    z.writestr('ok/../../evil2.txt', 'x')
    z.writestr('good/file.txt', 'fine')
""".trimIndent(),
            zip.path,
        )
        val dest = tmp.newFolder("slip", "inner")
        val r = ArchiveOps.extract(zip, null, dest, Quiet)
        assertEquals("fine", File(dest, "good/file.txt").readText())
        assertTrue(File(dest, "abs.txt").exists()) // absolute paths are re-rooted inside dest
        assertFalse(File(dest.parentFile, "evil.txt").exists())
        assertFalse(File(tmp.root, "evil2.txt").exists())
        assertTrue(r.failures.any { it.reason == FailureReason.UNSAFE_PATH })
    }

    @Test fun cp949AndUnicodeExtraNames() = runBlocking {
        val zip = File(tmp.root, "korean.zip")
        python(
            """
import zipfile, sys, struct, zlib
class RawInfo(zipfile.ZipInfo):
    __slots__ = ('raw',)
    def _encodeFilenameFlags(self):
        return self.raw, self.flag_bits & ~0x800
def raw_info(raw, extra=b''):
    zi = RawInfo('placeholder' + str(len(raw)))
    zi.raw = raw
    zi.compress_type = zipfile.ZIP_DEFLATED
    zi.extra = extra
    return zi
with zipfile.ZipFile(sys.argv[1], 'w') as z:
    # Korean Windows: CP949 bytes, no UTF-8 flag
    z.writestr(raw_info('보고서/1분기 결과.txt'.encode('cp949')), 'q1')
    # Garbled main name, correct Info-ZIP Unicode Path extra field
    raw = b'????.txt'
    utf = '사진목록.txt'.encode('utf-8')
    data = bytes([1]) + struct.pack('<I', zlib.crc32(raw) & 0xffffffff) + utf
    z.writestr(raw_info(raw, struct.pack('<HH', 0x7075, len(data)) + data), 'list')
""".trimIndent(),
            zip.path,
        )
        Archives.open(zip, null).use { r ->
            assertEquals(setOf("보고서/1분기 결과.txt", "사진목록.txt"), r.entries.map { it.path }.toSet())
        }
        val dest = tmp.newFolder("kr")
        ArchiveOps.extract(zip, null, dest, Quiet)
        assertEquals("q1", File(dest, "보고서/1분기 결과.txt").readText())
        assertEquals("list", File(dest, "사진목록.txt").readText())
    }

    // ---------------------------------------------------------------- 7-Zip interoperability

    private val sevenZip: String? by lazy {
        listOf("7z", "7zz").firstOrNull { cmd ->
            runCatching { ProcessBuilder(cmd).redirectErrorStream(true).start().apply { inputStream.readBytes(); waitFor() }.exitValue() == 0 }.getOrDefault(false)
        }
    }

    private fun sz(vararg args: String, dir: File = tmp.root): String {
        val pb = ProcessBuilder(listOf(sevenZip!!) + args).directory(dir).redirectErrorStream(true)
        pb.environment()["LANG"] = "C.UTF-8"
        pb.environment()["LC_ALL"] = "C.UTF-8"
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(120, TimeUnit.SECONDS)
        assertEquals(out, 0, p.exitValue())
        return out
    }

    @Test fun ourArchivesOpenIn7Zip() = runBlocking {
        assumeTrue(sevenZip != null)
        val src = sampleTree()
        for (type in ArchiveType.entries) {
            val plain = File(tmp.root, "plain.${type.extension}")
            ArchiveOps.compress(listOf(src), plain, type, CompressionLevel.MAX, null, Quiet)
            sz("t", plain.path)
            val listing = sz("l", "-slt", plain.path)
            assertTrue(listing, listing.contains("Path = 사진/여름 휴가/바다.txt") || listing.contains("Path = 사진\\여름 휴가\\바다.txt"))
            val secret = File(tmp.root, "secret.${type.extension}")
            ArchiveOps.compress(listOf(src), secret, type, CompressionLevel.NORMAL, "pw-테스트".toCharArray(), Quiet)
            sz("t", "-ppw-테스트", secret.path)
            val out = tmp.newFolder("7z-out-$type")
            sz("x", "-ppw-테스트", "-o${out.path}", secret.path)
            assertSameTree(src, File(out, "사진"))
        }
    }

    @Test fun sevenZipMadeArchivesOpenHere() = runBlocking {
        assumeTrue(sevenZip != null)
        val src = sampleTree()
        val work = src.parentFile
        data class Case(val file: String, val args: List<String>, val password: String? = null)
        val cases = listOf(
            Case("deflate.zip", listOf("-tzip")),
            Case("deflate64.zip", listOf("-tzip", "-mm=Deflate64")),
            Case("bzip2.zip", listOf("-tzip", "-mm=BZip2")),
            Case("zipcrypto.zip", listOf("-tzip", "-mem=ZipCrypto", "-psecret"), "secret"),
            Case("aes.zip", listOf("-tzip", "-mem=AES256", "-psecret"), "secret"),
            Case("cp949.zip", listOf("-tzip", "-mcp=949")),
            Case("lzma2.7z", listOf("-t7z")),
            Case("lzma.7z", listOf("-t7z", "-m0=LZMA")),
            Case("bzip2.7z", listOf("-t7z", "-m0=BZip2")),
            Case("solid-ultra.7z", listOf("-t7z", "-mx=9", "-ms=on")),
            Case("headers.7z", listOf("-t7z", "-psecret", "-mhe=on"), "secret"),
            Case("split.7z", listOf("-t7z", "-mx=0", "-v100k")),
        )
        for (c in cases) {
            val archive = File(tmp.root, c.file)
            sz(*(listOf("a") + c.args + listOf(archive.path, "사진")).toTypedArray(), dir = work)
            val open = if (c.file == "split.7z") File(tmp.root, "split.7z.002") else archive
            if (c.password != null) {
                expectError(ArchiveError.PASSWORD_REQUIRED) {
                    runBlocking { ArchiveOps.extract(open, null, tmp.newFolder("np-${c.file}"), Quiet) }
                }
            }
            val dest = tmp.newFolder("in-${c.file}")
            val r = ArchiveOps.extract(open, c.password?.toCharArray(), dest, Quiet)
            assertTrue("${c.file}: ${r.failures}", r.failures.isEmpty())
            assertSameTree(src, File(dest, "사진"))
        }
    }

    @Test fun unsupported7zMethodIsReported() = runBlocking {
        assumeTrue(sevenZip != null)
        val src = sampleTree()
        val archive = File(tmp.root, "ppmd.7z")
        sz("a", "-t7z", "-m0=PPMd", archive.path, "사진", dir = src.parentFile)
        expectError(ArchiveError.UNSUPPORTED_METHOD) {
            runBlocking { ArchiveOps.extract(archive, null, tmp.newFolder("ppmd"), Quiet) }
        }
    }
}

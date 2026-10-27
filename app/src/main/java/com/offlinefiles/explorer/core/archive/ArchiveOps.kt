package com.offlinefiles.explorer.core.archive

import com.offlinefiles.explorer.core.Conflict
import com.offlinefiles.explorer.core.ConflictAction
import com.offlinefiles.explorer.core.ConflictPolicy
import com.offlinefiles.explorer.core.Failure
import com.offlinefiles.explorer.core.FailureReason
import com.offlinefiles.explorer.core.FileOps
import com.offlinefiles.explorer.core.OperationListener
import com.offlinefiles.explorer.core.OperationResult
import com.offlinefiles.explorer.core.ProgressTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.tukaani.xz.LZMA2Options
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import net.lingala.zip4j.model.enums.CompressionLevel as ZipLevel

enum class CompressionLevel { STORE, FAST, NORMAL, MAX }

object ArchiveOps {

    // ------------------------------------------------------------------ extract

    /**
     * Extracts [archive] into [destDir].
     * @param selection entry paths to extract; a folder path includes everything below it. null = all.
     * @param stripPrefix removed from entry paths before writing, e.g. "docs/" when extracting
     *   items picked while browsing inside the archive's "docs" folder.
     */
    suspend fun extract(
        archive: File,
        password: CharArray?,
        destDir: File,
        listener: OperationListener,
        selection: Set<String>? = null,
        stripPrefix: String = "",
    ): OperationResult {
        val reader = Archives.open(archive, password)
        return try {
            extractFrom(reader, password, destDir, listener, selection, stripPrefix)
        } finally {
            runCatching { reader.close() }
        }
    }

    suspend fun extractFrom(
        reader: ArchiveReader,
        password: CharArray?,
        destDir: File,
        listener: OperationListener,
        selection: Set<String>? = null,
        stripPrefix: String = "",
    ): OperationResult {
        val tracker = ProgressTracker(listener)
        val policy = ConflictPolicy(listener)
        tracker.preparing()

        fun wanted(e: ArchiveEntryInfo): Boolean =
            selection == null || selection.any { s -> e.path == s || e.path.startsWith("$s/") }

        fun relativeOf(e: ArchiveEntryInfo): String? =
            if (stripPrefix.isEmpty()) e.path
            else if (e.path.startsWith(stripPrefix)) e.path.removePrefix(stripPrefix).ifEmpty { null } else null

        val targets = reader.entries.filter { wanted(it) && relativeOf(it) != null }
        if (password == null && targets.any { it.encrypted }) throw ArchiveException(ArchiveError.PASSWORD_REQUIRED)
        val files = targets.filter { !it.isDirectory }
        tracker.start(files.sumOf { it.size }, files.size)

        if (!destDir.isDirectory && !destDir.mkdirs()) {
            return OperationResult(0, failures = listOf(Failure(destDir.path, FailureReason.ACCESS_DENIED)))
        }

        val failures = mutableListOf<Failure>()
        val topLevel = LinkedHashSet<File>()
        var succeeded = 0
        var skipped = 0
        fun noteTop(rel: String) {
            topLevel += File(destDir, rel.substringBefore('/'))
        }

        // Folders first, so empty folders are kept too. Existing folders are merged.
        for (d in targets) {
            if (!d.isDirectory) continue
            val rel = relativeOf(d) ?: continue
            val dir = File(destDir, rel)
            if (!dir.isDirectory && !dir.mkdirs()) failures += Failure(dir.path, FailureReason.IO_ERROR)
            else noteTop(rel)
        }

        val wantedPaths = files.mapTo(HashSet()) { it.path }
        try { reader.readFiles({ it.path in wantedPaths }) { info, input ->
            currentCoroutineContext().ensureActive()
            val rel = relativeOf(info) ?: return@readFiles
            var target = File(destDir, rel)
            tracker.current(info.name)
            var replacing = false
            if (target.exists()) {
                if (target.isDirectory) {
                    target = FileOps.uniqueChild(target.parentFile!!, target.name, false)
                } else {
                    val action = policy.decide(
                        Conflict(
                            name = target.name,
                            targetPath = target.path,
                            sourceIsDirectory = false,
                            targetIsDirectory = false,
                            sourceSize = info.size,
                            sourceModified = info.lastModified,
                            targetSize = target.length(),
                            targetModified = target.lastModified(),
                        ),
                    )
                    when (action) {
                        ConflictAction.SKIP -> {
                            skipped++
                            tracker.itemDone(bytesIfSkipped = info.size)
                            return@readFiles
                        }
                        ConflictAction.KEEP_BOTH -> target = FileOps.uniqueChild(target.parentFile!!, target.name, false)
                        ConflictAction.OVERWRITE -> replacing = true
                    }
                }
            }
            val parent = target.parentFile!!
            if (!parent.isDirectory && !parent.mkdirs()) {
                failures += Failure(target.path, FailureReason.IO_ERROR)
                tracker.itemDone(bytesIfSkipped = info.size)
                return@readFiles
            }
            val out = if (replacing) File(parent, ".${target.name}.${System.nanoTime()}.part") else target
            try {
                FileOutputStream(out).use { FileOps.copyStream(input, it, tracker) }
                if (replacing) FileOps.replace(out, target)
                if (info.lastModified > 0) target.setLastModified(info.lastModified)
                succeeded++
                noteTop(rel)
            } catch (e: CancellationException) {
                out.delete()
                throw e
            } catch (e: SourceReadException) {
                out.delete()
                val t = Archives.translate(e, password)
                // A wrong password, or a damaged 7z stream, ruins everything that follows.
                if (t is ArchiveException && (t.error != ArchiveError.CORRUPT || reader.type == ArchiveType.SEVEN_Z)) throw t
                failures += Failure(info.path, FailureReason.IO_ERROR, t.message)
            } catch (e: IOException) {
                out.delete()
                val reason = FileOps.reasonOf(e, target)
                failures += Failure(target.path, reason, e.message)
                if (reason == FailureReason.NO_SPACE) throw e
            }
            tracker.itemDone()
        } } catch (e: SourceReadException) {
            throw Archives.translate(e, password)
        } catch (e: net.lingala.zip4j.exception.ZipException) {
            throw Archives.translate(e, password)
        }
        tracker.finish()
        if (reader.unsafeEntries > 0) {
            failures += Failure(reader.entries.firstOrNull()?.path ?: "", FailureReason.UNSAFE_PATH, "${reader.unsafeEntries}")
        }
        return OperationResult(succeeded, skipped, failures, topLevel.filter { it.exists() })
    }

    // ------------------------------------------------------------------ compress

    suspend fun compress(
        sources: List<File>,
        output: File,
        type: ArchiveType,
        level: CompressionLevel,
        password: CharArray?,
        listener: OperationListener,
    ): OperationResult {
        val tracker = ProgressTracker(listener)
        tracker.preparing()
        val failures = mutableListOf<Failure>()
        val items = mutableListOf<Pair<File, String>>()
        for (src in sources) collect(src, src.name, items, failures, output)
        val fileItems = items.filter { !it.first.isDirectory }
        tracker.start(fileItems.sumOf { it.first.length() }, fileItems.size)

        val parent = output.absoluteFile.parentFile ?: throw IOException("no parent")
        val temp = File(parent, ".${output.name}.${System.nanoTime()}.part")
        try {
            when (type) {
                ArchiveType.ZIP -> writeZip(temp, items, level, password, tracker)
                ArchiveType.SEVEN_Z -> write7z(temp, items, level, password, tracker)
            }
            if (output.exists()) FileOps.replace(temp, output)
            else if (!temp.renameTo(output)) Files.move(temp.toPath(), output.toPath())
        } catch (e: Throwable) {
            temp.delete()
            throw e
        }
        tracker.finish()
        return OperationResult(fileItems.size, failures = failures, outputs = listOf(output))
    }

    private suspend fun collect(f: File, name: String, out: MutableList<Pair<File, String>>, failures: MutableList<Failure>, exclude: File) {
        currentCoroutineContext().ensureActive()
        if (f.absolutePath == exclude.absolutePath) return
        if (f.isDirectory && !Files.isSymbolicLink(f.toPath())) {
            out += f to name
            val children = f.listFiles()
            if (children == null) {
                failures += Failure(f.path, FailureReason.ACCESS_DENIED)
                return
            }
            children.sortBy { it.name }
            for (c in children) collect(c, "$name/${c.name}", out, failures, exclude)
        } else if (f.isFile && f.canRead()) {
            out += f to name
        } else {
            failures += Failure(f.path, FailureReason.ACCESS_DENIED)
        }
    }

    private suspend fun writeZip(
        target: File,
        items: List<Pair<File, String>>,
        level: CompressionLevel,
        password: CharArray?,
        tracker: ProgressTracker,
    ) {
        BufferedOutputStream(FileOutputStream(target), 64 * 1024).use { bos ->
            ZipOutputStream(bos, password).use { zos ->
                for ((f, name) in items) {
                    val isDir = f.isDirectory
                    val p = ZipParameters().apply {
                        fileNameInZip = if (isDir) "$name/" else name
                        lastModifiedFileTime = f.lastModified()
                        if (isDir || level == CompressionLevel.STORE) {
                            compressionMethod = CompressionMethod.STORE
                            if (!isDir) entrySize = f.length()
                        } else {
                            compressionMethod = CompressionMethod.DEFLATE
                            compressionLevel = when (level) {
                                CompressionLevel.FAST -> ZipLevel.FASTEST
                                CompressionLevel.MAX -> ZipLevel.ULTRA
                                else -> ZipLevel.NORMAL
                            }
                        }
                        if (password != null && !isDir) {
                            isEncryptFiles = true
                            encryptionMethod = EncryptionMethod.AES
                            aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                        }
                    }
                    if (!isDir) tracker.current(f.name)
                    zos.putNextEntry(p)
                    if (!isDir) FileInputStream(f).use { FileOps.copyStream(it, zos, tracker) }
                    zos.closeEntry()
                    if (!isDir) tracker.itemDone()
                }
            }
        }
    }

    private suspend fun write7z(
        target: File,
        items: List<Pair<File, String>>,
        level: CompressionLevel,
        password: CharArray?,
        tracker: ProgressTracker,
    ) {
        SevenZOutputFile(target, password).use { out ->
            out.setContentMethods(
                listOf(
                    if (level == CompressionLevel.STORE) SevenZMethodConfiguration(SevenZMethod.COPY)
                    else SevenZMethodConfiguration(SevenZMethod.LZMA2, lzma2Options(level)),
                ),
            )
            val buf = ByteArray(256 * 1024)
            for ((f, name) in items) {
                val entry = out.createArchiveEntry(f, name)
                out.putArchiveEntry(entry)
                if (!f.isDirectory) {
                    tracker.current(f.name)
                    FileInputStream(f).use { input ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            tracker.addBytes(n.toLong())
                        }
                    }
                }
                out.closeArchiveEntry()
                if (!f.isDirectory) tracker.itemDone()
            }
            out.finish()
        }
    }

    /** Highest LZMA2 preset for [level] whose encoder fits comfortably in the free heap. */
    internal fun lzma2Options(level: CompressionLevel): LZMA2Options {
        var preset = when (level) {
            CompressionLevel.FAST -> 1
            CompressionLevel.MAX -> 7
            else -> 5
        }
        val rt = Runtime.getRuntime()
        val free = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
        while (preset > 1) {
            val opts = LZMA2Options(preset)
            if (opts.encoderMemoryUsage.toLong() * 1024L < free / 2) return opts
            preset--
        }
        return LZMA2Options(1)
    }
}

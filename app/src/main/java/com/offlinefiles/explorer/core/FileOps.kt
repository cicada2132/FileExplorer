package com.offlinefiles.explorer.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

enum class NameError { EMPTY, INVALID_CHARS, RESERVED, TOO_LONG }

class FileOpException(val reason: FailureReason, message: String? = null) : IOException(message ?: reason.name)

data class DirStats(val files: Int = 0, val folders: Int = 0, val bytes: Long = 0)

object FileOps {

    private const val BUFFER = 256 * 1024
    private val invalidChars = "\"*/:<>?\\|"

    // ---------------------------------------------------------------- names

    fun validateName(name: String): NameError? {
        if (name.isBlank()) return NameError.EMPTY
        if (name == "." || name == "..") return NameError.RESERVED
        if (name.any { it.code < 0x20 || it in invalidChars }) return NameError.INVALID_CHARS
        if (name.toByteArray(Charsets.UTF_8).size > 255) return NameError.TOO_LONG
        return null
    }

    /** "photo.jpg" → ("photo", ".jpg"); folders and dot-files keep the whole name as base. */
    fun splitName(name: String, isDirectory: Boolean): Pair<String, String> {
        if (isDirectory) return name to ""
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) name to "" else name.substring(0, dot) to name.substring(dot)
    }

    /** [name] if free in [dir], otherwise "name (1).ext", "name (2).ext", ... */
    fun uniqueChild(dir: File, name: String, isDirectory: Boolean): File {
        val first = File(dir, name)
        if (!first.exists()) return first
        val (base, ext) = splitName(name, isDirectory)
        // Don't stack suffixes: "a (1)" → "a (2)", not "a (1) (1)".
        val m = Regex("""^(.*) \((\d{1,3})\)$""").find(base)
        val stem = m?.groupValues?.get(1) ?: base
        var i = (m?.groupValues?.get(2)?.toIntOrNull() ?: 0) + 1
        while (true) {
            val candidate = File(dir, "$stem ($i)$ext")
            if (!candidate.exists()) return candidate
            i++
        }
    }

    // ---------------------------------------------------------------- simple ops

    fun createFolder(parent: File, name: String): File {
        validateName(name)?.let { throw FileOpException(FailureReason.IO_ERROR, it.name) }
        val f = File(parent, name)
        if (f.exists()) throw FileOpException(FailureReason.ALREADY_EXISTS)
        if (!f.mkdir()) throw FileOpException(accessReason(parent))
        return f
    }

    fun createFile(parent: File, name: String): File {
        validateName(name)?.let { throw FileOpException(FailureReason.IO_ERROR, it.name) }
        val f = File(parent, name)
        if (f.exists()) throw FileOpException(FailureReason.ALREADY_EXISTS)
        val ok = try { f.createNewFile() } catch (e: IOException) { false }
        if (!ok) throw FileOpException(accessReason(parent))
        return f
    }

    fun rename(file: File, newName: String): File {
        validateName(newName)?.let { throw FileOpException(FailureReason.IO_ERROR, it.name) }
        val parent = file.parentFile ?: throw FileOpException(FailureReason.ACCESS_DENIED)
        val target = File(parent, newName)
        if (target.path == file.path) return file
        if (target.exists()) {
            // On case-insensitive storage "a.txt" → "A.txt" points at the same file.
            if (!isSameFile(file, target)) throw FileOpException(FailureReason.ALREADY_EXISTS)
            val tmp = File(parent, ".rename-${System.nanoTime()}")
            if (!file.renameTo(tmp)) throw FileOpException(accessReason(parent))
            if (!tmp.renameTo(target)) {
                tmp.renameTo(file)
                throw FileOpException(accessReason(parent))
            }
            return target
        }
        if (!file.renameTo(target)) throw FileOpException(accessReason(parent))
        return target
    }

    // ---------------------------------------------------------------- copy / move

    suspend fun copyOrMove(
        sources: List<File>,
        destDir: File,
        move: Boolean,
        listener: OperationListener,
    ): OperationResult {
        val tracker = ProgressTracker(listener)
        val policy = ConflictPolicy(listener)
        val failures = mutableListOf<Failure>()
        val outputs = mutableListOf<File>()
        var succeeded = 0
        var skipped = 0

        tracker.preparing()
        if (!destDir.isDirectory) {
            return OperationResult(0, failures = listOf(Failure(destDir.path, FailureReason.NOT_FOUND)))
        }

        // 1. Validate and resolve top-level targets; try cheap renames for moves.
        val pending = mutableListOf<Pair<File, File>>()
        for (src in sources) {
            currentCoroutineContext().ensureActive()
            if (!src.exists()) {
                failures += Failure(src.path, FailureReason.NOT_FOUND); continue
            }
            if (src.isDirectory && isSameOrAncestor(src, destDir)) {
                failures += Failure(src.path, FailureReason.INTO_ITSELF); continue
            }
            val sameFolder = src.parentFile?.let { isSameFile(it, destDir) } == true
            if (move && sameFolder) {
                skipped++; continue
            }
            val target = resolveTopLevelTarget(src, destDir, policy, duplicate = sameFolder)
            if (target == null) {
                skipped++; continue
            }
            if (move && !target.exists() && src.renameTo(target)) {
                succeeded++; outputs += target; continue
            }
            pending += src to target
        }

        // 2. Count what actually needs copying, then copy.
        val totals = DirStats().let { acc ->
            var s = acc
            for ((src, _) in pending) s = s.plus(measureTree(src))
            s
        }
        tracker.start(totals.bytes, totals.files)
        for ((src, target) in pending) {
            val sub = mutableListOf<Failure>()
            val r = copyTree(src, target, move, tracker, policy, sub)
            failures += sub
            when (r) {
                TreeResult.OK -> { succeeded++; outputs += target }
                TreeResult.PARTIAL -> { if (target.exists()) outputs += target }
                TreeResult.SKIPPED -> skipped++
            }
        }
        tracker.finish()
        return OperationResult(succeeded, skipped, failures, outputs)
    }

    private suspend fun resolveTopLevelTarget(
        src: File,
        destDir: File,
        policy: ConflictPolicy,
        duplicate: Boolean,
    ): File? {
        val candidate = File(destDir, src.name)
        if (!candidate.exists()) return candidate
        if (duplicate || isSameFile(src, candidate)) return uniqueChild(destDir, src.name, src.isDirectory)
        return when (policy.decide(conflictOf(src, candidate))) {
            ConflictAction.SKIP -> null
            ConflictAction.KEEP_BOTH -> uniqueChild(destDir, src.name, src.isDirectory)
            ConflictAction.OVERWRITE ->
                if (src.isDirectory == candidate.isDirectory) candidate
                else uniqueChild(destDir, src.name, src.isDirectory)
        }
    }

    private enum class TreeResult { OK, PARTIAL, SKIPPED }

    private suspend fun copyTree(
        src: File,
        target: File,
        move: Boolean,
        tracker: ProgressTracker,
        policy: ConflictPolicy,
        failures: MutableList<Failure>,
    ): TreeResult {
        currentCoroutineContext().ensureActive()
        if (!src.isDirectory || Files.isSymbolicLink(src.toPath())) {
            return try {
                tracker.current(src.name)
                copyFile(src, target, tracker)
                if (move && !src.delete()) failures += Failure(src.path, FailureReason.ACCESS_DENIED)
                tracker.itemDone()
                TreeResult.OK
            } catch (e: IOException) {
                failures += Failure(src.path, reasonOf(e, target), e.message)
                tracker.itemDone()
                TreeResult.PARTIAL
            }
        }

        if (!target.isDirectory && !target.mkdirs()) {
            failures += Failure(target.path, accessReason(target.parentFile))
            return TreeResult.PARTIAL
        }
        val children = src.listFiles()
        if (children == null) {
            failures += Failure(src.path, FailureReason.ACCESS_DENIED)
            return TreeResult.PARTIAL
        }
        var allOk = true
        for (child in children) {
            currentCoroutineContext().ensureActive()
            var childTarget = File(target, child.name)
            if (childTarget.exists()) {
                val bothDirs = child.isDirectory && childTarget.isDirectory
                if (!bothDirs) {
                    when (policy.decide(conflictOf(child, childTarget))) {
                        ConflictAction.SKIP -> {
                            val skippedStats = measureTree(child)
                            repeat(skippedStats.files) { tracker.itemDone() }
                            tracker.addBytes(skippedStats.bytes)
                            allOk = false
                            continue
                        }
                        ConflictAction.KEEP_BOTH -> childTarget = uniqueChild(target, child.name, child.isDirectory)
                        ConflictAction.OVERWRITE ->
                            if (child.isDirectory != childTarget.isDirectory) {
                                childTarget = uniqueChild(target, child.name, child.isDirectory)
                            }
                    }
                }
            } else if (move) {
                // Same volume? A rename is instant and keeps everything below it.
                val stats = if (child.isDirectory) measureTree(child) else null
                if (child.renameTo(childTarget)) {
                    if (stats != null) {
                        repeat(stats.files) { tracker.itemDone() }
                        tracker.addBytes(stats.bytes)
                    } else {
                        tracker.addBytes(childTarget.length())
                        tracker.itemDone()
                    }
                    continue
                }
            }
            if (copyTree(child, childTarget, move, tracker, policy, failures) != TreeResult.OK) allOk = false
        }
        target.setLastModified(src.lastModified())
        if (move && allOk) {
            if (!src.delete() && src.exists()) {
                failures += Failure(src.path, FailureReason.ACCESS_DENIED)
                allOk = false
            }
        }
        return if (allOk) TreeResult.OK else TreeResult.PARTIAL
    }

    /** Copies one file. An existing target is replaced only after the new copy is complete. */
    private suspend fun copyFile(src: File, target: File, tracker: ProgressTracker) {
        val replacing = target.exists()
        if (replacing && isSameFile(src, target)) throw FileOpException(FailureReason.INTO_ITSELF)
        val out = if (replacing) File(target.parentFile, ".${target.name}.${System.nanoTime()}.part") else target
        try {
            FileInputStream(src).use { input ->
                FileOutputStream(out).use { output -> copyStream(input, output, tracker) }
            }
            if (replacing) replace(out, target)
        } catch (e: Throwable) {
            out.delete()
            throw e
        }
        target.setLastModified(src.lastModified())
    }

    internal suspend fun copyStream(input: InputStream, output: OutputStream, tracker: ProgressTracker?) {
        val buf = ByteArray(BUFFER)
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buf)
            if (n < 0) break
            output.write(buf, 0, n)
            tracker?.addBytes(n.toLong())
        }
    }

    internal fun replace(newFile: File, target: File) {
        try {
            Files.move(newFile.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            Files.move(newFile.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    // ---------------------------------------------------------------- delete

    suspend fun delete(targets: List<File>, listener: OperationListener): OperationResult {
        val tracker = ProgressTracker(listener)
        tracker.preparing()
        var total = 0
        for (t in targets) {
            val s = measureTree(t)
            total += s.files + s.folders + (if (t.isDirectory) 1 else 0)
        }
        tracker.start(0, total)
        val failures = mutableListOf<Failure>()
        var succeeded = 0
        for (t in targets) {
            if (!t.exists() && !Files.isSymbolicLink(t.toPath())) {
                failures += Failure(t.path, FailureReason.NOT_FOUND); continue
            }
            tracker.current(t.name)
            if (deleteTree(t, tracker, failures)) succeeded++
        }
        tracker.finish()
        return OperationResult(succeeded, failures = failures)
    }

    private suspend fun deleteTree(f: File, tracker: ProgressTracker, failures: MutableList<Failure>): Boolean {
        currentCoroutineContext().ensureActive()
        var ok = true
        if (f.isDirectory && !Files.isSymbolicLink(f.toPath())) {
            val children = f.listFiles()
            if (children == null) {
                failures += Failure(f.path, FailureReason.ACCESS_DENIED)
                return false
            }
            for (c in children) if (!deleteTree(c, tracker, failures)) ok = false
        }
        if (!f.delete() && (f.exists() || Files.isSymbolicLink(f.toPath()))) {
            if (ok) failures += Failure(f.path, FailureReason.ACCESS_DENIED)
            ok = false
        }
        tracker.itemDone()
        return ok
    }

    // ---------------------------------------------------------------- measuring

    /** Files, folders (excluding [root] itself) and bytes below [root]. Follows no symlinks. */
    suspend fun measureTree(root: File, onUpdate: ((DirStats) -> Unit)? = null): DirStats {
        if (!root.isDirectory || Files.isSymbolicLink(root.toPath())) {
            return DirStats(files = 1, bytes = root.length())
        }
        var files = 0
        var folders = 0
        var bytes = 0L
        var lastUpdate = System.nanoTime()
        val stack = ArrayDeque<File>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val dir = stack.removeLast()
            val names = dir.list() ?: continue
            for (name in names) {
                val f = File(dir, name)
                val item = DirectoryLister.stat(f)
                if (item.isDirectory && !Files.isSymbolicLink(f.toPath())) {
                    folders++
                    stack.addLast(f)
                } else {
                    files++
                    bytes += item.size
                }
            }
            if (onUpdate != null && System.nanoTime() - lastUpdate > 150_000_000L) {
                lastUpdate = System.nanoTime()
                onUpdate(DirStats(files, folders, bytes))
            }
        }
        return DirStats(files, folders, bytes)
    }

    private fun DirStats.plus(o: DirStats) = DirStats(files + o.files, folders + o.folders, bytes + o.bytes)

    // ---------------------------------------------------------------- helpers

    fun isSameFile(a: File, b: File): Boolean = try {
        a.exists() && b.exists() && Files.isSameFile(a.toPath(), b.toPath())
    } catch (e: Exception) {
        a.absolutePath == b.absolutePath
    }

    /** True if [ancestor] is [dir] itself or one of its parents. */
    fun isSameOrAncestor(ancestor: File, dir: File): Boolean {
        var cur: File? = dir.absoluteFile
        while (cur != null) {
            if (isSameFile(cur, ancestor)) return true
            cur = cur.parentFile
        }
        return false
    }

    private fun conflictOf(src: File, existing: File) = Conflict(
        name = existing.name,
        targetPath = existing.path,
        sourceIsDirectory = src.isDirectory,
        targetIsDirectory = existing.isDirectory,
        sourceSize = if (src.isDirectory) 0 else src.length(),
        sourceModified = src.lastModified(),
        targetSize = if (existing.isDirectory) 0 else existing.length(),
        targetModified = existing.lastModified(),
    )

    internal fun accessReason(dir: File?): FailureReason =
        if (dir != null && dir.exists() && !dir.canWrite()) FailureReason.ACCESS_DENIED else FailureReason.IO_ERROR

    internal fun reasonOf(e: Throwable, target: File?): FailureReason {
        if (e is FileOpException) return e.reason
        if (e is AccessDeniedException) return FailureReason.ACCESS_DENIED
        val msg = e.message.orEmpty()
        return when {
            msg.contains("ENOSPC") || msg.contains("No space", ignoreCase = true) -> FailureReason.NO_SPACE
            msg.contains("EACCES") || msg.contains("EPERM") || msg.contains("Permission denied", true) ||
                msg.contains("Read-only", true) || msg.contains("EROFS") -> FailureReason.ACCESS_DENIED
            msg.contains("ENOENT") && target?.parentFile?.exists() == false -> FailureReason.NOT_FOUND
            else -> FailureReason.IO_ERROR
        }
    }
}

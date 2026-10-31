package com.offlinefiles.explorer.ops

import com.offlinefiles.explorer.core.FailureReason
import com.offlinefiles.explorer.core.FileOpException
import com.offlinefiles.explorer.core.FileOps
import com.offlinefiles.explorer.core.ListResult
import com.offlinefiles.explorer.core.LocalStore
import com.offlinefiles.explorer.core.OperationListener
import com.offlinefiles.explorer.core.OperationResult
import com.offlinefiles.explorer.core.ProgressTracker
import com.offlinefiles.explorer.core.StoreOps
import com.offlinefiles.explorer.core.StoreResolver
import com.offlinefiles.explorer.core.archive.ArchiveFormats
import com.offlinefiles.explorer.core.archive.ArchiveOps
import com.offlinefiles.explorer.core.archive.ArchiveType
import com.offlinefiles.explorer.core.archive.Archives
import com.offlinefiles.explorer.core.archive.CompressionLevel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * The archive code needs real files, which USB drives opened through the system file picker don't
 * have. Archives stored on such a drive are copied into the app's cache first; archives and
 * extracted files meant for one are made in the cache and then copied over.
 */
class DocumentStaging(private val root: File, private val stores: StoreResolver) {
    /** A local copy of an archive; hand it back with [release] when done. */
    class Staged internal constructor(val file: File, internal val dir: File)

    private val users = HashMap<String, Int>()
    private val copyLock = Mutex()

    /** Leftovers from an earlier run. Only call while nothing is running. */
    fun clearAll() {
        root.deleteRecursively()
    }

    /** A local copy of the archive at [path], with the other parts of a split archive beside it. */
    suspend fun acquireArchive(path: String, listener: OperationListener?): Staged {
        val store = stores.storeFor(path)
        val item = store.stat(path) ?: throw FileOpException(FailureReason.NOT_FOUND)
        val parent = File(path).parent ?: throw FileOpException(FailureReason.NOT_FOUND)
        val key = Integer.toHexString("$path|${item.size}|${item.lastModified}".hashCode())
        val dir = File(root, "archives/$key")
        synchronized(users) { users[dir.path] = (users[dir.path] ?: 0) + 1 }
        val staged = Staged(File(dir, item.name), dir)
        try {
            copyLock.withLock {
                val siblings = (store.list(parent) as? ListResult.Ok)?.items.orEmpty().filter { !it.isDirectory }
                val names = ArchiveFormats.partNames(item.name, siblings.map { it.name })
                val parts = names.mapNotNull { n -> siblings.firstOrNull { it.name == n } }.ifEmpty { listOf(item) }
                val missing = parts.filter { p -> File(dir, p.name).let { !it.isFile || it.length() != p.size } }
                if (missing.isNotEmpty()) {
                    if (!dir.isDirectory && !dir.mkdirs()) throw FileOpException(FailureReason.IO_ERROR)
                    val tracker = listener?.let { ProgressTracker(it) }
                    tracker?.start(missing.sumOf { it.size }, missing.size)
                    for (p in missing) {
                        tracker?.current(p.name)
                        val tmp = File(dir, ".${p.name}.part")
                        try {
                            store.openInput(p.path).use { input -> FileOutputStream(tmp).use { FileOps.copyStream(input, it, tracker) } }
                            if (!tmp.renameTo(File(dir, p.name))) throw IOException("Can't keep ${p.name}")
                        } finally {
                            tmp.delete()
                        }
                        tracker?.itemDone()
                    }
                }
            }
        } catch (e: Throwable) {
            release(staged)
            throw e
        }
        return staged
    }

    fun release(staged: Staged) {
        synchronized(users) {
            val left = (users[staged.dir.path] ?: 1) - 1
            if (left > 0) {
                users[staged.dir.path] = left
            } else {
                users.remove(staged.dir.path)
                staged.dir.deleteRecursively()
            }
        }
    }

    /**
     * Extracts the local [archive] into the USB folder [destDir]: into the cache first, then moved
     * over with the usual questions about name clashes. [archiveName] names the "Extract to folder" folder.
     */
    suspend fun extractInto(
        archive: File,
        archiveName: String,
        password: CharArray?,
        destDir: String,
        listener: OperationListener,
        selection: Set<String>?,
        stripPrefix: String,
        smartFolder: Boolean,
    ): OperationResult {
        val work = newWorkDir()
        try {
            val reader = Archives.open(archive, password)
            val extracted = try {
                val target = if (!smartFolder) work else {
                    val base = ArchiveFormats.baseName(archiveName)
                    val single = ArchiveOps.singleTopFolder(reader.entries)
                    if (single != null && single.equals(base, ignoreCase = true)) work
                    else File(work, StoreOps.uniqueName(stores.storeFor(destDir), destDir, base, isDirectory = true))
                }
                ArchiveOps.extractFrom(reader, password, target, listener, selection, stripPrefix)
            } finally {
                runCatching { reader.close() }
            }
            val tops = work.listFiles().orEmpty().sortedBy { it.name }.map { it.path }
            if (tops.isEmpty()) return extracted.copy(outputs = emptyList())
            val moved = StoreOps.copyOrMove(tops, destDir, move = true, stores, listener)
            return OperationResult(
                succeeded = extracted.succeeded,
                skipped = extracted.skipped + moved.skipped,
                failures = extracted.failures + moved.failures,
                outputs = moved.outputs,
            )
        } finally {
            work.deleteRecursively()
        }
    }

    /** Compresses [sources] into [output] when either side is on a USB drive opened through the file picker. */
    suspend fun compress(
        sources: List<String>,
        output: String,
        type: ArchiveType,
        level: CompressionLevel,
        password: CharArray?,
        listener: OperationListener,
    ): OperationResult {
        val work = newWorkDir()
        try {
            val remote = sources.filter { stores.storeFor(it) != LocalStore }
            val inputs = if (remote.isEmpty()) sources.map(::File) else {
                val copies = File(work, "src").apply { mkdirs() }
                val copied = StoreOps.copyOrMove(remote, copies.path, move = false, stores, listener)
                if (copied.outputs.isEmpty()) return OperationResult(0, failures = copied.failures)
                sources.map { s -> if (s in remote) File(copies, File(s).name) else File(s) }.filter { it.exists() }
            }
            val parent = File(output).parent ?: throw FileOpException(FailureReason.NOT_FOUND)
            if (stores.storeFor(output) == LocalStore) {
                return ArchiveOps.compress(inputs, File(output), type, level, password, listener)
            }
            val local = File(work, File(output).name)
            val made = ArchiveOps.compress(inputs, local, type, level, password, listener)
            val moved = StoreOps.copyOrMove(listOf(local.path), parent, move = true, stores, listener)
            return OperationResult(made.succeeded, failures = made.failures + moved.failures, outputs = moved.outputs)
        } finally {
            work.deleteRecursively()
        }
    }

    private fun newWorkDir(): File = File(root, "work-${System.nanoTime()}").apply { mkdirs() }
}

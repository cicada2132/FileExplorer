package com.offlinefiles.explorer.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import java.io.IOException

/**
 * Copy, move, delete, search and the simple file actions for any [FileStore].
 *
 * The app uses this whenever a USB document tree is involved, on either side; work that only
 * touches local files keeps using [FileOps]. The rules are the same as there: folders merge,
 * "keep both" numbers the new item, and a replaced file is only swapped in once the new copy is
 * complete.
 */
object StoreOps {

    // ---------------------------------------------------------------- names & simple actions

    /** [name] if it is free in [dir], otherwise "name (1).ext", "name (2).ext"… (as [FileOps.uniqueChild]). */
    fun uniqueName(store: FileStore, dir: String, name: String, isDirectory: Boolean): String {
        if (store.stat(join(dir, name)) == null) return name
        val (base, ext) = FileOps.splitName(name, isDirectory)
        val m = Regex("""^(.*) \((\d{1,3})\)$""").find(base)
        val stem = m?.groupValues?.get(1) ?: base
        var i = (m?.groupValues?.get(2)?.toIntOrNull() ?: 0) + 1
        while (true) {
            val candidate = "$stem ($i)$ext"
            if (store.stat(join(dir, candidate)) == null) return candidate
            i++
        }
    }

    fun createFolder(store: FileStore, parent: String, name: String): String {
        FileOps.validateName(name)?.let { throw FileOpException(FailureReason.IO_ERROR, it.name) }
        if (store.stat(join(parent, name)) != null) throw FileOpException(FailureReason.ALREADY_EXISTS)
        return store.createFolder(parent, name)
    }

    fun createFile(store: FileStore, parent: String, name: String): String {
        FileOps.validateName(name)?.let { throw FileOpException(FailureReason.IO_ERROR, it.name) }
        if (store.stat(join(parent, name)) != null) throw FileOpException(FailureReason.ALREADY_EXISTS)
        val created = store.createFile(parent, name)
        created.output.close()
        return created.path
    }

    fun rename(store: FileStore, path: String, newName: String): String {
        FileOps.validateName(newName)?.let { throw FileOpException(FailureReason.IO_ERROR, it.name) }
        return store.rename(path, newName)
    }

    // ---------------------------------------------------------------- copy / move

    suspend fun copyOrMove(
        sources: List<String>,
        destDir: String,
        move: Boolean,
        stores: StoreResolver,
        listener: OperationListener,
    ): OperationResult {
        val tracker = ProgressTracker(listener)
        val policy = ConflictPolicy(listener)
        val failures = mutableListOf<Failure>()
        val outputs = mutableListOf<String>()
        var succeeded = 0
        var skipped = 0

        tracker.preparing()
        val dst = stores.storeFor(destDir)
        if (dst.stat(destDir)?.isDirectory != true) {
            return OperationResult(0, failures = listOf(Failure(destDir, FailureReason.NOT_FOUND)))
        }

        // 1. Validate and resolve top-level targets; try cheap moves within one volume.
        val pending = mutableListOf<Pending>()
        for (src in sources) {
            currentCoroutineContext().ensureActive()
            val store = stores.storeFor(src)
            val item = store.stat(src)
            if (item == null) {
                failures += Failure(src, FailureReason.NOT_FOUND); continue
            }
            val sameStore = store == dst
            if (item.isDirectory && sameStore && store.isSameOrAncestor(src, destDir)) {
                failures += Failure(src, FailureReason.INTO_ITSELF); continue
            }
            val sameFolder = sameStore && File(src).parent?.let { dst.isSame(it, destDir) } == true
            if (move && sameFolder) {
                skipped++; continue
            }
            val target = resolveTopLevel(store, item, dst, destDir, policy, duplicate = sameFolder)
            if (target == null) {
                skipped++; continue
            }
            if (move && sameStore && target.existing == null && target.name == item.name) {
                val moved = store.moveInto(src, destDir)
                if (moved != null) {
                    succeeded++; outputs += moved; continue
                }
            }
            pending += Pending(store, item, target.name, target.existing)
        }

        // 2. Count what actually needs copying, then copy.
        var totals = DirStats()
        for (p in pending) totals = totals.plus(measureTree(p.store, p.item))
        tracker.start(totals.bytes, totals.files)
        for (p in pending) {
            val sub = mutableListOf<Failure>()
            val r = copyTree(p.store, p.item, dst, destDir, p.name, p.existing, move, tracker, policy, sub)
            failures += sub
            when (r.result) {
                TreeResult.OK -> {
                    succeeded++; r.path?.let { outputs += it }
                }
                TreeResult.PARTIAL -> r.path?.let { outputs += it }
                TreeResult.SKIPPED -> skipped++
            }
        }
        tracker.finish()
        return OperationResult(succeeded, skipped, failures, outputs.map(::File))
    }

    private class Pending(val store: FileStore, val item: FileItem, val name: String, val existing: FileItem?)

    /** The name to use in the destination, and the item already there (to merge into or replace). */
    private class Target(val name: String, val existing: FileItem?)

    private suspend fun resolveTopLevel(
        src: FileStore,
        item: FileItem,
        dst: FileStore,
        destDir: String,
        policy: ConflictPolicy,
        duplicate: Boolean,
    ): Target? {
        val existing = dst.stat(join(destDir, item.name)) ?: return Target(item.name, null)
        if (duplicate || (src == dst && dst.isSame(item.path, existing.path))) {
            return Target(uniqueName(dst, destDir, item.name, item.isDirectory), null)
        }
        return when (policy.decide(conflictOf(item, existing))) {
            ConflictAction.SKIP -> null
            ConflictAction.KEEP_BOTH -> Target(uniqueName(dst, destDir, item.name, item.isDirectory), null)
            ConflictAction.OVERWRITE ->
                if (item.isDirectory == existing.isDirectory) Target(item.name, existing)
                else Target(uniqueName(dst, destDir, item.name, item.isDirectory), null)
        }
    }

    private enum class TreeResult { OK, PARTIAL, SKIPPED }

    /** How copying one item went, and where it landed (null if nothing was created). */
    private class Copied(val result: TreeResult, val path: String?)

    private suspend fun copyTree(
        src: FileStore,
        item: FileItem,
        dst: FileStore,
        destDir: String,
        name: String,
        existing: FileItem?,
        move: Boolean,
        tracker: ProgressTracker,
        policy: ConflictPolicy,
        failures: MutableList<Failure>,
    ): Copied {
        currentCoroutineContext().ensureActive()
        if (!item.isDirectory || src.isLink(item.path)) {
            return try {
                tracker.current(item.name)
                val path = copyFile(src, item, dst, destDir, name, existing, tracker)
                if (move && !src.delete(item.path)) failures += Failure(item.path, FailureReason.ACCESS_DENIED)
                tracker.itemDone()
                Copied(TreeResult.OK, path)
            } catch (e: IOException) {
                failures += Failure(item.path, FileOps.reasonOf(e, null), e.message)
                tracker.itemDone()
                Copied(TreeResult.PARTIAL, null)
            }
        }

        val targetDir = if (existing != null && existing.isDirectory) existing.path else try {
            dst.createFolder(destDir, name)
        } catch (e: IOException) {
            failures += Failure(join(destDir, name), FileOps.reasonOf(e, null), e.message)
            return Copied(TreeResult.PARTIAL, null)
        }
        val children = (src.list(item.path) as? ListResult.Ok)?.items
        if (children == null) {
            failures += Failure(item.path, FailureReason.ACCESS_DENIED)
            return Copied(TreeResult.PARTIAL, targetDir)
        }
        var allOk = true
        for (child in children) {
            currentCoroutineContext().ensureActive()
            var childName = child.name
            var childExisting = dst.stat(join(targetDir, child.name))
            if (childExisting != null) {
                val bothDirs = child.isDirectory && childExisting.isDirectory
                if (!bothDirs) {
                    when (policy.decide(conflictOf(child, childExisting))) {
                        ConflictAction.SKIP -> {
                            val skippedStats = measureTree(src, child)
                            repeat(skippedStats.files) { tracker.itemDone() }
                            tracker.addBytes(skippedStats.bytes)
                            allOk = false
                            continue
                        }
                        ConflictAction.KEEP_BOTH -> {
                            childName = uniqueName(dst, targetDir, child.name, child.isDirectory)
                            childExisting = null
                        }
                        ConflictAction.OVERWRITE ->
                            if (child.isDirectory != childExisting.isDirectory) {
                                childName = uniqueName(dst, targetDir, child.name, child.isDirectory)
                                childExisting = null
                            }
                    }
                }
            } else if (move && src == dst) {
                // Same volume? A move is instant and keeps everything below it.
                val stats = if (child.isDirectory) measureTree(src, child) else null
                if (src.moveInto(child.path, targetDir) != null) {
                    if (stats != null) {
                        repeat(stats.files) { tracker.itemDone() }
                        tracker.addBytes(stats.bytes)
                    } else {
                        tracker.addBytes(child.size)
                        tracker.itemDone()
                    }
                    continue
                }
            }
            val r = copyTree(src, child, dst, targetDir, childName, childExisting, move, tracker, policy, failures)
            if (r.result != TreeResult.OK) allOk = false
        }
        dst.setLastModified(targetDir, item.lastModified)
        if (move && allOk) {
            if (!src.delete(item.path) && src.stat(item.path) != null) {
                failures += Failure(item.path, FailureReason.ACCESS_DENIED)
                allOk = false
            }
        }
        return Copied(if (allOk) TreeResult.OK else TreeResult.PARTIAL, targetDir)
    }

    /** Copies one file. An existing target is replaced only after the new copy is complete. */
    private suspend fun copyFile(
        src: FileStore,
        item: FileItem,
        dst: FileStore,
        destDir: String,
        name: String,
        existing: FileItem?,
        tracker: ProgressTracker,
    ): String {
        if (existing != null && src == dst && dst.isSame(item.path, existing.path)) {
            throw FileOpException(FailureReason.INTO_ITSELF)
        }
        val created = dst.createFile(destDir, if (existing != null) ".$name.${System.nanoTime()}.part" else name)
        val path = try {
            created.output.use { out -> src.openInput(item.path).use { input -> FileOps.copyStream(input, out, tracker) } }
            if (existing != null) dst.replace(created.path, existing.path) else created.path
        } catch (e: Throwable) {
            runCatching { dst.delete(created.path) }
            throw e
        }
        dst.setLastModified(path, item.lastModified)
        return path
    }

    // ---------------------------------------------------------------- delete

    suspend fun delete(targets: List<String>, stores: StoreResolver, listener: OperationListener): OperationResult {
        val tracker = ProgressTracker(listener)
        tracker.preparing()
        val found = targets.map { path ->
            val store = stores.storeFor(path)
            Triple(path, store, store.stat(path))
        }
        var total = 0
        for ((_, store, item) in found) {
            if (item == null) continue
            val s = measureTree(store, item)
            total += s.files + s.folders + (if (item.isDirectory) 1 else 0)
        }
        tracker.start(0, total)
        val failures = mutableListOf<Failure>()
        var succeeded = 0
        for ((path, store, item) in found) {
            if (item == null) {
                failures += Failure(path, FailureReason.NOT_FOUND); continue
            }
            tracker.current(item.name)
            if (deleteTree(store, item, tracker, failures)) succeeded++
        }
        tracker.finish()
        return OperationResult(succeeded, failures = failures)
    }

    private suspend fun deleteTree(store: FileStore, item: FileItem, tracker: ProgressTracker, failures: MutableList<Failure>): Boolean {
        currentCoroutineContext().ensureActive()
        var ok = true
        val folder = item.isDirectory && !store.isLink(item.path)
        if (folder) {
            val children = (store.list(item.path) as? ListResult.Ok)?.items
            if (children == null) {
                failures += Failure(item.path, FailureReason.ACCESS_DENIED)
                return false
            }
            for (c in children) if (!deleteTree(store, c, tracker, failures)) ok = false
        }
        // A document folder goes with everything in it, so keep it when something inside stayed.
        if (ok || !folder) {
            if (!store.delete(item.path) && store.stat(item.path) != null) {
                if (ok) failures += Failure(item.path, FailureReason.ACCESS_DENIED)
                ok = false
            }
        }
        tracker.itemDone()
        return ok
    }

    // ---------------------------------------------------------------- measuring & search

    /** Files, folders (excluding [root] itself) and bytes below [root]. Follows no links. */
    suspend fun measureTree(store: FileStore, root: FileItem, onUpdate: ((DirStats) -> Unit)? = null): DirStats {
        if (!root.isDirectory || store.isLink(root.path)) return DirStats(files = 1, bytes = root.size)
        var files = 0
        var folders = 0
        var bytes = 0L
        var lastUpdate = System.nanoTime()
        val stack = ArrayDeque<String>()
        stack.addLast(root.path)
        while (stack.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val items = (store.list(stack.removeLast()) as? ListResult.Ok)?.items ?: continue
            for (item in items) {
                if (item.isDirectory && !store.isLink(item.path)) {
                    folders++
                    stack.addLast(item.path)
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

    /** Recursive, case-insensitive name search below [root], like [DirectoryLister.search]. */
    fun search(store: FileStore, root: String, query: String, includeHidden: Boolean, limit: Int = 2000): Flow<FileItem> = flow {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return@flow
        val queue = ArrayDeque<String>()
        queue.addLast(root)
        var found = 0
        while (queue.isNotEmpty() && found < limit) {
            currentCoroutineContext().ensureActive()
            val items = (store.list(queue.removeFirst()) as? ListResult.Ok)?.items ?: continue
            for (item in items.sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }) {
                if (!includeHidden && item.isHidden) continue
                if (item.name.lowercase().contains(needle)) {
                    emit(item)
                    if (++found >= limit) break
                }
                if (item.isDirectory && !store.isLink(item.path)) queue.addLast(item.path)
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    internal fun join(dir: String, name: String): String = File(dir, name).path

    private fun DirStats.plus(o: DirStats) = DirStats(files + o.files, folders + o.folders, bytes + o.bytes)

    private fun conflictOf(src: FileItem, existing: FileItem) = Conflict(
        name = existing.name,
        targetPath = existing.path,
        sourceIsDirectory = src.isDirectory,
        targetIsDirectory = existing.isDirectory,
        sourceSize = if (src.isDirectory) 0 else src.size,
        sourceModified = src.lastModified,
        targetSize = if (existing.isDirectory) 0 else existing.size,
        targetModified = existing.lastModified,
    )
}

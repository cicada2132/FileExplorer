package com.offlinefiles.explorer.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

sealed interface ListResult {
    data class Ok(val items: List<FileItem>) : ListResult
    /** The folder exists but the system won't let us read it (e.g. Android/data on Android 11+). */
    data object Denied : ListResult
    data object NotFound : ListResult
}

object DirectoryLister {

    fun list(dir: File): ListResult {
        if (!dir.exists() || !dir.isDirectory) return ListResult.NotFound
        val names = dir.list() ?: return ListResult.Denied
        val items = ArrayList<FileItem>(names.size)
        for (name in names) items += stat(File(dir, name))
        return ListResult.Ok(items)
    }

    /** One stat() call per entry instead of three (isDirectory / length / lastModified). */
    fun stat(f: File): FileItem {
        val name = f.name
        return try {
            val a = Files.readAttributes(f.toPath(), BasicFileAttributes::class.java)
            FileItem(
                path = f.path,
                name = name,
                isDirectory = a.isDirectory,
                size = if (a.isDirectory) 0L else a.size(),
                lastModified = a.lastModifiedTime().toMillis(),
                isHidden = name.startsWith("."),
            )
        } catch (e: Exception) {
            FileItem(f.path, name, f.isDirectory, f.length(), f.lastModified(), name.startsWith("."))
        }
    }

    /** Number of visible children, or null if the folder can't be read. */
    fun childCount(dir: File, includeHidden: Boolean): Int? {
        val names = dir.list() ?: return null
        return if (includeHidden) names.size else names.count { !it.startsWith(".") }
    }

    /**
     * Recursive, case-insensitive name search below [root]. Folders that can't be read are
     * skipped silently. Emits matches as they are found.
     */
    fun search(root: File, query: String, includeHidden: Boolean, limit: Int = 2000): Flow<FileItem> = flow {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return@flow
        val queue = ArrayDeque<File>()
        queue.addLast(root)
        var found = 0
        while (queue.isNotEmpty() && found < limit) {
            currentCoroutineContext().ensureActive()
            val dir = queue.removeFirst()
            val names = dir.list() ?: continue
            names.sortWith(NaturalOrder)
            for (name in names) {
                if (!includeHidden && name.startsWith(".")) continue
                val f = File(dir, name)
                val item = stat(f)
                if (name.lowercase().contains(needle)) {
                    emit(item)
                    if (++found >= limit) break
                }
                if (item.isDirectory && !Files.isSymbolicLink(f.toPath())) queue.addLast(f)
            }
        }
    }
}

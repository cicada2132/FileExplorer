package com.offlinefiles.explorer.core

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files

/** A file that was just created, and the stream that fills it. */
class NewFile(val path: String, val output: OutputStream)

/**
 * Where files live and how to change them.
 *
 * [LocalStore] is the ordinary file system. USB drives that Android only shares through the
 * system file picker are document trees (data/DocumentTree). Both use the same absolute,
 * "/"-separated paths, so screens, selections and [StoreOps] treat them alike.
 */
interface FileStore {
    /** The children of [dir]. */
    fun list(dir: String): ListResult

    /** The file or folder at [path], or null when nothing is there. */
    fun stat(path: String): FileItem?

    fun openInput(path: String): InputStream

    /** Creates the empty file [name] in [dir] (the name must be free) and opens it for writing. */
    fun createFile(dir: String, name: String): NewFile

    /** Creates the folder [name] in [dir] and returns its path. */
    fun createFolder(dir: String, name: String): String

    /** Renames [path] in its folder and returns the new path. Fails if [newName] is taken. */
    fun rename(path: String, newName: String): String

    /** Deletes one file or one empty folder. */
    fun delete(path: String): Boolean

    /** Puts the finished file [temp] in place of [target] (same folder) and returns the final path. */
    fun replace(temp: String, target: String): String

    /** Moves [path] into [destDir] without copying when the store can (same volume); null when not. */
    fun moveInto(path: String, destDir: String): String?

    fun setLastModified(path: String, millis: Long)

    /** Symbolic links are copied like files and never followed into. */
    fun isLink(path: String): Boolean = false

    /** Whether [a] and [b] name the same file. */
    fun isSame(a: String, b: String): Boolean = a == b

    /** True if [ancestor] is [path] itself or one of its parent folders. */
    fun isSameOrAncestor(ancestor: String, path: String): Boolean =
        path == ancestor || path.startsWith(ancestor.trimEnd('/') + "/")
}

/** Finds the store that serves a path. */
fun interface StoreResolver {
    fun storeFor(path: String): FileStore
}

/** The ordinary file system, following the same rules as [FileOps]. */
object LocalStore : FileStore {
    override fun list(dir: String): ListResult = DirectoryLister.list(File(dir))

    override fun stat(path: String): FileItem? {
        val f = File(path)
        return if (f.exists() || isLink(path)) DirectoryLister.stat(f) else null
    }

    override fun openInput(path: String): InputStream = FileInputStream(path)

    override fun createFile(dir: String, name: String): NewFile {
        val f = File(dir, name)
        if (f.exists()) throw FileOpException(FailureReason.ALREADY_EXISTS)
        return NewFile(f.path, FileOutputStream(f))
    }

    override fun createFolder(dir: String, name: String): String {
        val f = File(dir, name)
        if (f.exists()) throw FileOpException(FailureReason.ALREADY_EXISTS)
        if (!f.mkdir()) throw FileOpException(FileOps.accessReason(File(dir)))
        return f.path
    }

    override fun rename(path: String, newName: String): String = FileOps.rename(File(path), newName).path

    override fun delete(path: String): Boolean = File(path).delete()

    override fun replace(temp: String, target: String): String {
        FileOps.replace(File(temp), File(target))
        return target
    }

    override fun moveInto(path: String, destDir: String): String? {
        val src = File(path)
        val target = File(destDir, src.name)
        return if (!target.exists() && src.renameTo(target)) target.path else null
    }

    override fun setLastModified(path: String, millis: Long) {
        File(path).setLastModified(millis)
    }

    override fun isLink(path: String): Boolean = Files.isSymbolicLink(File(path).toPath())

    override fun isSame(a: String, b: String): Boolean = FileOps.isSameFile(File(a), File(b))

    override fun isSameOrAncestor(ancestor: String, path: String): Boolean =
        FileOps.isSameOrAncestor(File(ancestor), File(path))
}

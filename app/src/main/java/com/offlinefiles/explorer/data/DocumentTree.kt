package com.offlinefiles.explorer.data

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.system.Os
import com.offlinefiles.explorer.core.FailureReason
import com.offlinefiles.explorer.core.FileItem
import com.offlinefiles.explorer.core.FileOpException
import com.offlinefiles.explorer.core.FileStore
import com.offlinefiles.explorer.core.ListResult
import com.offlinefiles.explorer.core.NewFile
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CancellationException

/**
 * A drive the user opened to this app in the system file picker (Storage Access Framework).
 * That is how USB drives are reached when Android doesn't let apps use their file paths.
 *
 * Items keep ordinary paths below [rootPath] (where the drive is mounted), so the rest of the app
 * shows, selects and sorts them like local files. The external storage provider names documents
 * "<volume uuid>:<path on the drive>", which maps one-to-one onto those paths.
 */
class DocumentTree(
    val resolver: ContentResolver,
    val rootPath: String,
    val treeUri: Uri,
) : FileStore {
    private val rootId: String = DocumentsContract.getTreeDocumentId(treeUri)

    fun contains(path: String): Boolean = path == rootPath || path.startsWith("$rootPath/")

    // ------------------------------------------------------------------ addresses

    fun documentId(path: String): String {
        val rel = when {
            path == rootPath -> ""
            path.startsWith("$rootPath/") -> path.substring(rootPath.length + 1)
            else -> throw FileNotFoundException(path)
        }
        return when {
            rel.isEmpty() -> rootId
            rootId.endsWith(":") -> rootId + rel
            else -> "$rootId/$rel"
        }
    }

    /** The path of a document from this tree, or null if it lies outside. */
    private fun pathOf(documentId: String): String? = when {
        documentId == rootId -> rootPath
        rootId.endsWith(":") && documentId.startsWith(rootId) -> "$rootPath/${documentId.substring(rootId.length)}"
        documentId.startsWith("$rootId/") -> "$rootPath/${documentId.substring(rootId.length + 1)}"
        else -> null
    }

    fun documentUri(path: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId(path))

    // ------------------------------------------------------------------ reading

    override fun list(dir: String): ListResult {
        val uri = try {
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId(dir))
        } catch (e: FileNotFoundException) {
            return ListResult.NotFound
        }
        val cursor = try {
            resolver.query(uri, COLUMNS, null as Bundle?, null)
        } catch (e: SecurityException) {
            return ListResult.Denied
        } catch (e: Exception) {
            null
        } ?: return if (stat(dir) == null) ListResult.NotFound else ListResult.Denied
        val items = ArrayList<FileItem>(cursor.count.coerceAtLeast(0))
        cursor.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val path = pathOf(id) ?: c.getString(1)?.takeIf { it.isNotEmpty() }?.let { File(dir, it).path } ?: continue
                items += item(c, path)
            }
        }
        return ListResult.Ok(items)
    }

    override fun stat(path: String): FileItem? {
        val uri = try {
            documentUri(path)
        } catch (e: FileNotFoundException) {
            return null
        }
        val cursor = try {
            resolver.query(uri, COLUMNS, null as Bundle?, null)
        } catch (e: Exception) {
            null
        } ?: return null
        return cursor.use { c -> if (c.moveToFirst()) item(c, path) else null }
    }

    private fun item(c: Cursor, path: String): FileItem {
        val isDir = c.getString(2) == Document.MIME_TYPE_DIR
        val name = path.substringAfterLast('/')
        return FileItem(
            path = path,
            name = name,
            isDirectory = isDir,
            size = if (isDir || c.isNull(3)) 0L else c.getLong(3),
            lastModified = if (c.isNull(4)) 0L else c.getLong(4),
            isHidden = name.startsWith("."),
        )
    }

    override fun openInput(path: String): InputStream = io {
        resolver.openInputStream(documentUri(path)) ?: throw FileNotFoundException(path)
    }

    /** For decoders that want a seekable file descriptor (thumbnails, video frames). Null if it can't be opened. */
    fun openFile(path: String): ParcelFileDescriptor? = try {
        resolver.openFileDescriptor(documentUri(path), "r")
    } catch (e: Exception) {
        null
    }

    // ------------------------------------------------------------------ changing

    override fun createFile(dir: String, name: String): NewFile = io {
        // A generic type keeps the provider from adding an extension of its own.
        val uri = DocumentsContract.createDocument(resolver, documentUri(dir), BINARY, name)
            ?: throw FileOpException(FailureReason.IO_ERROR, name)
        val path = pathOf(DocumentsContract.getDocumentId(uri)) ?: File(dir, name).path
        val pfd = try {
            resolver.openFileDescriptor(uri, "w")
        } catch (e: Exception) {
            null
        }
        if (pfd == null) {
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            throw FileOpException(FailureReason.IO_ERROR, name)
        }
        NewFile(path, SyncingOutputStream(pfd))
    }

    override fun createFolder(dir: String, name: String): String = io {
        val uri = DocumentsContract.createDocument(resolver, documentUri(dir), Document.MIME_TYPE_DIR, name)
            ?: throw FileOpException(FailureReason.IO_ERROR, name)
        pathOf(DocumentsContract.getDocumentId(uri)) ?: File(dir, name).path
    }

    override fun rename(path: String, newName: String): String = io {
        val parent = File(path).parent ?: throw FileOpException(FailureReason.ACCESS_DENIED)
        val current = File(path).name
        if (newName == current) return@io path
        // The provider would quietly pick "name (1)" for a taken name; refuse instead.
        val caseOnly = newName.equals(current, ignoreCase = true)
        if (!caseOnly && stat(File(parent, newName).path) != null) throw FileOpException(FailureReason.ALREADY_EXISTS)
        var uri = documentUri(path)
        if (caseOnly) {
            // FAT and exFAT ignore case, so "a.txt" → "A.txt" goes through a temporary name.
            uri = DocumentsContract.renameDocument(resolver, uri, ".rename-${System.nanoTime()}")
                ?: throw FileOpException(FailureReason.IO_ERROR)
        }
        val renamed = try {
            DocumentsContract.renameDocument(resolver, uri, newName)
        } catch (e: RuntimeException) {
            null
        }
        if (renamed == null) {
            if (caseOnly) runCatching { DocumentsContract.renameDocument(resolver, uri, current) }
            throw FileOpException(FailureReason.IO_ERROR)
        }
        pathOf(DocumentsContract.getDocumentId(renamed)) ?: File(parent, newName).path
    }

    override fun delete(path: String): Boolean = try {
        DocumentsContract.deleteDocument(resolver, documentUri(path))
    } catch (e: Exception) {
        // Some providers fail after the work is done (cleaning up grants); what counts is that it's gone.
        stat(path) == null
    }

    override fun replace(temp: String, target: String): String = io {
        val name = File(target).name
        val old = stat(target)
        // Set the old file aside first, so a failed rename can still put it back.
        val backup = if (old == null) null else {
            DocumentsContract.renameDocument(resolver, documentUri(target), ".$name.${System.nanoTime()}.old")
                ?: throw FileOpException(FailureReason.ACCESS_DENIED)
        }
        val renamed = try {
            DocumentsContract.renameDocument(resolver, documentUri(temp), name)
        } catch (e: RuntimeException) {
            null
        }
        if (renamed == null) {
            if (backup != null) runCatching { DocumentsContract.renameDocument(resolver, backup, name) }
            throw FileOpException(FailureReason.IO_ERROR)
        }
        if (backup != null) runCatching { DocumentsContract.deleteDocument(resolver, backup) }
        pathOf(DocumentsContract.getDocumentId(renamed)) ?: target
    }

    override fun moveInto(path: String, destDir: String): String? = try {
        val parent = File(path).parent
        if (parent == null) null else {
            DocumentsContract.moveDocument(resolver, documentUri(path), documentUri(parent), documentUri(destDir))
                ?.let { pathOf(DocumentsContract.getDocumentId(it)) }
        }
    } catch (e: Exception) {
        null
    }

    /** Documents have no way to set a modification time; copies get the time they were written. */
    override fun setLastModified(path: String, millis: Long) = Unit

    /** USB drives use FAT, exFAT or NTFS, which ignore case. */
    override fun isSame(a: String, b: String): Boolean = a.equals(b, ignoreCase = true)

    override fun isSameOrAncestor(ancestor: String, path: String): Boolean =
        path.equals(ancestor, ignoreCase = true) || path.startsWith(ancestor.trimEnd('/') + "/", ignoreCase = true)

    /** Size and free space of the drive, or (0, 0) when the provider won't say. */
    fun capacity(): Pair<Long, Long> = try {
        resolver.openFileDescriptor(documentUri(rootPath), "r")?.use { pfd ->
            val st = Os.fstatvfs(pfd.fileDescriptor)
            (st.f_blocks * st.f_frsize) to (st.f_bavail * st.f_frsize)
        } ?: (0L to 0L)
    } catch (e: Exception) {
        0L to 0L
    } catch (e: LinkageError) {
        0L to 0L
    }

    /** Provider calls throw all sorts of runtime exceptions; the operations expect IOExceptions. */
    private inline fun <T> io(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (e: SecurityException) {
        throw FileOpException(FailureReason.ACCESS_DENIED, e.message)
    } catch (e: RuntimeException) {
        throw FileOpException(FailureReason.IO_ERROR, e.message)
    }

    override fun equals(other: Any?): Boolean =
        other is DocumentTree && other.rootPath == rootPath && other.treeUri == treeUri

    override fun hashCode(): Int = rootPath.hashCode() * 31 + treeUri.hashCode()

    override fun toString(): String = "DocumentTree($rootPath)"

    /** Writes through the provider's file descriptor and flushes it to the drive on close, so a USB stick can be unplugged once a copy is done. */
    private class SyncingOutputStream(pfd: ParcelFileDescriptor) : ParcelFileDescriptor.AutoCloseOutputStream(pfd) {
        private var closed = false

        override fun close() {
            if (closed) return
            closed = true
            try {
                flush()
                fd.sync()
            } catch (e: IOException) {
                // Some providers hand out pipes, which can't be synced; the data was still written.
            } finally {
                super.close()
            }
        }
    }

    companion object {
        private const val BINARY = "application/octet-stream"
        private val COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
    }
}

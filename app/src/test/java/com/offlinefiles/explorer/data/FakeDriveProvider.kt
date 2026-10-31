package com.offlinefiles.explorer.data

import android.Manifest
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import androidx.core.os.BundleCompat
import org.robolectric.Robolectric
import java.io.File
import java.io.FileNotFoundException

/**
 * Stands in for Android's external storage provider in tests: each USB drive is a folder on the
 * host, addressed with the same document ids as on a phone ("<uuid>:<path on the drive>").
 * Like the real provider, a name that is taken becomes "name (1)".
 */
class FakeDriveProvider : DocumentsProvider() {
    companion object {
        /** Volume uuid → the folder that holds the drive's files. */
        val drives = HashMap<String, File>()

        fun install() {
            val info = ProviderInfo().apply {
                authority = StorageVolumes.DOCS_AUTHORITY
                exported = true
                grantUriPermissions = true
                readPermission = Manifest.permission.MANAGE_DOCUMENTS
                writePermission = Manifest.permission.MANAGE_DOCUMENTS
            }
            Robolectric.buildContentProvider(FakeDriveProvider::class.java).create(info)
        }

        /** What the system file picker returns when the user picks the whole drive. */
        fun treeUri(uuid: String, inside: String = ""): Uri =
            DocumentsContract.buildTreeDocumentUri(StorageVolumes.DOCS_AUTHORITY, "$uuid:$inside")
    }

    private val columns = arrayOf(
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE,
        Document.COLUMN_SIZE,
        Document.COLUMN_LAST_MODIFIED,
        Document.COLUMN_FLAGS,
    )

    override fun onCreate() = true

    /**
     * Renames, moves and deletes without the framework's grant clean-up afterwards: that calls
     * the system's URI grant service, which Robolectric doesn't provide.
     */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val uri = extras?.let { BundleCompat.getParcelable(it, "uri", Uri::class.java) }
            ?: return super.call(method, arg, extras)
        val id = DocumentsContract.getDocumentId(uri)
        fun uriOf(newId: String) = DocumentsContract.buildDocumentUriUsingTree(uri, newId)
        val out = Bundle()
        when (method) {
            "android:deleteDocument" -> deleteDocument(id)
            "android:renameDocument" ->
                renameDocument(id, extras.getString(Document.COLUMN_DISPLAY_NAME)!!)?.let { out.putParcelable("uri", uriOf(it)) }
            "android:moveDocument" -> {
                // The other two URIs are the source's folder and the target folder.
                val parent = fileFor(id).parentFile!!
                val target = extras.keySet().filter { it != "uri" }
                    .mapNotNull { BundleCompat.getParcelable(extras, it, Uri::class.java) }
                    .map { DocumentsContract.getDocumentId(it) }
                    .firstOrNull { fileFor(it).path != parent.path }
                    ?: return super.call(method, arg, extras)
                out.putParcelable("uri", uriOf(moveDocument(id, idFor(parent), target)))
            }
            else -> return super.call(method, arg, extras)
        }
        return out
    }

    private fun fileFor(documentId: String): File {
        val root = drives[documentId.substringBefore(':')] ?: throw FileNotFoundException(documentId)
        val rel = documentId.substringAfter(':')
        return if (rel.isEmpty()) root else File(root, rel)
    }

    private fun idFor(file: File): String {
        for ((uuid, root) in drives) {
            if (file.path == root.path) return "$uuid:"
            if (file.path.startsWith(root.path + "/")) return "$uuid:" + file.path.substring(root.path.length + 1)
        }
        throw FileNotFoundException(file.path)
    }

    private fun addRow(c: MatrixCursor, f: File) {
        c.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, idFor(f))
            .add(Document.COLUMN_DISPLAY_NAME, f.name)
            .add(Document.COLUMN_MIME_TYPE, if (f.isDirectory) Document.MIME_TYPE_DIR else "application/octet-stream")
            .add(Document.COLUMN_SIZE, if (f.isDirectory) null else f.length())
            .add(Document.COLUMN_LAST_MODIFIED, f.lastModified())
            .add(Document.COLUMN_FLAGS, Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME or Document.FLAG_SUPPORTS_MOVE)
    }

    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(arrayOf(Root.COLUMN_ROOT_ID))

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val f = fileFor(documentId)
        if (!f.exists()) throw FileNotFoundException(documentId)
        return MatrixCursor(projection ?: columns).also { addRow(it, f) }
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val dir = fileFor(parentDocumentId)
        if (!dir.exists()) throw FileNotFoundException(parentDocumentId)
        val c = MatrixCursor(projection ?: columns)
        dir.listFiles()?.forEach { addRow(c, it) }
        return c
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fileFor(documentId), ParcelFileDescriptor.parseMode(mode))

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = fileFor(parentDocumentId).path
        val child = fileFor(documentId).path
        return child == parent || child.startsWith("$parent/")
    }

    private fun unique(dir: File, name: String): File {
        val first = File(dir, name)
        if (!first.exists()) return first
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (File(dir, "$base ($i)$ext").exists()) i++
        return File(dir, "$base ($i)$ext")
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val f = unique(fileFor(parentDocumentId), displayName)
        val ok = if (mimeType == Document.MIME_TYPE_DIR) f.mkdir() else f.createNewFile()
        check(ok) { "Failed to create $f" }
        return idFor(f)
    }

    override fun renameDocument(documentId: String, displayName: String): String? {
        val before = fileFor(documentId)
        val after = unique(before.parentFile!!, displayName)
        check(before.renameTo(after)) { "Failed to rename to $after" }
        val id = idFor(after)
        return if (id == documentId) null else id
    }

    override fun deleteDocument(documentId: String) {
        val f = fileFor(documentId)
        check(f.deleteRecursively()) { "Failed to delete $f" }
    }

    override fun moveDocument(sourceDocumentId: String, sourceParentDocumentId: String, targetParentDocumentId: String): String {
        val before = fileFor(sourceDocumentId)
        val after = File(fileFor(targetParentDocumentId), before.name)
        check(!after.exists()) { "Already exists $after" }
        check(before.renameTo(after)) { "Failed to move to $after" }
        return idFor(after)
    }
}

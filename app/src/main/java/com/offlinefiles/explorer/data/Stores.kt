package com.offlinefiles.explorer.data

import android.content.ContentResolver
import androidx.core.net.toUri
import com.offlinefiles.explorer.core.FileStore
import com.offlinefiles.explorer.core.LocalStore
import com.offlinefiles.explorer.core.StoreResolver
import java.io.File

/**
 * Which [FileStore] serves a path: a USB drive opened through the system file picker, or the
 * local file system. Updated whenever the list of storage volumes is read.
 */
object Stores : StoreResolver {
    @Volatile
    private var trees: List<DocumentTree> = emptyList()

    /** Registers the drives in [volumes] that are reached through the file picker. */
    fun update(resolver: ContentResolver, volumes: List<VolumeInfo>) {
        val current = trees
        trees = volumes.mapNotNull { v ->
            val uri = v.treeUri?.takeIf { v.access == VolumeAccess.DOCUMENTS } ?: return@mapNotNull null
            current.firstOrNull { it.rootPath == v.path && it.treeUri.toString() == uri }
                ?: DocumentTree(resolver, v.path, uri.toUri())
        }
    }

    fun tree(path: String): DocumentTree? {
        val list = trees
        return if (list.isEmpty()) null else list.firstOrNull { it.contains(path) }
    }

    fun isDocument(path: String): Boolean = tree(path) != null

    /** Like File.exists() for local paths. */
    fun exists(path: String): Boolean = tree(path)?.let { it.stat(path) != null } ?: File(path).exists()

    override fun storeFor(path: String): FileStore = tree(path) ?: LocalStore
}

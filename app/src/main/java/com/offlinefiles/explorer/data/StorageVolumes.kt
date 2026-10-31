package com.offlinefiles.explorer.data

import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import com.offlinefiles.explorer.R
import java.io.File

enum class VolumeKind { INTERNAL, SD_CARD, USB }

/** How the app reaches a volume's files. */
enum class VolumeAccess {
    /** Through ordinary file paths. */
    PATH,

    /** Through the drive the user opened to the app in the system file picker. */
    DOCUMENTS,

    /** Mounted, but Android only shares it with apps the user allows in the system file picker. */
    NEEDS_GRANT,
}

data class VolumeInfo(
    val path: String,
    val label: String,
    val kind: VolumeKind,
    val totalBytes: Long,
    val freeBytes: Long,
    val access: VolumeAccess = VolumeAccess.PATH,
    /** The drive's file-system id ("1A2B-3C4D"); the system file picker knows the drive by it. */
    val uuid: String? = null,
    /** Files can be read but not changed (removable drives on Android 8–10, or a read-only mount). */
    val readOnly: Boolean = false,
    /** Opening the drive in the system file picker would give this app (more) access to it. */
    val canGrant: Boolean = false,
    /** For [VolumeAccess.DOCUMENTS]: the tree URI the user granted. */
    val treeUri: String? = null,
) {
    val usedBytes: Long get() = (totalBytes - freeBytes).coerceAtLeast(0)
    val usedFraction: Float get() = if (totalBytes > 0) (usedBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f) else 0f
}

/** What came of the user's pick in the system file picker. */
sealed interface GrantResult {
    data class Granted(val uuid: String) : GrantResult

    /** A folder inside the drive was picked instead of the drive itself. */
    data object NotRoot : GrantResult

    /** Not one of the drives that needed access. */
    data object NotADrive : GrantResult

    data object Failed : GrantResult
}

/**
 * Finds internal storage, SD cards and USB drives that are mounted, and how each can be reached:
 * by path, or through the system file picker for drives Android keeps away from apps.
 */
object StorageVolumes {
    /** The system's provider for internal storage, SD cards and USB drives. */
    const val DOCS_AUTHORITY = "com.android.externalstorage.documents"

    /** Where Android mounts drives it doesn't show to apps. Paths below it are only used as names. */
    private const val HIDDEN_ROOT = "/mnt/media_rw"

    fun list(context: Context): List<VolumeInfo> {
        val sm = context.getSystemService(StorageManager::class.java)
        val result = LinkedHashMap<String, VolumeInfo>()
        val primary = Environment.getExternalStorageDirectory()
        val grants = grantedTrees(context)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && sm != null) {
            for (v in sm.storageVolumes) {
                if (!isMounted(v)) continue
                describe(context, v, v.directory, grants)?.let { result[it.path] = it }
            }
        } else {
            // Android 8–10: app-specific dirs reveal the roots of the volumes apps can see.
            val seen = HashSet<String>()
            for (appDir in context.getExternalFilesDirs(null)) {
                if (appDir == null) continue
                val root = appDir.path.substringBefore("/Android/data/", "")
                if (root.isEmpty()) continue
                val dir = File(root)
                val v = sm?.getStorageVolume(dir)
                v?.uuid?.let { seen += it }
                describe(context, v, dir, grants)?.let { result[it.path] = it }
            }
            // Drives Android hides from apps (most USB drives) are only reachable through the file picker.
            sm?.storageVolumes?.forEach { v ->
                val uuid = v.uuid
                if (v.isRemovable && isMounted(v) && uuid != null && uuid !in seen) {
                    describe(context, v, null, grants)?.let { result[it.path] = it }
                }
            }
        }
        if (result.isEmpty() || result.values.none { it.kind == VolumeKind.INTERNAL }) {
            describe(context, null, primary, grants)?.let { result[primary.path] = it }
        }
        return result.values.sortedBy { it.kind.ordinal }
    }

    private fun isMounted(v: StorageVolume): Boolean =
        v.state == Environment.MEDIA_MOUNTED || v.state == Environment.MEDIA_MOUNTED_READ_ONLY

    private fun describe(context: Context, v: StorageVolume?, dir: File?, grants: Map<String, Uri>): VolumeInfo? {
        val isPrimary = v?.isPrimary ?: (dir?.path == Environment.getExternalStorageDirectory().path)
        if (isPrimary && !(v?.isRemovable ?: false)) {
            val d = dir ?: Environment.getExternalStorageDirectory()
            val (total, free) = capacity(context, d, VolumeKind.INTERNAL)
            return VolumeInfo(d.path, context.getString(R.string.storage_internal), VolumeKind.INTERNAL, total, free)
        }
        val uuid = v?.uuid
        val readable = dir != null && dir.list() != null
        val path = dir?.path ?: uuid?.let { "$HIDDEN_ROOT/$it" } ?: return null
        val description = runCatching { v?.getDescription(context) }.getOrNull().orEmpty()
        // Stock Android shows SD cards to apps and keeps USB drives behind the file picker.
        val kind = if (description.contains("USB", ignoreCase = true) || !readable) VolumeKind.USB else VolumeKind.SD_CARD
        val label = description.ifBlank {
            context.getString(if (kind == VolumeKind.USB) R.string.storage_usb else R.string.storage_sd)
        }
        val mountedReadOnly = v?.state == Environment.MEDIA_MOUNTED_READ_ONLY
        val tree = uuid?.let { grants[it] }
        if (readable) {
            val writable = !mountedReadOnly && dir!!.canWrite()
            if (writable || tree == null) {
                val (total, free) = capacity(context, dir!!, kind)
                return VolumeInfo(
                    path, label, kind, total, free, VolumeAccess.PATH, uuid,
                    readOnly = !writable,
                    canGrant = !writable && !mountedReadOnly && uuid != null,
                )
            }
        }
        if (uuid == null) return null
        if (tree == null) {
            return VolumeInfo(path, label, kind, 0, 0, VolumeAccess.NEEDS_GRANT, uuid, readOnly = mountedReadOnly, canGrant = true)
        }
        val (total, free) = if (readable) capacity(context, dir!!, kind)
        else DocumentTree(context.contentResolver, path, tree).capacity()
        return VolumeInfo(path, label, kind, total, free, VolumeAccess.DOCUMENTS, uuid, readOnly = mountedReadOnly, treeUri = tree.toString())
    }

    private fun capacity(context: Context, dir: File, kind: VolumeKind): Pair<Long, Long> {
        if (kind == VolumeKind.INTERNAL) {
            // Matches the numbers shown in system Settings (whole device, not just /data).
            runCatching {
                val ssm = context.getSystemService(StorageStatsManager::class.java)
                val total = ssm.getTotalBytes(StorageManager.UUID_DEFAULT)
                val free = ssm.getFreeBytes(StorageManager.UUID_DEFAULT)
                if (total > 0) return total to free
            }
        }
        return runCatching {
            val st = StatFs(dir.path)
            st.totalBytes to st.availableBytes
        }.getOrDefault(0L to 0L)
    }

    /** The volume that contains [path] (longest matching root), if any. */
    fun volumeFor(path: String, volumes: List<VolumeInfo>): VolumeInfo? =
        volumes.filter { path == it.path || path.startsWith(it.path + "/") }.maxByOrNull { it.path.length }

    // ------------------------------------------------------------------ system file picker

    /** Drive roots the user opened to the app in the system file picker, by volume uuid. */
    fun grantedTrees(context: Context): Map<String, Uri> {
        val out = HashMap<String, Uri>()
        val permissions = runCatching { context.contentResolver.persistedUriPermissions }.getOrNull().orEmpty()
        for (p in permissions) {
            if (!p.isReadPermission || !p.isWritePermission) continue
            val uuid = driveRootOf(p.uri) ?: continue
            out[uuid] = p.uri
        }
        return out
    }

    /** The volume uuid if [uri] is the whole of a drive in the external storage provider. */
    private fun driveRootOf(uri: Uri): String? {
        if (uri.authority != DOCS_AUTHORITY || !runCatching { DocumentsContract.isTreeUri(uri) }.getOrDefault(false)) return null
        val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
        val uuid = id.substringBefore(':', "")
        return if (uuid.isNotEmpty() && id == "$uuid:") uuid else null
    }

    /** Opens the system file picker at the root of [volume], so the user only taps "Use this folder". */
    fun accessIntent(context: Context, volume: VolumeInfo): Intent {
        val uuid = volume.uuid
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(StorageManager::class.java)?.storageVolumes
                ?.firstOrNull { it.uuid == uuid }?.createOpenDocumentTreeIntent()
        } else {
            null
        } ?: Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(DOCS_AUTHORITY, "$uuid:"))
            .putExtra("android.content.extra.SHOW_ADVANCED", true)
        return intent.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
        )
    }

    /** Keeps the access the user gave in the file picker, if [uri] is a drive that asked for it. */
    fun acceptGrant(context: Context, uri: Uri, volumes: List<VolumeInfo>): GrantResult {
        if (uri.authority != DOCS_AUTHORITY || !runCatching { DocumentsContract.isTreeUri(uri) }.getOrDefault(false)) {
            return GrantResult.NotADrive
        }
        val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return GrantResult.NotADrive
        val uuid = id.substringBefore(':', "")
        if (volumes.none { it.uuid == uuid && it.canGrant }) return GrantResult.NotADrive
        if (driveRootOf(uri) == null) return GrantResult.NotRoot
        return try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            GrantResult.Granted(uuid)
        } catch (e: SecurityException) {
            GrantResult.Failed
        }
    }

    /** Gives back access to drives the user opened in the file picker. */
    fun releaseGrants(context: Context) {
        val resolver = context.contentResolver
        for (uri in grantedTrees(context).values) {
            runCatching {
                resolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
    }
}

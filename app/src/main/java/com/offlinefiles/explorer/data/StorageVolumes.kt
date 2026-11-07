package com.offlinefiles.explorer.data

import android.app.usage.StorageStatsManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import com.offlinefiles.explorer.R
import java.io.File
import java.util.Locale

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

    /** Access was given, but the drive's files still couldn't be listed. */
    data object Unreadable : GrantResult

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
        val grants = withOrphanGrant(grants(context), sm)

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

    /**
     * A drive root the user allowed whose id matches no mounted volume, while exactly one removable
     * volume has no access yet: they are the same drive (the picker spelled its id differently).
     */
    private fun withOrphanGrant(grants: Map<String, Grant>, sm: StorageManager?): Map<String, Grant> {
        val removable = runCatching { sm?.storageVolumes.orEmpty() }.getOrDefault(emptyList())
            .filter { it.isRemovable && isMounted(it) }.mapNotNull { it.uuid?.let(::key) }.toSet()
        val orphans = grants.filterKeys { it !in removable }
        val waiting = removable.filter { it !in grants }
        if (orphans.size != 1 || waiting.size != 1) return grants
        return grants + (waiting.single() to orphans.values.single())
    }

    private fun isMounted(v: StorageVolume): Boolean =
        v.state == Environment.MEDIA_MOUNTED || v.state == Environment.MEDIA_MOUNTED_READ_ONLY

    private fun describe(context: Context, v: StorageVolume?, dir: File?, grants: Map<String, Grant>): VolumeInfo? {
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
        val grant = uuid?.let { grants[key(it)] }
        val tree = grant?.uri
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
        if (grant == null || tree == null) {
            return VolumeInfo(path, label, kind, 0, 0, VolumeAccess.NEEDS_GRANT, uuid, readOnly = mountedReadOnly, canGrant = true)
        }
        val (total, free) = if (readable) capacity(context, dir!!, kind)
        else DocumentTree(context.contentResolver, path, tree).capacity()
        val writable = grant.writable && !mountedReadOnly
        return VolumeInfo(
            path, label, kind, total, free, VolumeAccess.DOCUMENTS, uuid,
            readOnly = !writable,
            treeUri = tree.toString(),
        )
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

    /** A drive root the user opened to the app, and whether they let it change files there. */
    private data class Grant(val uri: Uri, val writable: Boolean)

    private const val GRANT_PREFS = "drive_access"
    private const val KEY_REQUESTED = "requested"
    private const val VOLUME_PREFIX = "volume:"

    private fun grantPrefs(context: Context) = context.getSharedPreferences(GRANT_PREFS, Context.MODE_PRIVATE)

    /** Volume uuids are compared without case: the picker and the volume list may spell them differently. */
    private fun key(uuid: String) = uuid.uppercase(Locale.ROOT)

    /** Drive roots the user opened to the app in the system file picker, by volume uuid. */
    fun grantedTrees(context: Context): Map<String, Uri> = grants(context).mapValues { it.value.uri }

    private fun grants(context: Context): Map<String, Grant> {
        val held = HashMap<String, Grant>()
        val permissions = runCatching { context.contentResolver.persistedUriPermissions }.getOrNull().orEmpty()
        for (p in permissions) {
            if (!p.isReadPermission || driveRootOf(p.uri) == null) continue
            held[p.uri.toString()] = Grant(p.uri, p.isWritePermission)
        }
        val out = HashMap<String, Grant>()
        for (g in held.values) out[key(driveRootOf(g.uri)!!)] = g
        // Drives whose picker id didn't match the volume's uuid: the volume they were picked for.
        for ((k, v) in grantPrefs(context).all) {
            if (!k.startsWith(VOLUME_PREFIX)) continue
            val g = held[v as? String ?: continue] ?: continue
            out[key(k.removePrefix(VOLUME_PREFIX))] = g
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

    /**
     * Notes which drive the picker is opened for. Kept on disk: Android may close the app while
     * the picker is showing, and the answer then arrives in a new process.
     */
    fun rememberRequest(context: Context, volume: VolumeInfo) {
        grantPrefs(context).edit().putString(KEY_REQUESTED, volume.uuid).apply()
    }

    /** The drive the picker was last opened for (once). */
    fun takeRequest(context: Context): String? {
        val sp = grantPrefs(context)
        return sp.getString(KEY_REQUESTED, null)?.also { sp.edit().remove(KEY_REQUESTED).apply() }
    }

    /**
     * Keeps the access the user gave in the file picker, if [uri] is a removable drive, and checks
     * that its files can really be read. [requested] is the drive the picker was opened for: a
     * drive root the volume list doesn't know by that id is taken to be that drive.
     */
    fun acceptGrant(context: Context, uri: Uri, volumes: List<VolumeInfo>, requested: String? = null): GrantResult {
        if (uri.authority != DOCS_AUTHORITY || !runCatching { DocumentsContract.isTreeUri(uri) }.getOrDefault(false)) {
            return GrantResult.NotADrive
        }
        val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return GrantResult.NotADrive
        val pickedId = id.substringBefore(':', "")
        if (pickedId.isEmpty()) return GrantResult.NotADrive
        val drives = volumes.filter { it.kind != VolumeKind.INTERNAL && it.uuid != null }
        val volume = drives.firstOrNull { key(it.uuid!!) == key(pickedId) }
            ?: drives.firstOrNull { it.uuid == requested }?.takeIf { pickedId !in INTERNAL_ROOTS }
        if (volume == null) {
            UsbDiagnostics.log(context, "picked id '$pickedId' matches no drive (drives=${drives.map { it.uuid }}, requested=$requested)")
            return GrantResult.NotADrive
        }
        if (driveRootOf(uri) == null) {
            UsbDiagnostics.log(context, "picked a folder inside the drive: $id")
            return GrantResult.NotRoot
        }

        val resolver = context.contentResolver
        val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val write = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        // Read-only drives may come back without write access; reading alone is still worth keeping.
        val taken = listOf(read or write, read).firstOrNull { flags ->
            runCatching { resolver.takePersistableUriPermission(uri, flags) }
                .onFailure { UsbDiagnostics.log(context, "take flags=$flags failed: ${it.javaClass.simpleName}: ${it.message}") }
                .isSuccess
        } ?: return GrantResult.Failed
        val listError = listError(resolver, uri, id)
        if (listError != null) {
            UsbDiagnostics.log(context, "granted (flags=$taken) but listing failed: $listError")
            runCatching { resolver.releasePersistableUriPermission(uri, taken) }
            return GrantResult.Unreadable
        }
        UsbDiagnostics.log(context, "granted ${volume.uuid} via $uri (flags=$taken)")
        val uuid = volume.uuid!!
        val sp = grantPrefs(context).edit()
        if (key(uuid) == key(pickedId)) sp.remove(VOLUME_PREFIX + uuid) else sp.putString(VOLUME_PREFIX + uuid, uri.toString())
        sp.apply()
        return GrantResult.Granted(uuid)
    }

    /** Ids the external storage provider uses for the phone's own storage, never a removable drive. */
    private val INTERNAL_ROOTS = setOf("primary", "home")

    /** Why the drive's top folder can't be listed, or null if it can. */
    private fun listError(resolver: ContentResolver, tree: Uri, rootId: String): String? = try {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId)
        val cursor = resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null as Bundle?, null)
        cursor?.close()
        if (cursor == null) "provider returned nothing" else null
    } catch (e: Exception) {
        "${e.javaClass.simpleName}: ${e.message}"
    }

    /** Gives back access to drives the user opened in the file picker. */
    fun releaseGrants(context: Context) {
        val resolver = context.contentResolver
        for (g in grants(context).values) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or (if (g.writable) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
            runCatching { resolver.releasePersistableUriPermission(g.uri, flags) }
        }
        grantPrefs(context).edit().clear().apply()
    }
}

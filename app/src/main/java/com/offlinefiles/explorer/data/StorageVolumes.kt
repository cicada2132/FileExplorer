package com.offlinefiles.explorer.data

import android.app.usage.StorageStatsManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import com.offlinefiles.explorer.R
import java.io.File

enum class VolumeKind { INTERNAL, SD_CARD, USB }

data class VolumeInfo(
    val path: String,
    val label: String,
    val kind: VolumeKind,
    val totalBytes: Long,
    val freeBytes: Long,
) {
    val usedBytes: Long get() = (totalBytes - freeBytes).coerceAtLeast(0)
    val usedFraction: Float get() = if (totalBytes > 0) (usedBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f) else 0f
}

/** Finds internal storage, SD cards and USB drives that are mounted and reachable by path. */
object StorageVolumes {

    fun list(context: Context): List<VolumeInfo> {
        val sm = context.getSystemService(StorageManager::class.java)
        val result = LinkedHashMap<String, VolumeInfo>()
        val primary = Environment.getExternalStorageDirectory()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && sm != null) {
            for (v in sm.storageVolumes) {
                val state = v.state
                if (state != Environment.MEDIA_MOUNTED && state != Environment.MEDIA_MOUNTED_READ_ONLY) continue
                val dir = v.directory ?: continue
                result[dir.path] = describe(context, dir, v)
            }
        } else {
            // Android 8–10: app-specific dirs reveal every mounted volume's root.
            for (appDir in context.getExternalFilesDirs(null)) {
                if (appDir == null) continue
                val root = appDir.path.substringBefore("/Android/data/", "")
                if (root.isEmpty()) continue
                val dir = File(root)
                val v = sm?.getStorageVolume(dir)
                result[dir.path] = describe(context, dir, v)
            }
        }
        if (result.isEmpty() || result.values.none { it.kind == VolumeKind.INTERNAL }) {
            result[primary.path] = describe(context, primary, null)
        }
        return result.values.sortedBy { it.kind.ordinal }
    }

    private fun describe(context: Context, dir: File, v: StorageVolume?): VolumeInfo {
        val isPrimary = v?.isPrimary ?: (dir.path == Environment.getExternalStorageDirectory().path)
        val description = runCatching { v?.getDescription(context) }.getOrNull().orEmpty()
        val kind = when {
            isPrimary && !(v?.isRemovable ?: false) -> VolumeKind.INTERNAL
            description.contains("USB", ignoreCase = true) -> VolumeKind.USB
            else -> VolumeKind.SD_CARD
        }
        val label = when (kind) {
            VolumeKind.INTERNAL -> context.getString(R.string.storage_internal)
            else -> description.ifBlank {
                context.getString(if (kind == VolumeKind.USB) R.string.storage_usb else R.string.storage_sd)
            }
        }
        val (total, free) = capacity(context, dir, kind)
        return VolumeInfo(dir.path, label, kind, total, free)
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
}

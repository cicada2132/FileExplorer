package com.offlinefiles.explorer.data

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.offlinefiles.explorer.core.gallery.MediaDates
import com.offlinefiles.explorer.core.gallery.MediaItem
import com.offlinefiles.explorer.core.gallery.MediaScan
import java.io.File

/**
 * Reads the device's photos and videos from MediaStore (fast, covers SD cards and USB drives,
 * already skips .nomedia folders). Everything stays on the device; nothing is uploaded.
 */
object MediaLibrary {
    private val filesUri: Uri = MediaStore.Files.getContentUri("external")

    // Plain column names: they exist on every supported Android version, while some of the
    // constants were only added to the SDK in Android 10.
    private val projection = arrayOf(
        "_id",
        "_data",
        "_display_name",
        "mime_type",
        "media_type",
        "_size",
        "date_modified",
        "datetaken",
        "width",
        "height",
        "orientation",
        "duration",
    )

    /** All photos and videos, or a folder scan when MediaStore can't be asked. */
    fun load(context: Context): List<MediaItem> = query(context) ?: scanFolders(context)

    /** Slow fallback: walk every storage volume like the media scanner would. */
    fun scanFolders(context: Context): List<MediaItem> =
        MediaScan.scan(StorageVolumes.list(context).filter { it.access == VolumeAccess.PATH }.map { File(it.path) })

    /** Null when the media provider isn't available (or refuses); an empty list means "no media". */
    fun query(context: Context): List<MediaItem>? {
        val selection = "media_type IN (${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}, ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"
        val cursor = try {
            context.contentResolver.query(filesUri, projection, selection, null, null)
        } catch (e: Exception) {
            null
        } ?: return null
        val now = System.currentTimeMillis()
        return cursor.use { c -> read(c, now) }
    }

    private fun read(c: Cursor, now: Long): List<MediaItem> {
        val iId = c.getColumnIndex("_id")
        val iData = c.getColumnIndex("_data")
        val iName = c.getColumnIndex("_display_name")
        val iMime = c.getColumnIndex("mime_type")
        val iType = c.getColumnIndex("media_type")
        val iSize = c.getColumnIndex("_size")
        val iModified = c.getColumnIndex("date_modified")
        val iTaken = c.getColumnIndex("datetaken")
        val iWidth = c.getColumnIndex("width")
        val iHeight = c.getColumnIndex("height")
        val iOrientation = c.getColumnIndex("orientation")
        val iDuration = c.getColumnIndex("duration")
        val out = ArrayList<MediaItem>(c.count.coerceAtLeast(0))
        while (c.moveToNext()) {
            val path = c.str(iData)
            if (path.isNullOrBlank() || !path.startsWith("/")) continue
            val size = c.long(iSize)
            if (size <= 0) continue
            val video = c.long(iType) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toLong()
            val modified = c.long(iModified) * 1000L
            val rotated = c.long(iOrientation).let { it == 90L || it == 270L }
            val w = c.long(iWidth).toInt()
            val h = c.long(iHeight).toInt()
            out.add(
                MediaItem(
                    id = c.long(iId),
                    path = path,
                    name = c.str(iName)?.takeIf { it.isNotBlank() } ?: path.substringAfterLast('/'),
                    isVideo = video,
                    mime = c.str(iMime).orEmpty(),
                    size = size,
                    dateModified = modified,
                    date = MediaDates.choose(c.long(iTaken), modified, now),
                    width = if (rotated) h else w,
                    height = if (rotated) w else h,
                    durationMs = if (video) c.long(iDuration) else 0L,
                ),
            )
        }
        return out
    }

    private fun Cursor.long(i: Int): Long = if (i < 0 || isNull(i)) 0L else getLong(i)

    private fun Cursor.str(i: Int): String? = if (i < 0 || isNull(i)) null else getString(i)

    /** content:// address of a MediaStore item (used for the system's cached thumbnails). */
    fun contentUri(item: MediaItem): Uri? {
        if (item.id < 0) return null
        val base = if (item.isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        return ContentUris.withAppendedId(base, item.id)
    }

    /**
     * Cheap fingerprint of the media database (Android 11+), so coming back to the app can skip
     * a reload when nothing changed. Null means "unknown, reload".
     */
    fun stamp(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            MediaStore.getExternalVolumeNames(context).sorted().joinToString("|") { v ->
                "$v:${MediaStore.getVersion(context, v)}:${MediaStore.getGeneration(context, v)}"
            }
        } catch (e: Exception) {
            null
        }
    }
}

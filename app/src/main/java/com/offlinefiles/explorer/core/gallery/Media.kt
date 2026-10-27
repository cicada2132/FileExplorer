package com.offlinefiles.explorer.core.gallery

import com.offlinefiles.explorer.core.DirectoryLister
import com.offlinefiles.explorer.core.FileItem
import com.offlinefiles.explorer.core.FileKind
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.zone.ZoneRules
import java.util.Locale

/**
 * One photo or video. [date] is when it was taken if that is known and believable,
 * otherwise when the file was last modified. All times are epoch milliseconds.
 */
data class MediaItem(
    /** MediaStore row id, or -1 when the item was found by scanning folders. */
    val id: Long,
    val path: String,
    val name: String,
    val isVideo: Boolean,
    /** MIME type reported by MediaStore; empty when unknown. */
    val mime: String,
    val size: Long,
    val dateModified: Long,
    val date: Long,
    /** Size as displayed (already swapped for photos rotated by 90° or 270°); 0 when unknown. */
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long = 0,
) {
    val folder: String get() = path.substringBeforeLast('/', "")
}

/** Photos and videos in a folder from the file list, so the viewer can swipe through them. */
fun FileItem.toMediaItem(): MediaItem? {
    val video = when (kind) {
        FileKind.VIDEO -> true
        FileKind.IMAGE -> false
        else -> return null
    }
    return MediaItem(-1, path, name, video, "", size, lastModified, lastModified)
}

enum class AlbumType { CAMERA, VIDEOS, FOLDER }

data class Album(
    val key: String,
    val type: AlbumType,
    /** Folder name; empty for the virtual videos album (the UI supplies its label). */
    val name: String,
    /** The folder on disk, or null for virtual albums. */
    val path: String?,
    val count: Int,
    val videoCount: Int,
    val cover: MediaItem,
    val latest: Long,
)

/** Items that share a calendar day, newest day first. */
data class DaySection(val epochDay: Long, val items: List<MediaItem>)

/**
 * Everything the gallery screens need, built once per load. A plain class on purpose:
 * state flows compare it by identity instead of walking tens of thousands of items.
 */
class GalleryIndex(
    /** Every item, newest first. */
    val items: List<MediaItem>,
    /** Camera folders, then all videos, then the other folders by their newest item. */
    val albums: List<Album>,
    private val byKey: Map<String, List<MediaItem>>,
) {
    val videoCount: Int = items.count { it.isVideo }
    val photoCount: Int = items.size - videoCount

    fun itemsOf(key: String): List<MediaItem> = if (key == ALL) items else byKey[key].orEmpty()

    fun album(key: String): Album? = albums.firstOrNull { it.key == key }

    companion object {
        /** Key of the "all photos" timeline. Folder albums use their path (always starting with "/"). */
        const val ALL = "*all"
        const val VIDEOS = "*videos"

        val EMPTY = GalleryIndex(emptyList(), emptyList(), emptyMap())

        /** Newest first; the path breaks ties so the order is the same on every load. */
        val NEWEST_FIRST: Comparator<MediaItem> = compareByDescending<MediaItem> { it.date }.thenBy { it.path }

        fun build(input: Collection<MediaItem>): GalleryIndex {
            // MediaStore can hold two rows for one file (e.g. after an SD card remount); grid keys must be unique.
            val items = input.sortedWith(NEWEST_FIRST).distinctBy { it.path }
            // Items arrive newest first, so folders are inserted in order of their newest item.
            val folders = LinkedHashMap<String, MutableList<MediaItem>>()
            val videos = ArrayList<MediaItem>()
            for (m in items) {
                folders.getOrPut(m.folder) { ArrayList() }.add(m)
                if (m.isVideo) videos.add(m)
            }
            val folderAlbums = folders.map { (path, list) ->
                Album(
                    key = path,
                    type = if (isCameraFolder(path)) AlbumType.CAMERA else AlbumType.FOLDER,
                    name = path.substringAfterLast('/'),
                    path = path,
                    count = list.size,
                    videoCount = list.count { it.isVideo },
                    cover = list.first(),
                    latest = list.first().date,
                )
            }
            val videoAlbum = videos.firstOrNull()?.let {
                Album(VIDEOS, AlbumType.VIDEOS, "", null, videos.size, videos.size, it, it.date)
            }
            val albums = folderAlbums.filter { it.type == AlbumType.CAMERA } +
                listOfNotNull(videoAlbum) +
                folderAlbums.filter { it.type != AlbumType.CAMERA }
            val byKey = HashMap<String, List<MediaItem>>(folders.size * 2 + 2)
            for ((k, v) in folders) byKey[k] = v
            if (videoAlbum != null) byKey[VIDEOS] = videos
            return GalleryIndex(items, albums, byKey)
        }

        /** DCIM/Camera on any storage volume. */
        fun isCameraFolder(path: String): Boolean = path.endsWith("/DCIM/Camera", ignoreCase = true)

        /** Splits [items] (newest first) into calendar days in the given time zone. */
        fun sections(items: List<MediaItem>, rules: ZoneRules): List<DaySection> {
            if (items.isEmpty()) return emptyList()
            val out = ArrayList<DaySection>()
            var day = epochDay(items[0].date, rules)
            var start = 0
            for (i in 1 until items.size) {
                val d = epochDay(items[i].date, rules)
                if (d != day) {
                    out.add(DaySection(day, items.subList(start, i)))
                    day = d
                    start = i
                }
            }
            out.add(DaySection(day, items.subList(start, items.size)))
            return out
        }

        fun epochDay(millis: Long, rules: ZoneRules): Long {
            val offset = rules.getOffset(Instant.ofEpochMilli(millis)).totalSeconds
            return Math.floorDiv(Math.floorDiv(millis, 1000L) + offset, 86_400L)
        }
    }
}

object MediaDates {
    /** 1990-01-01, anything earlier is a broken clock or a value in seconds. */
    const val MIN_PLAUSIBLE = 631_152_000_000L
    private const val DAY = 86_400_000L

    /** MediaStore sometimes reports 0, seconds instead of milliseconds, or dates in the future. */
    fun choose(taken: Long, modified: Long, now: Long): Long =
        if (taken >= MIN_PLAUSIBLE && taken <= now + 2 * DAY) taken else modified

    /** "0:07", "12:05", "1:02:03". */
    fun duration(ms: Long): String {
        val total = (ms.coerceAtLeast(0) + 500) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.ROOT, "%d:%02d", m, s)
    }
}

/**
 * Finds photos and videos by walking folders, the way the system media scanner does:
 * hidden folders and folders holding a .nomedia file are left out. Used only when
 * MediaStore can't be asked (it is far faster on real devices).
 */
object MediaScan {
    /** Image types BitmapFactory/ImageDecoder can't draw. */
    private val notDrawable = setOf("svg")

    fun scan(roots: List<File>, limit: Int = 20_000): List<MediaItem> {
        val out = ArrayList<MediaItem>()
        val queue = ArrayDeque<File>()
        val seen = HashSet<String>()
        for (r in roots) if (seen.add(r.path)) queue.addLast(r)
        while (queue.isNotEmpty() && out.size < limit) {
            val dir = queue.removeFirst()
            val names = dir.list() ?: continue
            if (".nomedia" in names) continue
            names.sort()
            for (name in names) {
                if (name.startsWith(".")) continue
                val f = File(dir, name)
                val item = DirectoryLister.stat(f)
                if (item.isDirectory) {
                    if (Files.isSymbolicLink(f.toPath()) || isAppPrivate(f)) continue
                    if (seen.add(f.path)) queue.addLast(f)
                    continue
                }
                val video = when {
                    item.kind == FileKind.VIDEO -> true
                    item.kind == FileKind.IMAGE && item.extension !in notDrawable -> false
                    else -> continue
                }
                if (item.size <= 0) continue
                out.add(MediaItem(-1, f.path, name, video, "", item.size, item.lastModified, item.lastModified))
                if (out.size >= limit) break
            }
        }
        return out
    }

    /** Android/data and Android/obb belong to other apps (and can't be read on Android 11+). */
    private fun isAppPrivate(dir: File): Boolean =
        dir.parentFile?.name == "Android" && (dir.name == "data" || dir.name == "obb")
}

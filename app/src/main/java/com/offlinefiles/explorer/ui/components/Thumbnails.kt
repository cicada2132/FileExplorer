package com.offlinefiles.explorer.ui.components

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import com.offlinefiles.explorer.core.DirectoryLister
import com.offlinefiles.explorer.core.FileItem
import com.offlinefiles.explorer.core.FileKind
import com.offlinefiles.explorer.core.ListResult
import com.offlinefiles.explorer.core.gallery.MediaItem
import com.offlinefiles.explorer.data.MediaLibrary
import com.offlinefiles.explorer.data.Stores
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlin.math.roundToInt

/** Small in-memory thumbnail cache for images, videos, audio covers and APK icons. */
object Thumbnails {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val decodeDispatcher = Dispatchers.IO.limitedParallelism(3)

    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).coerceAtMost(96L shl 20).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val misses = ConcurrentHashMap.newKeySet<String>()

    fun supports(item: FileItem): Boolean =
        item.kind == FileKind.IMAGE || item.kind == FileKind.VIDEO || item.kind == FileKind.AUDIO ||
            (item.kind == FileKind.APK && item.extension == "apk")

    private fun key(item: FileItem, px: Int) = "${item.path}|${item.lastModified}|${item.size}|$px"

    fun peek(item: FileItem, px: Int): Bitmap? = cache.get(key(item, px))

    suspend fun load(context: Context, item: FileItem, px: Int): Bitmap? {
        if (!supports(item)) return null
        val k = key(item, px)
        cache.get(k)?.let { return it }
        if (k in misses) return null
        return withContext(decodeDispatcher) {
            cache.get(k) ?: runCatching { decode(context, item, px) }.getOrNull().also {
                if (it != null) cache.put(k, it) else misses += k
            }
        }
    }

    private fun decode(context: Context, item: FileItem, px: Int): Bitmap? {
        Stores.tree(item.path)?.let { tree ->
            return when (item.kind) {
                FileKind.IMAGE -> DocumentMedia.image(tree, item.path, px, fitLongSide = false)
                FileKind.VIDEO -> DocumentMedia.videoFrame(tree, item.path, px)
                FileKind.AUDIO -> DocumentMedia.albumArt(tree, item.path, px)
                else -> null // APK icons need a file path.
            }
        }
        val file = File(item.path)
        val size = Size(px, px)
        return when (item.kind) {
            FileKind.IMAGE -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ThumbnailUtils.createImageThumbnail(file, size, null)
            } else decodeSampled(file, px)
            FileKind.VIDEO -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ThumbnailUtils.createVideoThumbnail(file, size, null)
            } else {
                @Suppress("DEPRECATION")
                ThumbnailUtils.createVideoThumbnail(file.path, MediaStore.Video.Thumbnails.MINI_KIND)
            }
            FileKind.AUDIO -> albumArt(file, px)
            FileKind.APK -> apkIcon(context, file, px)
            else -> null
        }
    }

    // ------------------------------------------------------------------ gallery

    /** Thumbnail sizes the gallery asks for, so grids at different zoom levels share the cache. */
    val MEDIA_SIZES = intArrayOf(128, 192, 256, 320, 384)

    /** The bucket closest to [px]. */
    fun mediaSize(px: Int): Int = MEDIA_SIZES.minBy { kotlin.math.abs(it - px) }

    private fun mediaKey(item: MediaItem, px: Int) = "m|${item.path}|${item.dateModified}|${item.size}|$px"

    fun peekMedia(item: MediaItem, px: Int): Bitmap? = cache.get(mediaKey(item, px))

    /** Largest cached gallery thumbnail of [item]; the viewer shows it while the full picture loads. */
    fun peekMediaAny(item: MediaItem): Bitmap? {
        for (i in MEDIA_SIZES.indices.reversed()) cache.get(mediaKey(item, MEDIA_SIZES[i]))?.let { return it }
        return null
    }

    suspend fun loadMedia(context: Context, item: MediaItem, px: Int): Bitmap? {
        val k = mediaKey(item, px)
        cache.get(k)?.let { return it }
        if (k in misses) return null
        return withContext(decodeDispatcher) {
            cache.get(k) ?: runCatching { decodeMedia(context, item, px) }.getOrNull().also {
                if (it != null) cache.put(k, it) else misses += k
            }
        }
    }

    /** MediaStore's cached thumbnail first (fast, no full decode), then the file itself. */
    private fun decodeMedia(context: Context, item: MediaItem, px: Int): Bitmap? {
        val raw = fromMediaStore(context, item, px) ?: fromFile(item, px) ?: return null
        return shrink(raw, px)
    }

    private fun fromMediaStore(context: Context, item: MediaItem, px: Int): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val uri = MediaLibrary.contentUri(item) ?: return null
        return runCatching { context.contentResolver.loadThumbnail(uri, Size(px, px), null) }.getOrNull()
    }

    private fun fromFile(item: MediaItem, px: Int): Bitmap? {
        Stores.tree(item.path)?.let { tree ->
            return if (item.isVideo) DocumentMedia.videoFrame(tree, item.path, px)
            else DocumentMedia.image(tree, item.path, px, fitLongSide = false)
        }
        val file = File(item.path)
        if (item.isVideo) {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching { ThumbnailUtils.createVideoThumbnail(file, Size(px, px), null) }.getOrNull()
            } else {
                @Suppress("DEPRECATION")
                ThumbnailUtils.createVideoThumbnail(file.path, MediaStore.Video.Thumbnails.MINI_KIND)
            }
        }
        val viaSystem = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { ThumbnailUtils.createImageThumbnail(file, Size(px, px), null) }.getOrNull()
        } else null
        return viaSystem ?: decodeSampled(file, px)
    }

    /** Short side down to [px]: enough for square, center-cropped cells, and kind to the cache. */
    private fun shrink(bmp: Bitmap, px: Int): Bitmap {
        val short = min(bmp.width, bmp.height)
        if (short <= px * 5 / 4 || bmp.config == Bitmap.Config.HARDWARE) return bmp
        val k = px.toFloat() / short
        val w = (bmp.width * k).roundToInt().coerceAtLeast(1)
        val h = (bmp.height * k).roundToInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bmp, w, h, true)
        if (scaled !== bmp) bmp.recycle()
        return scaled
    }

    // ------------------------------------------------------------------ decoding helpers

    private fun decodeSampled(file: File, px: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= px && bounds.outHeight / (sample * 2) >= px) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val rotation = runCatching {
            when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        if (rotation == 0f) return bmp
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation) }, true)
    }

    private fun albumArt(file: File, px: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.path)
            val bytes = retriever.embeddedPicture ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= px && bounds.outHeight / (sample * 2) >= px) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun apkIcon(context: Context, file: File, px: Int): Bitmap? {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(file.path, 0)
        } ?: return null
        val app = info.applicationInfo ?: return null
        app.sourceDir = file.path
        app.publicSourceDir = file.path
        val drawable = app.loadIcon(pm) ?: return null
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, px, px)
        drawable.draw(canvas)
        return bmp
    }
}

/** Child counts for folder rows, cached per folder modification time. */
object FolderCounts {
    private val cache = ConcurrentHashMap<String, Int>()

    private fun key(item: FileItem, hidden: Boolean) = "${item.path}|${item.lastModified}|$hidden"

    fun peek(item: FileItem, includeHidden: Boolean): Int? = cache[key(item, includeHidden)]

    @OptIn(ExperimentalCoroutinesApi::class)
    private val dispatcher = Dispatchers.IO.limitedParallelism(4)

    suspend fun load(item: FileItem, includeHidden: Boolean): Int? {
        val k = key(item, includeHidden)
        cache[k]?.let { return it }
        return withContext(dispatcher) {
            val count = Stores.tree(item.path)?.let { tree ->
                (tree.list(item.path) as? ListResult.Ok)?.items?.count { includeHidden || !it.isHidden }
            } ?: DirectoryLister.childCount(File(item.path), includeHidden)
            count?.also { cache[k] = it }
        }
    }

    fun clear() = cache.clear()
}

package com.offlinefiles.explorer.ui.gallery

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.os.Build
import android.util.Size
import androidx.annotation.RequiresApi
import com.offlinefiles.explorer.core.gallery.MediaItem
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** Result of decoding a picture for the full-screen viewer. */
sealed interface Decoded {
    class Still(val bitmap: Bitmap) : Decoded

    /** Animated GIF or WebP (Android 9+). */
    class Animated(val drawable: Drawable) : Decoded

    data object Failed : Decoded
}

/** Facts shown in the details sheet that MediaStore doesn't always know. */
class MediaDetails(
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val camera: String?,
    val exposure: String?,
    val latitude: Double?,
    val longitude: Double?,
)

/** Decoding for the viewer. Everything here blocks: call it off the main thread. */
object MediaDecode {

    /** The picture scaled to fit [maxSide] on its long side, rotated as its EXIF data says. */
    fun full(item: MediaItem, maxSide: Int): Decoded {
        val file = File(item.path)
        if (!file.isFile) return Decoded.Failed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return withImageDecoder(file, maxSide)
            } catch (e: Exception) {
                // Fall through to BitmapFactory (some files decode there but not in ImageDecoder).
            } catch (e: OutOfMemoryError) {
                return Decoded.Failed
            }
        }
        return try {
            sampled(file, maxSide)?.let { Decoded.Still(it) } ?: Decoded.Failed
        } catch (e: Exception) {
            Decoded.Failed
        } catch (e: OutOfMemoryError) {
            Decoded.Failed
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun withImageDecoder(file: File, maxSide: Int): Decoded {
        val source = ImageDecoder.createSource(file)
        val listener = ImageDecoder.OnHeaderDecodedListener { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val long = max(w, h)
            if (long > maxSide) {
                val k = maxSide.toFloat() / long
                decoder.setTargetSize((w * k).roundToInt().coerceAtLeast(1), (h * k).roundToInt().coerceAtLeast(1))
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val ext = file.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext == "gif" || ext == "webp") {
            when (val d = ImageDecoder.decodeDrawable(source, listener)) {
                is AnimatedImageDrawable -> return Decoded.Animated(d)
                is BitmapDrawable -> return Decoded.Still(d.bitmap)
                else -> Unit
            }
        }
        return Decoded.Still(ImageDecoder.decodeBitmap(source, listener))
    }

    /** BitmapFactory path (Android 8, and a fallback): power-of-two sampling plus EXIF rotation. */
    private fun sampled(file: File, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val rotation = runCatching { rotationOf(ExifInterface(file.path)) }.getOrDefault(0)
        if (rotation == 0) return bmp
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
        if (rotated !== bmp) bmp.recycle()
        return rotated
    }

    /** A still frame for a video page (the video itself plays in another app for now). */
    fun videoFrame(item: MediaItem, maxSide: Int): Bitmap? {
        val file = File(item.path)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { ThumbnailUtils.createVideoThumbnail(file, Size(maxSide, maxSide), null) }.getOrNull()?.let { return it }
        }
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.path)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                r.getScaledFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, maxSide, maxSide)
            } else {
                r.frameAtTime
            }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    /** Resolution, length, camera and exposure, read from the file. */
    @Suppress("DEPRECATION")
    fun details(item: MediaItem): MediaDetails {
        var w = item.width
        var h = item.height
        var duration = item.durationMs
        var camera: String? = null
        var exposure: String? = null
        var lat: Double? = null
        var lon: Double? = null
        if (item.isVideo) {
            if (w <= 0 || h <= 0 || duration <= 0) {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(item.path)
                    val vw = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    val vh = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    if (w <= 0 || h <= 0) {
                        val swap = rotation == 90 || rotation == 270
                        w = if (swap) vh else vw
                        h = if (swap) vw else vh
                    }
                    if (duration <= 0) duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                } catch (e: Exception) {
                    // Unreadable or unsupported video: show what we know.
                } finally {
                    runCatching { r.release() }
                }
            }
        } else {
            val exif = try {
                ExifInterface(item.path)
            } catch (e: Exception) {
                null
            }
            if (w <= 0 || h <= 0) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(item.path, bounds)
                if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                    val swap = exif != null && rotationOf(exif).let { it == 90 || it == 270 }
                    w = if (swap) bounds.outHeight else bounds.outWidth
                    h = if (swap) bounds.outWidth else bounds.outHeight
                }
            }
            if (exif != null) {
                camera = cameraOf(exif)
                exposure = exposureOf(exif)
                val ll = FloatArray(2)
                if (exif.getLatLong(ll)) {
                    lat = ll[0].toDouble()
                    lon = ll[1].toDouble()
                }
            }
        }
        return MediaDetails(w, h, duration, camera, exposure, lat, lon)
    }

    fun rotationOf(exif: ExifInterface): Int =
        when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 270
            else -> 0
        }

    private fun cameraOf(exif: ExifInterface): String? {
        val make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim().orEmpty()
        val model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim().orEmpty()
        return when {
            model.isEmpty() -> make.ifEmpty { null }
            make.isEmpty() || model.startsWith(make, ignoreCase = true) -> model
            else -> "$make $model"
        }
    }

    /** "f/1.8 · 1/120s · ISO 100 · 5.4mm". */
    private fun exposureOf(exif: ExifInterface): String? {
        val parts = ArrayList<String>(4)
        val f = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0)
        if (f > 0) parts.add("f/" + short(f))
        val t = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)
        if (t > 0) parts.add(if (t < 1.0) "1/${(1.0 / t).roundToInt()}s" else short(t) + "s")
        val iso = exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0)
        if (iso > 0) parts.add("ISO $iso")
        val focal = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0)
        if (focal > 0) parts.add(short(focal) + "mm")
        return if (parts.isEmpty()) null else parts.joinToString(" · ")
    }

    private fun short(v: Double): String =
        if (v == Math.floor(v)) v.toLong().toString() else String.format(Locale.ROOT, "%.1f", v)
}

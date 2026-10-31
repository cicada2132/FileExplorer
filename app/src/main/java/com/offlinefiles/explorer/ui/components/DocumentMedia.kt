package com.offlinefiles.explorer.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.annotation.RequiresApi
import com.offlinefiles.explorer.data.DocumentTree
import com.offlinefiles.explorer.ui.gallery.MediaDecode
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pictures, video frames and cover art from files on USB drives that are opened through the
 * system file picker. Those have no file paths, so everything is read through the provider.
 * Everything here blocks: call it off the main thread.
 */
object DocumentMedia {

    /**
     * The picture at [path], turned upright, with its long side ([fitLongSide]) or short side
     * (for cropped thumbnails) scaled down to about [px].
     */
    fun image(tree: DocumentTree, path: String, px: Int, fitLongSide: Boolean): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return withImageDecoder(tree, path, px, fitLongSide)
            } catch (e: Exception) {
                // Fall through to BitmapFactory.
            } catch (e: OutOfMemoryError) {
                return null
            }
        }
        return try {
            sampled(tree, path, px, fitLongSide)
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun withImageDecoder(tree: DocumentTree, path: String, px: Int, fitLongSide: Boolean): Bitmap {
        val source = ImageDecoder.createSource(tree.resolver, tree.documentUri(path))
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val side = if (fitLongSide) max(w, h) else min(w, h)
            if (side > px) {
                val k = px.toFloat() / side
                decoder.setTargetSize((w * k).roundToInt().coerceAtLeast(1), (h * k).roundToInt().coerceAtLeast(1))
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }

    /** BitmapFactory with power-of-two sampling, then the EXIF rotation. */
    private fun sampled(tree: DocumentTree, path: String, px: Int, fitLongSide: Boolean): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        tree.openInput(path).use { BitmapFactory.decodeStream(it, null, bounds) }
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        var sample = 1
        if (fitLongSide) {
            while (max(w, h) / sample > px) sample *= 2
        } else {
            while (w / (sample * 2) >= px && h / (sample * 2) >= px) sample *= 2
        }
        val bmp = tree.openInput(path).use { input ->
            BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val rotation = runCatching { tree.openInput(path).use { MediaDecode.rotationOf(ExifInterface(it)) } }.getOrDefault(0)
        if (rotation == 0) return bmp
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
        if (rotated !== bmp) bmp.recycle()
        return rotated
    }

    /** A still frame of the video at [path]. */
    fun videoFrame(tree: DocumentTree, path: String, px: Int): Bitmap? = withRetriever(tree, path) { r ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            r.getScaledFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, px, px)
        } else {
            r.frameAtTime
        }
    }

    /** The cover picture embedded in a music file. */
    fun albumArt(tree: DocumentTree, path: String, px: Int): Bitmap? = withRetriever(tree, path) { r ->
        val bytes = r.embeddedPicture ?: return@withRetriever null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= px && bounds.outHeight / (sample * 2) >= px) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Runs [block] with a metadata retriever reading [path], or returns null when that fails. */
    fun <T> withRetriever(tree: DocumentTree, path: String, block: (MediaMetadataRetriever) -> T?): T? {
        val pfd = tree.openFile(path) ?: return null
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(pfd.fileDescriptor)
            block(r)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { r.release() }
            runCatching { pfd.close() }
        }
    }
}

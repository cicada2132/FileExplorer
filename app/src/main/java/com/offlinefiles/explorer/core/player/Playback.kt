package com.offlinefiles.explorer.core.player

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * OFF plays the queue once and stops; ALL loops the queue; ONE loops the current file.
 * Declared in the order the repeat button cycles (as in VLC and music apps).
 */
enum class RepeatMode {
    OFF, ALL, ONE;

    fun next(): RepeatMode = entries[(ordinal + 1) % entries.size]
}

/** How the picture fills the screen, in the order the aspect button cycles through them. */
enum class AspectMode {
    FIT, FILL, STRETCH, ORIGINAL, RATIO_16_9, RATIO_4_3;

    fun next(): AspectMode = entries[(ordinal + 1) % entries.size]
}

/** Screen orientation while a video plays. AUTO follows the video: landscape videos turn the screen. */
enum class RotationMode {
    AUTO, LANDSCAPE, PORTRAIT;

    fun next(): RotationMode = entries[(ordinal + 1) % entries.size]
}

object VideoLayout {
    /**
     * Size of the video on a [boxW]×[boxH] screen (pixels). Unknown video sizes fill the screen;
     * the picture is centred and anything outside the screen is clipped.
     */
    fun size(mode: AspectMode, videoW: Int, videoH: Int, boxW: Float, boxH: Float): Pair<Float, Float> {
        if (boxW <= 0f || boxH <= 0f) return 0f to 0f
        if (videoW <= 0 || videoH <= 0) return boxW to boxH
        val vw = videoW.toFloat()
        val vh = videoH.toFloat()
        return when (mode) {
            AspectMode.FIT -> min(boxW / vw, boxH / vh).let { vw * it to vh * it }
            AspectMode.FILL -> max(boxW / vw, boxH / vh).let { vw * it to vh * it }
            AspectMode.STRETCH -> boxW to boxH
            AspectMode.ORIGINAL -> vw to vh
            AspectMode.RATIO_16_9 -> ratio(16f / 9f, boxW, boxH)
            AspectMode.RATIO_4_3 -> ratio(4f / 3f, boxW, boxH)
        }
    }

    private fun ratio(r: Float, boxW: Float, boxH: Float): Pair<Float, Float> =
        if (boxW / boxH > r) boxH * r to boxH else boxW to boxW / r
}

object PlayQueue {
    /**
     * The entry after (step = 1) or before (step = -1) [current] in [order], a permutation of the
     * queue's indices (shuffled or not). Null at either end unless the whole queue repeats.
     */
    fun step(order: List<Int>, current: Int, step: Int, repeat: RepeatMode): Int? {
        if (order.isEmpty()) return null
        val at = order.indexOf(current).coerceAtLeast(0)
        val next = at + step
        return when {
            next in order.indices -> order[next]
            repeat == RepeatMode.ALL -> order[Math.floorMod(next, order.size)]
            else -> null
        }
    }

    /** What plays when [current] ends by itself. */
    fun afterCompletion(order: List<Int>, current: Int, repeat: RepeatMode): Int? =
        if (repeat == RepeatMode.ONE) current else step(order, current, 1, repeat)

    /** Shuffled order that starts with [current], so the file on screen keeps playing. */
    fun shuffled(size: Int, current: Int, random: java.util.Random = java.util.Random()): List<Int> {
        val rest = (0 until size).filter { it != current }.toMutableList()
        java.util.Collections.shuffle(rest, random)
        return if (current in 0 until size) listOf(current) + rest else rest
    }
}

object Resume {
    /** Positions this close to either end aren't worth coming back to. */
    const val MIN_MS = 5_000L
    const val TAIL_MS = 10_000L

    /** Music under ten minutes starts from the top; videos, podcasts and audiobooks resume. */
    const val MIN_AUDIO_MS = 10 * 60_000L

    fun applies(durationMs: Long, isVideo: Boolean): Boolean = isVideo || durationMs >= MIN_AUDIO_MS

    fun shouldRemember(positionMs: Long, durationMs: Long): Boolean =
        positionMs >= MIN_MS && (durationMs <= 0 || positionMs < durationMs - TAIL_MS)
}

object PlaybackSpeed {
    const val MIN = 0.25f
    const val MAX = 4f
    val PRESETS = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

    /** Snapped to 0.05 steps inside the supported range. */
    fun clamp(speed: Float): Float = ((speed.coerceIn(MIN, MAX) * 20f).roundToInt() / 20f)

    /** "1x", "1.5x", "1.25x". */
    fun label(speed: Float): String {
        val s = String.format(Locale.ROOT, "%.2f", clamp(speed)).trimEnd('0').trimEnd('.')
        return "${s}x"
    }
}

object PlayerTime {
    /** "0:07", "12:05", "1:02:03"; [hours] forces the hour field so times line up in long videos. */
    fun format(ms: Long, hours: Boolean = false): String {
        val total = ms.coerceAtLeast(0) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0 || hours) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.ROOT, "%d:%02d", m, s)
    }

    /** "+0:15" or "−1:05" for seek feedback. */
    fun signed(deltaMs: Long): String = (if (deltaMs < 0) "−" else "+") + format(abs(deltaMs))

    /**
     * How far a horizontal swipe across [fraction] of the screen width seeks: a full swipe covers
     * the whole of a short clip and two minutes of anything longer.
     */
    fun swipeSeek(fraction: Float, durationMs: Long): Long {
        val perWidth = durationMs.coerceIn(10_000L, 120_000L)
        return (fraction * perWidth).toLong()
    }
}

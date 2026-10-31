package com.offlinefiles.explorer.data

import android.content.Context
import com.offlinefiles.explorer.core.player.AspectMode
import com.offlinefiles.explorer.core.player.RepeatMode
import com.offlinefiles.explorer.core.player.RotationMode

/**
 * What the player remembers between files and launches: the last screen fit, repeat mode,
 * subtitle size, and where each file was left off (the most recent few hundred files).
 */
class PlayerStore(context: Context) {
    private val sp = context.getSharedPreferences("player", Context.MODE_PRIVATE)
    private val resume = context.getSharedPreferences("player_resume", Context.MODE_PRIVATE)

    var aspect: AspectMode
        get() = enumOr(sp.getString("aspect", null), AspectMode.FIT)
        set(v) = sp.edit().putString("aspect", v.name).apply()

    var repeat: RepeatMode
        get() = enumOr(sp.getString("repeat", null), RepeatMode.OFF)
        set(v) = sp.edit().putString("repeat", v.name).apply()

    var shuffle: Boolean
        get() = sp.getBoolean("shuffle", false)
        set(v) = sp.edit().putBoolean("shuffle", v).apply()

    var rotation: RotationMode
        get() = enumOr(sp.getString("rotation", null), RotationMode.AUTO)
        set(v) = sp.edit().putString("rotation", v.name).apply()

    var subtitleScale: Float
        get() = sp.getFloat("subtitle_scale", 1f).coerceIn(MIN_SUBTITLE_SCALE, MAX_SUBTITLE_SCALE)
        set(v) = sp.edit().putFloat("subtitle_scale", v.coerceIn(MIN_SUBTITLE_SCALE, MAX_SUBTITLE_SCALE)).apply()

    /** Where [path] was left off, or null. */
    fun position(path: String): Long? =
        resume.getString(path, null)?.substringBefore(',')?.toLongOrNull()?.takeIf { it > 0 }

    fun remember(path: String, positionMs: Long) {
        resume.edit().putString(path, "$positionMs,${System.currentTimeMillis()}").apply()
        trim()
    }

    fun forget(path: String) {
        if (resume.contains(path)) resume.edit().remove(path).apply()
    }

    private fun trim() {
        val all = resume.all
        if (all.size <= MAX_ENTRIES) return
        val oldest = all.entries
            .sortedBy { (it.value as? String)?.substringAfter(',')?.toLongOrNull() ?: 0L }
            .take(all.size - MAX_ENTRIES)
        resume.edit().apply { oldest.forEach { remove(it.key) } }.apply()
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: fallback

    companion object {
        const val MAX_ENTRIES = 300
        const val MIN_SUBTITLE_SCALE = 0.6f
        const val MAX_SUBTITLE_SCALE = 2f
    }
}

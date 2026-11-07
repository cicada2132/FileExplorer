package com.offlinefiles.explorer.data

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build

/**
 * VLC runs native code that can take the whole app down. This notes when VLC is playing; if the
 * app then dies with a crash, VLC is switched off (Settings can switch it back on), so a file
 * that crashes it can't close the app again and again.
 */
object VlcSafety {
    private const val PREFS = "vlc_safety"
    private const val KEY_ACTIVE = "active_since"
    private const val KEY_DISABLED = "disabled"
    private const val KEY_UNSEEN = "unseen"
    private const val KEY_DETAIL = "detail"

    /** Checked once per process start, before anything uses VLC. */
    fun checkLastRun(context: Context) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val since = sp.getLong(KEY_ACTIVE, 0L)
        if (since == 0L) return
        val exit = lastExit(context)
        val crashed = if (exit == null) {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R
        } else {
            exit.timestamp >= since && exit.reason in CRASHES
        }
        val edit = sp.edit().remove(KEY_ACTIVE)
        if (crashed) {
            edit.putBoolean(KEY_DISABLED, true).putBoolean(KEY_UNSEEN, true)
                .putString(KEY_DETAIL, exit?.let { describe(it) } ?: "process ended while VLC was playing")
        }
        edit.commit()
    }

    fun started(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_ACTIVE, System.currentTimeMillis()).commit()
    }

    fun stopped(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_ACTIVE).apply()
    }

    fun disabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DISABLED, false)

    fun setDisabled(context: Context, off: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DISABLED, off).apply()
    }

    /** True once after VLC was switched off by a crash. */
    fun takeUnseen(context: Context): Boolean {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!sp.getBoolean(KEY_UNSEEN, false)) return false
        sp.edit().putBoolean(KEY_UNSEEN, false).apply()
        return true
    }

    fun detail(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_DETAIL, null)

    /** The most recent ways this app's process ended (Android 11+), newest first. */
    fun exitReasons(context: Context, max: Int = 5): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        val am = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        return runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, max) }
            .getOrDefault(emptyList())
            .map { describe(it) }
    }

    private fun lastExit(context: Context): ApplicationExitInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val am = context.getSystemService(ActivityManager::class.java) ?: return null
        return runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() }.getOrNull()
    }

    private val CRASHES = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        setOf(ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_EXIT_SELF)
    } else {
        emptySet()
    }

    private fun describe(e: ApplicationExitInfo): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return ""
        val reason = when (e.reason) {
            ApplicationExitInfo.REASON_CRASH -> "crash (Java)"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash (native)"
            ApplicationExitInfo.REASON_EXIT_SELF -> "exit by itself"
            ApplicationExitInfo.REASON_ANR -> "not responding"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory"
            ApplicationExitInfo.REASON_SIGNALED -> "killed by signal"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "closed by the user"
            ApplicationExitInfo.REASON_USER_STOPPED -> "stopped by the user"
            else -> "reason ${e.reason}"
        }
        val time = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(e.timestamp))
        return "$time $reason status=${e.status} ${e.description.orEmpty()}".trim()
    }
}

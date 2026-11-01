package com.offlinefiles.explorer.data

import android.content.Context
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the stack trace of the last crash on the device, so the user can copy it from
 * Settings › Diagnostics (the app has no network access and sends nothing anywhere).
 */
object CrashLog {
    private const val PREFS = "crash_log"
    private const val KEY_TRACE = "trace"
    private const val KEY_UNSEEN = "unseen"
    private const val MAX_CHARS = 12_000

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { record(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun record(context: Context, thread: Thread, error: Throwable) {
        val out = StringWriter()
        PrintWriter(out).use { error.printStackTrace(it) }
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val text = "$time thread=${thread.name}\n$out".take(MAX_CHARS)
        // commit(): the process is about to die, apply() might not reach the disk.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_TRACE, text)
            .putBoolean(KEY_UNSEEN, true)
            .commit()
    }

    fun last(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TRACE, null)

    /** True once after a crash: the next start can say where to find the details. */
    fun takeUnseen(context: Context): Boolean {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!sp.getBoolean(KEY_UNSEEN, false)) return false
        sp.edit().putBoolean(KEY_UNSEEN, false).apply()
        return true
    }
}
